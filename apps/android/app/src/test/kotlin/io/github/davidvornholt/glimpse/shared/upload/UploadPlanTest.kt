package io.github.davidvornholt.glimpse.shared.upload

import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.api.TEST_PHOTO_ID
import io.github.davidvornholt.glimpse.shared.api.TEST_TIME
import io.github.davidvornholt.glimpse.shared.api.testPhoto
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UploadPlanTest {
    private val fresh = CaptureRecord(TEST_PHOTO_ID, TEST_TIME, PhotoSource.Camera, 4032, 3024, OriginalType.Jpeg)

    @Test
    fun `an original upload answer cannot turn an agent note into a local removal`() {
        val uploading = fresh.copy(registered = true, previewSent = true)
        val answer = UploadPlan.succeeded(uploading, UploadStep.Original, testPhoto(note = "From the agent"))
        assertNull(UploadPlan.nextStep(answer))
    }

    @Test
    fun `explicitly clearing a remote note sends a patch even when the cached note was null`() {
        val uploaded = fresh.copy(registered = true, previewSent = true, originalSent = true)
        assertEquals(UploadStep.Note, UploadPlan.nextStep(uploaded.withNote(null)))
    }

    @Test
    fun `an acknowledgement in flight leaves a newer edit pending`() {
        val first = fresh.copy(registered = true).withNote("First")
        val next = first.withNote(null)
        val answer = UploadPlan.succeeded(next, UploadStep.Note, testPhoto(note = "First"), first)
        assertEquals(UploadStep.Note, UploadPlan.nextStep(answer))
        assertNull(answer.note)
        assertEquals(UploadStep.Preview, UploadPlan.nextStep(UploadPlan.succeeded(answer, UploadStep.Note, testPhoto(note = null))))
    }

    private fun failed(
        step: UploadStep,
        error: Throwable,
        record: CaptureRecord = fresh.copy(registered = true, registrations = 1),
    ) = UploadPlan.failed(record, step, error)

    private fun api(
        status: Int,
        tag: ApiErrorTag,
    ) = ApiException(status, tag, "No")

    @Test
    fun `registers, then sends the note, the preview and the original`() {
        assertEquals(UploadStep.Register, UploadPlan.nextStep(fresh))
        assertEquals(UploadStep.Note, UploadPlan.nextStep(fresh.copy(registered = true).withNote("Left one")))
        assertEquals(UploadStep.Preview, UploadPlan.nextStep(fresh.copy(registered = true)))
        assertEquals(UploadStep.Original, UploadPlan.nextStep(fresh.copy(registered = true, previewSent = true)))
        assertNull(UploadPlan.nextStep(fresh.copy(registered = true, previewSent = true, originalSent = true)))
        assertNull(UploadPlan.nextStep(fresh.copy(registered = true, previewSent = true, originalSkipped = true)))
        assertNull(UploadPlan.nextStep(fresh.copy(failure = UploadFailure.Rejected)))
    }

    @Test
    fun `remembers what the server answered`() {
        val registered = UploadPlan.succeeded(fresh.copy(note = "Left one"), UploadStep.Register, testPhoto(note = "Left one"))

        assertEquals(true, registered.registered)
        assertEquals("Left one", registered.serverNote)
        assertEquals(1, registered.registrations)
        assertEquals(true, UploadPlan.succeeded(registered, UploadStep.Preview, testPhoto()).previewSent)
        assertNull(UploadPlan.succeeded(registered, UploadStep.Preview, testPhoto()).serverNote)
        assertEquals(true, UploadPlan.succeeded(registered, UploadStep.Original, testPhoto()).originalSent)
    }

    @Test
    fun `waits when the network or the server is down`() {
        assertEquals(UploadPlan.Decision.RetryLater, failed(UploadStep.Preview, IOException("Connection reset")))
        assertEquals(UploadPlan.Decision.RetryLater, failed(UploadStep.Preview, api(503, ApiErrorTag.ServerError)))
        assertEquals(UploadPlan.Decision.RetryLater, failed(UploadStep.Register, api(429, ApiErrorTag.Unknown)))
        assertEquals(UploadPlan.Decision.RetryLater, failed(UploadStep.Original, api(408, ApiErrorTag.Unknown)))
    }

    @Test
    fun `stops for the whole phone when its token is gone`() {
        assertEquals(UploadPlan.Decision.Unpaired, failed(UploadStep.Original, api(401, ApiErrorTag.Unauthorized)))
    }

    @Test
    fun `fails for good when the server refuses the photo`() {
        assertEquals(UploadPlan.Decision.Fail(UploadFailure.Conflict), failed(UploadStep.Register, api(409, ApiErrorTag.Conflict)))
        assertEquals(UploadPlan.Decision.Fail(UploadFailure.Rejected), failed(UploadStep.Preview, api(400, ApiErrorTag.InvalidRequest)))
        assertEquals(UploadPlan.Decision.Fail(UploadFailure.Rejected), failed(UploadStep.Original, api(403, ApiErrorTag.Forbidden)))
        assertEquals(UploadPlan.Decision.Fail(UploadFailure.Rejected), failed(UploadStep.Preview, api(413, ApiErrorTag.TooLarge)))
        assertEquals(UploadPlan.Decision.Fail(UploadFailure.Rejected), failed(UploadStep.Note, IllegalStateException("Bug")))
    }

    @Test
    fun `registers again when the server lost the photo, but only once`() {
        val decision = assertIs<UploadPlan.Decision.Update>(failed(UploadStep.Preview, api(404, ApiErrorTag.NotFound)))
        val reset = decision.change(fresh.copy(registered = true, note = "Left one", serverNote = "Left one"))

        assertEquals(UploadStep.Register, UploadPlan.nextStep(reset))
        assertEquals(
            UploadStep.Register,
            UploadPlan.nextStep(
                assertIs<UploadPlan.Decision.Update>(failed(UploadStep.Note, api(404, ApiErrorTag.NotFound))).change(reset),
            ),
        )
        assertEquals(
            UploadPlan.Decision.Fail(UploadFailure.Rejected),
            failed(UploadStep.Preview, api(404, ApiErrorTag.NotFound), fresh.copy(registered = true, registrations = 2)),
        )
        assertEquals(UploadPlan.Decision.Fail(UploadFailure.Rejected), failed(UploadStep.Register, api(404, ApiErrorTag.NotFound)))
    }

    @Test
    fun `asks whether a photo an agent could see is really gone`() {
        val ready = fresh.copy(registered = true, registrations = 1, previewSent = true)

        for ((step, record) in listOf(UploadStep.Original to ready, UploadStep.Note to ready.copy(note = "Later"))) {
            assertEquals(UploadPlan.Decision.ConfirmDeleted, failed(step, api(404, ApiErrorTag.NotFound), record))
        }
        assertNull(UploadPlan.nextStep(ready.copy(deletedOnServer = true)))
        assertEquals(true, ready.copy(deletedOnServer = true).isDone)
    }

    @Test
    fun `lets the preview stand in for an original the server or the phone can't keep`() {
        val tooLarge = assertIs<UploadPlan.Decision.Update>(failed(UploadStep.Original, api(413, ApiErrorTag.TooLarge)))
        val missing = assertIs<UploadPlan.Decision.Update>(failed(UploadStep.Original, MissingFileException(File("original.jpg"))))

        assertEquals(true, tooLarge.change(fresh).originalSkipped)
        assertEquals(true, missing.change(fresh).originalSkipped)
        assertEquals(
            UploadPlan.Decision.Fail(UploadFailure.Rejected),
            failed(UploadStep.Preview, MissingFileException(File("preview.jpg"))),
        )
    }
}
