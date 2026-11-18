package io.github.davidvornholt.glimpse.features.capture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.roundToInt

/** A photo just taken, on its way from the viewfinder into the filmstrip. */
data class Flight(
    val id: String,
    val frame: ImageBitmap,
    val from: Rect,
    val to: Rect,
)

/**
 * Flashes the viewfinder, shrinks the frame a little as if it were pressed
 * out of the screen, then flies it into the filmstrip's first slot.
 */
@Composable
fun CaptureFlight(
    flight: Flight,
    onLanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val landed by rememberUpdatedState(onLanded)
    val bounds = remember(flight.id) { Animatable(flight.from, Rect.VectorConverter) }
    val corner = remember(flight.id) { Animatable(0f) }
    val flash = remember(flight.id) { Animatable(FLASH_ALPHA) }
    val press = MaterialTheme.motionScheme.fastSpatialSpec<Rect>()
    val fly = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val round = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val cornerTarget = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(flight.id) {
        coroutineScope {
            async { flash.animateTo(0f, tween(FLASH_MILLIS)) }
            bounds.animateTo(flight.from.shrink(PRESS), press)
            async { corner.animateTo(cornerTarget, round) }
            bounds.animateTo(flight.to, fly)
        }
        landed()
    }
    Canvas(modifier.fillMaxSize()) {
        drawRect(Color.White.copy(alpha = flash.value))
        val rect = bounds.value
        val path =
            Path().apply {
                addRoundRect(RoundRect(rect, CornerRadius(corner.value)))
            }
        clipPath(path) {
            val (srcOffset, srcSize) = centerCrop(flight.frame, rect.size)
            drawImage(
                image = flight.frame,
                srcOffset = srcOffset,
                srcSize = srcSize,
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
            )
        }
    }
}

private fun Rect.shrink(fraction: Float): Rect {
    val dx = width * fraction / 2
    val dy = height * fraction / 2
    return Rect(left + dx, top + dy, right - dx, bottom - dy)
}

/** The part of [image] that fills [target] without stretching. */
private fun centerCrop(
    image: ImageBitmap,
    target: Size,
): Pair<IntOffset, IntSize> {
    if (target.width <= 0f || target.height <= 0f) return IntOffset.Zero to IntSize(image.width, image.height)
    val scale = maxOf(target.width / image.width, target.height / image.height)
    val width = (target.width / scale).roundToInt().coerceIn(1, image.width)
    val height = (target.height / scale).roundToInt().coerceIn(1, image.height)
    val center = Offset(image.width / 2f, image.height / 2f)
    return IntOffset((center.x - width / 2f).roundToInt(), (center.y - height / 2f).roundToInt()) to IntSize(width, height)
}

private const val FLASH_ALPHA = 0.7f
private const val FLASH_MILLIS = 220
private const val PRESS = 0.06f
