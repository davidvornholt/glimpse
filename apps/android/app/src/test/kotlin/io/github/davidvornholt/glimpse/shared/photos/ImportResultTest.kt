package io.github.davidvornholt.glimpse.shared.photos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportResultTest {
    @Test
    fun `tells a partial import from a complete or failed one`() {
        val partial = ImportResult(added = 1, total = 2)
        assertTrue(partial.isPartial)
        assertFalse(partial.isComplete)
        assertEquals(1, partial.failed)

        assertTrue(ImportResult(added = 2, total = 2).isComplete)
        assertFalse(ImportResult(added = 2, total = 2).isPartial)
        assertFalse(ImportResult(added = 0, total = 2).isPartial)
        assertFalse(ImportResult(added = 0, total = 2).isComplete)
        assertFalse(ImportResult(added = 0, total = 0).isComplete)
    }
}
