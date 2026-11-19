package io.github.davidvornholt.glimpse.features.capture

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class FilmstripTest {
    @Test
    fun `the first slot follows start padding in both layout directions`() {
        val strip = Rect(20f, 100f, 620f, 228f)
        assertEquals(Rect(52f, 108f, 164f, 220f), firstSlotIn(strip, 2f, LayoutDirection.Ltr))
        assertEquals(Rect(476f, 108f, 588f, 220f), firstSlotIn(strip, 2f, LayoutDirection.Rtl))
    }
}
