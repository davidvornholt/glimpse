package io.github.davidvornholt.glimpse.features.pairing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.api.ClaimResult
import io.github.davidvornholt.glimpse.shared.api.Device
import io.github.davidvornholt.glimpse.shared.api.GlimpseApi
import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.Photo
import io.github.davidvornholt.glimpse.shared.api.PhotoMetadata
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.api.ServerEvent
import io.github.davidvornholt.glimpse.shared.api.TEST_PHOTO_ID
import io.github.davidvornholt.glimpse.shared.api.TEST_TIME
import io.github.davidvornholt.glimpse.shared.api.testPhoto
import io.github.davidvornholt.glimpse.shared.image.Gallery
import io.github.davidvornholt.glimpse.shared.pairing.PairingLink
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import io.github.davidvornholt.glimpse.shared.photos.PhotoFeed
import io.github.davidvornholt.glimpse.shared.photos.PhotoLibrary
import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.settings.SessionState
import io.github.davidvornholt.glimpse.shared.settings.SessionStore
import io.github.davidvornholt.glimpse.shared.settings.SettingsStore
import io.github.davidvornholt.glimpse.shared.ulid.UlidGenerator
import io.github.davidvornholt.glimpse.shared.upload.CaptureRecord
import io.github.davidvornholt.glimpse.shared.upload.CaptureStore
import io.github.davidvornholt.glimpse.shared.upload.DeleteScheduler
import io.github.davidvornholt.glimpse.shared.upload.MissingFileException
import io.github.davidvornholt.glimpse.shared.upload.UploadFailure
import io.github.davidvornholt.glimpse.shared.upload.UploadRunner
import io.github.davidvornholt.glimpse.shared.upload.UploadScheduler
import io.github.davidvornholt.glimpse.shared.upload.UploadStep
import io.github.davidvornholt.glimpse.shared.upload.UploadTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PairingBoundaryTest {
    private val root = Files.createTempDirectory("pairing").toFile()
    private val server = "https://glimpse.example.com"
    private val link = PairingLink("$server/second", "code")
    private val old = Session(server, "test", "first-device", "Phone")
    private val newDevice = "new-device"
    private val sessions = mock(SessionStore::class.java)
    private val settings = mock(SettingsStore::class.java)
    private val uploads = mock(UploadScheduler::class.java)
    private val state = MutableStateFlow<SessionState>(SessionState.Paired(old))
    private val captures = CaptureStore(File(root, "captures"))
    private val record = CaptureRecord(TEST_PHOTO_ID, TEST_TIME, PhotoSource.Camera, 1, 1, OriginalType.Jpeg)
    private val api = mock(GlimpseApi::class.java)
    private val remote = FakeServer(captures)
    private val revoked = mutableListOf<File>()
    private var gallery = mock(Gallery::class.java)
    private lateinit var feed: PhotoFeed
    private lateinit var library: PhotoLibrary

    private suspend fun TestScope.claimer(): PairingClaimer {
        val deletes = mock(DeleteScheduler::class.java)
        `when`(uploads.progress).thenReturn(MutableStateFlow(emptyMap()))
        `when`(deletes.pending).thenReturn(flowOf(emptySet()))
        `when`(sessions.state).thenReturn(state)
        `when`(sessions.lastServer()).thenReturn(server)
        `when`(api.claim("code", "Phone")).thenReturn(ClaimResult("new-test", Device(newDevice, "Phone", TEST_TIME)))
        `when`(api.events()).thenReturn(
            flow {
                emit(ServerEvent.PhotoChanged(testPhoto()))
                emit(ServerEvent.Waiting(2))
                awaitCancellation()
            },
        )
        feed = PhotoFeed(sessions, { api }, backgroundScope)
        library =
            PhotoLibrary(
                captures,
                PreferenceDataStoreFactory.create(scope = backgroundScope) { File(root, "undo.preferences_pb") },
                mock(),
                uploads,
                deletes,
                feed,
                sessions,
                settings,
                mock(),
                gallery,
                UlidGenerator(),
                { api },
                root,
                { revoked.add(it) },
                backgroundScope,
            )
        return PairingClaimer({ api }, sessions, library)
    }

    /** Keeps a capture and its image files, as the camera leaves them. */
    private suspend fun keep(
        capture: CaptureRecord,
        withOriginal: Boolean = true,
    ) {
        captures.prepare(capture.id)
        if (withOriginal) captures.originalFile(capture).writeText("original")
        captures.previewFile(capture.id).writeText("preview")
        captures.add(capture)
    }

    /** Runs every upload to its end, as the upload worker does once the phone is paired again. */
    private suspend fun uploadAll(session: Session) {
        val target = remote.asDevice(session.deviceId)
        captures.records.value.keys
            .forEach { UploadRunner(target, captures).run(it, session.server) }
    }

    /** A shared photo the old device uploaded completely, whose note was changed on the phone since. */
    private suspend fun TestScope.sendsOnlyTheNoteOfAnUploadedShare(note: String?) {
        val claimer = claimer()
        val shared = record.copy(source = PhotoSource.Share, server = old.server)
        remote.registerAs(old.deviceId, shared.copy(note = "old caption"))
        remote.photos[record.id] = remote.photos.getValue(record.id).copy(preview = "preview", original = "original")
        val uploaded =
            shared
                .copy(
                    note = "old caption",
                    serverNote = "old caption",
                    registered = true,
                    previewSent = true,
                    originalSent = true,
                    registrations = 1,
                ).withNote(note)
        keep(uploaded, withOriginal = false)
        uploadAll(claimer.claim(link.copy(server = server), "Phone"))
        assertEquals(listOf(UploadStep.Note to record.id), remote.requests)
        assertEquals(note, remote.photos.getValue(record.id).note)
        val kept =
            captures.records.value.values
                .single()
        assertEquals(record.id, kept.id)
        assertEquals(note, kept.serverNote)
        assertEquals(0, kept.noteEdit)
    }

    private fun assertFinished(note: String? = null) {
        val uploaded =
            captures.records.value.values
                .single()
        assertEquals(record.id, uploaded.id)
        assertTrue(uploaded.isDone, "$uploaded")
        assertEquals(note, uploaded.note)
        assertEquals(setOf(record.id), remote.photos.keys)
        val stored = remote.photos.getValue(uploaded.id)
        assertEquals(FakeServer.Stored(old.deviceId, uploaded.metadata.copy(note = null), note, "preview", "original"), stored)
        // The phone deletes its original only once the server has it.
        assertFalse(captures.originalFile(uploaded).exists())
    }

    private companion object {
        const val UNDO_MILLIS = 10_000L
    }

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    @Test fun `warns about unsent originals after the device is removed`() =
        runTest {
            val claimer = claimer()
            state.value = SessionState.Unpaired(server)
            keep(record)
            assertEquals(PairingClaimer.SwitchWarning(server, 1), claimer.switchWarning(link))
        }

    @Test fun `counts an original whose record can't be read`() =
        runTest {
            val claimer = claimer()
            val directory = captures.directory(record.id).apply { mkdirs() }
            File(directory, "meta.json").writeText("{ not a record")
            File(directory, "original.jpg").writeText("original")
            assertEquals(PairingClaimer.SwitchWarning(server, 1), claimer.switchWarning(link))
        }

    @Test fun `doesn't count a photo whose original is already gone`() =
        runTest {
            val claimer = claimer()
            keep(record, withOriginal = false)
            assertEquals(PairingClaimer.SwitchWarning(server, 0), claimer.switchWarning(link))
        }

    @Test fun `keeps captures on a previously stored default port`() =
        runTest {
            val claimer = claimer()
            `when`(sessions.lastServer()).thenReturn("$server:443")
            keep(record)
            claimer.claim(link.copy(server = server), "Phone")
            assertEquals(
                record,
                captures.records.value.values
                    .single(),
            )
        }

    @Test fun `a registration without a preview finishes after pairing again`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record.copy(note = "caption"))
            keep(record.copy(registered = true, note = "caption", registrations = 1))
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            assertFinished(note = "caption")
        }

    @Test fun `a photo whose original the old device never sent finishes after pairing again`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record)
            remote.photos[record.id] = remote.photos.getValue(record.id).copy(preview = "preview")
            keep(record.copy(registered = true, previewSent = true, registrations = 1))
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            assertFinished()
        }

    @Test fun `a registration whose reply was lost finishes after pairing again`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record)
            keep(record)
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            assertFinished()
        }

    @Test fun `a registration that conflicted finishes after pairing again`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record)
            keep(record.copy(failure = UploadFailure.Conflict))
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            assertFinished()
        }

    @Test fun `a photo rejected after an earlier pairing finishes after pairing again`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record)
            // Released versions marked the server's 403 for another device as a rejection.
            keep(record.copy(registered = true, failure = UploadFailure.Rejected))
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            assertFinished()
        }

    @Test fun `a fully uploaded photo keeps its id and sends only its new note`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record.copy(note = "old caption"))
            remote.photos[record.id] = remote.photos.getValue(record.id).copy(preview = "preview", original = "original")
            val uploaded =
                record
                    .copy(
                        note = "old caption",
                        serverNote = "old caption",
                        registered = true,
                        previewSent = true,
                        originalSent = true,
                        registrations = 1,
                    ).withNote("new caption")
            keep(uploaded, withOriginal = false)
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            assertEquals(listOf(UploadStep.Note to record.id), remote.requests)
            assertEquals("new caption", remote.photos.getValue(record.id).note)
            assertEquals(
                uploaded.copy(serverNote = "new caption", noteEdit = 0),
                captures.records.value.values
                    .single(),
            )
        }

    @Test fun `a fully uploaded share sends its new note after pairing again with the same server`() =
        runTest {
            sendsOnlyTheNoteOfAnUploadedShare("new caption")
        }

    @Test fun `a fully uploaded share sends its removed note after pairing again with the same server`() =
        runTest {
            sendsOnlyTheNoteOfAnUploadedShare(null)
        }

    @Test fun `an unfinished share finishes uploading after pairing again with the same server`() =
        runTest {
            val claimer = claimer()
            val shared = record.copy(source = PhotoSource.Share, server = old.server)
            remote.registerAs(old.deviceId, shared)
            keep(shared.copy(registered = true, registrations = 1))
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            assertFinished()
        }

    @Test fun `a photo waiting for its delete keeps its id, so the delete still finds it`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record)
            val waiting = record.copy(registered = true, registrations = 1)
            keep(waiting)
            library.startDelete(record.id, UNDO_MILLIS)
            assertEquals(server, library.undo.current()?.server)
            val session = claimer.claim(link.copy(server = server), "Phone")
            `when`(sessions.currentSession()).thenReturn(session)
            val kept = captures.get(record.id)
            assertEquals("original", kept?.let { captures.originalFile(it).readText() })
            uploadAll(session)
            // Undo runs out, which deletes the photo.
            advanceTimeBy(UNDO_MILLIS * 2)
            runCurrent()
            assertEquals(emptyMap(), captures.records.value)
            assertEquals(setOf(record.id), remote.photos.keys)
            assertEquals(waiting, kept)
        }

    @Test fun `a photo deleted after the server removed this phone doesn't upload again after pairing again`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record)
            keep(record.copy(registered = true, registrations = 1))
            library.startDelete(record.id, UNDO_MILLIS)
            // The server removes this phone before Undo runs out, so the delete has no pairing to go with.
            `when`(sessions.currentSession()).thenReturn(null)
            advanceTimeBy(UNDO_MILLIS * 2)
            runCurrent()
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            assertEquals(emptyMap(), captures.records.value)
            assertEquals(setOf(record.id), remote.photos.keys)
        }

    @Test fun `a photo deleted on the server while its original was queued isn't registered again`() =
        runTest {
            val claimer = claimer()
            // Another device deleted it, so the server has nothing under its id any more.
            keep(record.copy(registered = true, previewSent = true, registrations = 1))
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            assertEquals(emptyMap(), remote.photos)
            assertFalse(UploadStep.Register to record.id in remote.requests)
            assertTrue(
                captures.records.value.values
                    .all { it.deletedOnServer },
            )
        }

    @Test fun `a note edited on the server stays after pairing again`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record.copy(note = "server caption"))
            remote.photos[record.id] = remote.photos.getValue(record.id).copy(preview = "preview")
            val sent =
                record.copy(
                    note = "old caption",
                    serverNote = "old caption",
                    registered = true,
                    previewSent = true,
                    registrations = 1,
                )
            keep(sent)
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            assertFinished(note = "server caption")
        }

    @Test fun `a photo whose delete was undone after pairing again finishes uploading`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record)
            keep(record.copy(registered = true, registrations = 1))
            library.startDelete(record.id, UNDO_MILLIS)
            val session = claimer.claim(link.copy(server = server), "Phone")
            `when`(sessions.currentSession()).thenReturn(session)
            library.undo.undo(record.id)
            uploadAll(session)
            advanceTimeBy(UNDO_MILLIS * 2)
            runCurrent()
            assertFinished()
        }

    @Test fun `a photo the server already has a preview of keeps that one`() =
        runTest {
            val claimer = claimer()
            remote.registerAs(old.deviceId, record)
            remote.photos[record.id] = remote.photos.getValue(record.id).copy(preview = "old preview")
            // The old device's answer to the preview was lost, so the phone sends it again.
            keep(record.copy(registered = true, registrations = 1))
            uploadAll(claimer.claim(link.copy(server = server), "Phone"))
            val stored = remote.photos.getValue(record.id)
            assertEquals("old preview", stored.preview)
            assertEquals(old.deviceId, stored.device)
            assertEquals("original", stored.original)
            assertTrue(
                captures.records.value.values
                    .single()
                    .isDone,
            )
        }

    @Test fun `switching clears the next photo note`() =
        runTest {
            claimer().claim(link, "Phone")
            verify(settings).clearNotes()
        }

    @Test fun `switching clears photos and waiters before the new server lists anything`() =
        runTest {
            val claimer = claimer()
            feed.start()
            runCurrent()
            assertEquals(setOf(TEST_PHOTO_ID), feed.photos.value.photos.keys)
            assertEquals(2, feed.waiting.value)
            claimer.claim(link, "Phone")
            feed.upsert(old, testPhoto())
            assertTrue(
                feed.photos.value.photos
                    .isEmpty(),
            )
            assertEquals(0, feed.waiting.value)
        }

    @Test fun `unpair and switch cleanup removes shared and downloaded copies`() =
        runTest {
            claimer()
            val shared = File(root, "shared").apply { mkdirs() }
            File(shared, "shared.jpg").writeText("copy")
            File(shared, "download.jpg").writeText("original")
            library.changePairing(null)
            assertEquals(setOf("shared.jpg", "download.jpg"), revoked.map { it.name }.toSet())
            assertFalse(shared.exists())
        }

    @Test fun `clearing waits for a save that is reading the original`() =
        runTest {
            val reading = CountDownLatch(1)
            val release = CountDownLatch(1)
            var saved: String? = null
            gallery =
                mock(Gallery::class.java) { save ->
                    reading.countDown()
                    release.await()
                    saved = (save.arguments[0] as File).takeIf { it.isFile }?.readText()
                    null
                }
            claimer()
            keep(record)
            val photo = library.fileFor(GalleryItem.merge(record.id, record, null, null, null, null)!!)
            val saving = launch(Dispatchers.IO) { library.saveToGallery(record.id, photo) }
            withContext(Dispatchers.IO) { reading.await() }
            val clearing = launch { library.changePairing(null) }
            // Gives clearing the time to delete the capture if it doesn't wait for the save.
            withContext(Dispatchers.IO) { withTimeoutOrNull(500.milliseconds) { while (captures.records.value.isNotEmpty()) delay(10) } }
            release.countDown()
            saving.join()
            clearing.join()
            assertEquals("original", saved)
            assertNull(captures.get(record.id))
            assertFalse(captures.directory(record.id).exists())
        }
}

/**
 * Keeps photos the way the server does: any device may repeat a registration and send an image a photo is
 * missing, and only the device that registered the photo may replace an image it has.
 */
private class FakeServer(
    private val captures: CaptureStore,
) {
    data class Stored(
        val device: String,
        val metadata: PhotoMetadata,
        val note: String?,
        val preview: String? = null,
        val original: String? = null,
    )

    val photos = mutableMapOf<String, Stored>()
    val requests = mutableListOf<Pair<UploadStep, String>>()

    fun registerAs(
        device: String,
        record: CaptureRecord,
    ) {
        photos[record.id] = Stored(device, record.metadata.copy(note = null), record.note)
    }

    private fun stored(id: String) = photos[id] ?: throw ApiException(404, ApiErrorTag.NotFound, "Photo $id doesn't exist.")

    fun photo(id: String): Photo {
        val photo = stored(id)
        return testPhoto(id = id, note = photo.note, readyAt = photo.preview?.let { TEST_TIME }).copy(hasOriginal = photo.original != null)
    }

    fun asDevice(device: String) =
        object : UploadTarget {
            override suspend fun register(record: CaptureRecord): Photo {
                requests += UploadStep.Register to record.id
                val existing = photos[record.id]
                if (existing == null) {
                    registerAs(device, record)
                } else if (existing.metadata != record.metadata.copy(note = null)) {
                    throw ApiException(409, ApiErrorTag.Conflict, "Photo ${record.id} already exists with other details.")
                }
                return photo(record.id)
            }

            // Like the server, anyone paired may change a note.
            override suspend fun setNote(record: CaptureRecord): Photo {
                requests += UploadStep.Note to record.id
                photos[record.id] = stored(record.id).copy(note = record.note)
                return photo(record.id)
            }

            override suspend fun putPreview(
                record: CaptureRecord,
                onProgress: (Long, Long) -> Unit,
            ): Photo =
                send(UploadStep.Preview, record, { it.preview != null }) { it.copy(preview = bytes(captures.previewFile(record.id))) }

            override suspend fun putOriginal(
                record: CaptureRecord,
                onProgress: (Long, Long) -> Unit,
            ): Photo =
                send(UploadStep.Original, record, { it.original != null }) { it.copy(original = bytes(captures.originalFile(record))) }

            override suspend fun exists(record: CaptureRecord): Boolean = record.id in photos

            private fun send(
                step: UploadStep,
                record: CaptureRecord,
                has: (Stored) -> Boolean,
                change: (Stored) -> Stored,
            ): Photo {
                requests += step to record.id
                val existing = stored(record.id)
                if (existing.device == device || !has(existing)) photos[record.id] = change(existing)
                return photo(record.id)
            }

            private fun bytes(file: File) = file.takeIf { it.isFile }?.readText() ?: throw MissingFileException(file)
        }
}
