package io.github.davidvornholt.glimpse.shared.upload

import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.Photo
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.api.TEST_PHOTO_ID
import io.github.davidvornholt.glimpse.shared.api.TEST_TIME
import io.github.davidvornholt.glimpse.shared.api.testPhoto
import io.github.davidvornholt.glimpse.shared.settings.PairingId
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadRunnerTest {
    private val id = TEST_PHOTO_ID

    private class MemoryRecords(
        vararg records: CaptureRecord,
    ) : CaptureRecords {
        val records = records.associateBy { it.id }.toMutableMap()

        override suspend fun get(id: String) = records[id]

        override suspend fun update(
            id: String,
            change: (CaptureRecord) -> CaptureRecord,
        ): CaptureRecord? = records[id]?.let(change)?.also { records[id] = it }
    }

    /** Answers like a server would, unless [failures] has an error queued for the step. */
    private class FakeServer : UploadTarget {
        val calls = mutableListOf<UploadStep>()
        val failures = mutableMapOf<UploadStep, ArrayDeque<Exception>>()

        /** What `GET /v1/photos/{id}` says: whether the photo exists, or null for no answer. */
        var exists: Boolean? = true
        private var note: String? = null

        fun failNext(
            step: UploadStep,
            error: Exception,
        ) {
            failures.getOrPut(step) { ArrayDeque() }.addLast(error)
        }

        private fun answer(
            step: UploadStep,
            record: CaptureRecord,
        ): Photo {
            calls += step
            failures[step]?.removeFirstOrNull()?.let { throw it }
            return testPhoto(id = record.id, note = note)
        }

        override suspend fun register(record: CaptureRecord): Photo {
            note = record.note
            return answer(UploadStep.Register, record)
        }

        override suspend fun setNote(record: CaptureRecord): Photo {
            val answer = answer(UploadStep.Note, record)
            note = record.note
            return answer.copy(note = note)
        }

        override suspend fun putPreview(
            record: CaptureRecord,
            onProgress: (sent: Long, total: Long) -> Unit,
        ): Photo {
            onProgress(50, 100)
            onProgress(100, 100)
            return answer(UploadStep.Preview, record)
        }

        override suspend fun putOriginal(
            record: CaptureRecord,
            onProgress: (sent: Long, total: Long) -> Unit,
        ): Photo = answer(UploadStep.Original, record)

        override suspend fun exists(record: CaptureRecord): Boolean = exists ?: throw IOException("Connection reset")
    }

    private fun record(note: String? = null) = CaptureRecord(id, TEST_TIME, PhotoSource.Camera, 4032, 3024, OriginalType.Jpeg, note = note)

    @Test
    fun `a worker using a different pairing makes no request`() =
        runTest {
            val server = FakeServer()
            val owner = PairingId("https://glimpse.example.com", "first")
            val record = record().copy(pairing = owner)
            val runner = UploadRunner(server, MemoryRecords(record))
            assertEquals(UploadOutcome.Gone, runner.run(id, owner.copy(deviceId = "second")))
            assertTrue(server.calls.isEmpty())
            assertEquals(UploadOutcome.Done, runner.run(id, owner))
        }

    @Test
    fun `uploads a new photo step by step`() =
        runTest {
            val server = FakeServer()
            val records = MemoryRecords(record(note = "The one on the left"))
            val progress = mutableListOf<Triple<String, UploadStep, Long>>()

            val outcome = UploadRunner(server, records) { id, step, sent, _ -> progress += Triple(id, step, sent) }.run(id)

            assertEquals(UploadOutcome.Done, outcome)
            assertEquals(listOf(UploadStep.Register, UploadStep.Preview, UploadStep.Original), server.calls)
            assertEquals(listOf(Triple(id, UploadStep.Preview, 50L), Triple(id, UploadStep.Preview, 100L)), progress)
            val done = records.records.getValue(id)
            assertTrue(done.isDone)
            assertEquals("The one on the left", done.serverNote)
        }

    @Test
    fun `sends a note added after the photo was registered`() =
        runTest {
            val server = FakeServer()
            val records = MemoryRecords(record().copy(registered = true, registrations = 1).withNote("Changed my mind"))

            assertEquals(UploadOutcome.Done, UploadRunner(server, records).run(id))

            assertEquals(listOf(UploadStep.Note, UploadStep.Preview, UploadStep.Original), server.calls)
            assertEquals("Changed my mind", records.records.getValue(id).serverNote)
        }

    @Test
    fun `waits for the network and carries on where it stopped`() =
        runTest {
            val server = FakeServer()
            val records = MemoryRecords(record())
            server.failNext(UploadStep.Preview, IOException("Connection reset"))

            assertEquals(UploadOutcome.RetryLater, UploadRunner(server, records).run(id))
            assertEquals(true, records.records.getValue(id).registered)
            assertNull(records.records.getValue(id).failure)

            assertEquals(UploadOutcome.Done, UploadRunner(server, records).run(id))
            assertEquals(
                listOf(UploadStep.Register, UploadStep.Preview, UploadStep.Preview, UploadStep.Original),
                server.calls,
            )
        }

    @Test
    fun `registers again when the server lost the photo`() =
        runTest {
            val server = FakeServer()
            val records = MemoryRecords(record())
            server.failNext(UploadStep.Preview, ApiException(404, ApiErrorTag.NotFound, "No such photo"))

            assertEquals(UploadOutcome.Done, UploadRunner(server, records).run(id))

            assertEquals(
                listOf(UploadStep.Register, UploadStep.Preview, UploadStep.Register, UploadStep.Preview, UploadStep.Original),
                server.calls,
            )
            assertEquals(2, records.records.getValue(id).registrations)
        }

    @Test
    fun `doesn't bring back a photo deleted once an agent could see it`() =
        runTest {
            val server = FakeServer()
            val records = MemoryRecords(record())
            server.failNext(UploadStep.Original, ApiException(404, ApiErrorTag.NotFound, "No such photo"))
            server.exists = false

            assertEquals(UploadOutcome.Done, UploadRunner(server, records).run(id))

            assertEquals(listOf(UploadStep.Register, UploadStep.Preview, UploadStep.Original), server.calls)
            assertTrue(records.records.getValue(id).deletedOnServer)
        }

    // A server that couldn't store the original answers 404 too, and the phone holds the only copy.
    @Test
    fun `keeps a photo the server still has when it turns down the original`() =
        runTest {
            val server = FakeServer()
            val records = MemoryRecords(record())
            server.failNext(UploadStep.Original, ApiException(404, ApiErrorTag.NotFound, "No such photo"))
            server.failNext(UploadStep.Original, ApiException(404, ApiErrorTag.NotFound, "No such photo"))

            for (answer in listOf(true, null)) {
                server.exists = answer
                assertEquals(UploadOutcome.RetryLater, UploadRunner(server, records).run(id))
                assertEquals(false, records.records.getValue(id).deletedOnServer)
            }

            assertEquals(UploadOutcome.Done, UploadRunner(server, records).run(id))
            assertEquals(true, records.records.getValue(id).originalSent)
        }

    @Test
    fun `waits when the phone can't save how far the upload got`() =
        runTest {
            val server = FakeServer()
            val full =
                object : CaptureRecords {
                    override suspend fun get(id: String) = record()

                    override suspend fun update(
                        id: String,
                        change: (CaptureRecord) -> CaptureRecord,
                    ): CaptureRecord = throw IOException("No space left on device")
                }

            assertEquals(UploadOutcome.RetryLater, UploadRunner(server, full).run(id))
        }

    @Test
    fun `finishes without an original the server finds too large`() =
        runTest {
            val server = FakeServer()
            val records = MemoryRecords(record())
            server.failNext(UploadStep.Original, ApiException(413, ApiErrorTag.TooLarge, "Too large"))

            assertEquals(UploadOutcome.Done, UploadRunner(server, records).run(id))

            val done = records.records.getValue(id)
            assertEquals(true, done.originalSkipped)
            assertEquals(false, done.originalSent)
            assertTrue(done.isReady)
        }

    @Test
    fun `stops without failing the photo when the phone was unpaired`() =
        runTest {
            val server = FakeServer()
            val records = MemoryRecords(record())
            server.failNext(UploadStep.Register, ApiException(401, ApiErrorTag.Unauthorized, "Unknown token"))

            assertEquals(UploadOutcome.Unpaired, UploadRunner(server, records).run(id))

            assertNull(records.records.getValue(id).failure)
        }

    @Test
    fun `keeps a refused photo failed`() =
        runTest {
            val server = FakeServer()
            val records = MemoryRecords(record())
            server.failNext(UploadStep.Register, ApiException(409, ApiErrorTag.Conflict, "Another photo has this id"))

            assertEquals(UploadOutcome.Failed, UploadRunner(server, records).run(id))
            assertEquals(UploadFailure.Conflict, records.records.getValue(id).failure)

            assertEquals(UploadOutcome.Failed, UploadRunner(server, records).run(id))
            assertEquals(listOf(UploadStep.Register), server.calls)
        }

    @Test
    fun `gives up on a photo that was deleted`() =
        runTest {
            val server = FakeServer()

            assertEquals(UploadOutcome.Gone, UploadRunner(server, MemoryRecords()).run(id))
            assertTrue(server.calls.isEmpty())
        }
}
