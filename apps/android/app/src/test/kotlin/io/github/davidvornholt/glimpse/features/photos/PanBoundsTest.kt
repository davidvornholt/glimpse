package io.github.davidvornholt.glimpse.features.photos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class PanBoundsTest {
    private val viewport = IntSize(400, 600)
    private val far = Offset(2000f, -2000f)

    @Test
    fun `a panorama pans horizontally until its edge reaches the viewport`() {
        assertEquals(Offset(200f, 0f), clampPan(far, 2f, viewport, IntSize(400, 100)))
    }

    @Test
    fun `a tall photo pans vertically and only travels horizontally once wide enough`() {
        assertEquals(Offset(0f, -300f), clampPan(far, 2f, viewport, IntSize(100, 600)))
        assertEquals(Offset(50f, -1200f), clampPan(far, 5f, viewport, IntSize(100, 600)))
    }
}
