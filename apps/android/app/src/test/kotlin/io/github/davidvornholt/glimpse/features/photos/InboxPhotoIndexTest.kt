package io.github.davidvornholt.glimpse.features.photos

import io.github.davidvornholt.glimpse.shared.api.testPhoto
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class InboxPhotoIndexTest {
    private fun photo(id: String) = checkNotNull(GalleryItem.merge(id, null, testPhoto(id), null, null, null))

    @Test
    fun `finds the return row across day headers and ignores a removed photo`() {
        val today = LocalDate.of(2026, 11, 18)
        val days = listOf(today to listOf(photo("a"), photo("b")), today.minusDays(1) to listOf(photo("c")))
        assertEquals(1, inboxPhotoIndex(days, "a"))
        assertEquals(4, inboxPhotoIndex(days, "c"))
        assertEquals(-1, inboxPhotoIndex(days, "gone"))
    }
}
