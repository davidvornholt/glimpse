package io.github.davidvornholt.glimpse.features.photos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewerStartTest {
    @Test
    fun `waits for the saved id through an empty or local-only list`() {
        assertNull(viewerStart(-1, false))
        assertEquals(2, viewerStart(2, false))
        assertEquals(3, viewerStart(3, true))
        assertEquals(-1, viewerStart(-1, true))
    }
}
