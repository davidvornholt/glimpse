package io.github.davidvornholt.glimpse.shared.photos

import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.api.TEST_PHOTO_ID
import io.github.davidvornholt.glimpse.shared.api.TEST_TIME
import io.github.davidvornholt.glimpse.shared.api.testPhoto
import io.github.davidvornholt.glimpse.shared.upload.CaptureRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
