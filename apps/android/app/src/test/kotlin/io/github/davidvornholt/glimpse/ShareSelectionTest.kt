package io.github.davidvornholt.glimpse

import kotlin.test.Test
import kotlin.test.assertEquals

class ShareSelectionTest {
    @Test
    fun `all distinct content images reach the sheet and the importer`() {
        val images = (1..23).toList()
        assertEquals(images, sharedSelection(emptyList(), images + images + 0) { it > 0 })
    }
}
