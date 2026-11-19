package io.github.davidvornholt.glimpse.shared.photos

import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.api.TEST_PHOTO_ID
import io.github.davidvornholt.glimpse.shared.api.TEST_TIME
import io.github.davidvornholt.glimpse.shared.api.testPhoto
import io.github.davidvornholt.glimpse.shared.ulid.UlidGenerator
import io.github.davidvornholt.glimpse.shared.upload.CaptureRecord
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GalleryItemTest {
    private val uploaded =
        CaptureRecord(
            id = TEST_PHOTO_ID,
            takenAt = TEST_TIME,
            source = PhotoSource.Camera,
            width = 4032,
            height = 3024,
            originalType = OriginalType.Jpeg,
            note = "Left one",
            registered = true,
            serverNote = "Left one",
            previewSent = true,
            originalSent = true,
        )
    private val sentAt = Instant.ofEpochMilli(UlidGenerator.timeOf(TEST_PHOTO_ID))
    private val listedSince = sentAt - Duration.ofDays(10)

    private fun noteOf(
        record: CaptureRecord,
        serverNote: String?,
    ) = GalleryItem.merge(TEST_PHOTO_ID, record, testPhoto(note = serverNote), null, null, null)?.note

    @Test
    fun `shows a note an agent changed or cleared`() {
        assertEquals("The right one", noteOf(uploaded, "The right one"))
        assertNull(noteOf(uploaded, null))
    }

    @Test
    fun `shows an edit the phone hasn't sent yet over the server's note`() {
        assertEquals("Both", noteOf(uploaded.copy(note = "Both"), "The right one"))
        assertNull(noteOf(uploaded.copy(note = null), "The right one"))
    }

    @Test
    fun `counts a photo missing from a complete list as deleted`() {
        assertTrue(GalleryItem.isDeletedOnServer(uploaded, null, listedSince))
        assertTrue(GalleryItem.isDeletedOnServer(uploaded.copy(originalSent = false), null, listedSince))
        assertFalse(GalleryItem.isDeletedOnServer(uploaded, testPhoto(), listedSince))
    }

    @Test
    fun `counts a photo the upload found deleted as deleted`() {
        assertTrue(GalleryItem.isDeletedOnServer(uploaded.copy(originalSent = false, deletedOnServer = true), testPhoto(), null))
    }

    @Test
    fun `doesn't take a missing photo for a deleted one when the list can't tell`() {
        // The stream is down, so the list may have missed the photo.
        assertFalse(GalleryItem.isDeletedOnServer(uploaded, null, null))
        // An agent can't see the photo yet, so the list doesn't have it.
        assertFalse(GalleryItem.isDeletedOnServer(uploaded.copy(previewSent = false, originalSent = false), null, listedSince))
        // The photo is older than the list, or so close to its start that the clocks may disagree.
        assertFalse(GalleryItem.isDeletedOnServer(uploaded, null, sentAt + Duration.ofDays(1)))
        assertFalse(GalleryItem.isDeletedOnServer(uploaded, null, sentAt - Duration.ofHours(12)))
    }
}
