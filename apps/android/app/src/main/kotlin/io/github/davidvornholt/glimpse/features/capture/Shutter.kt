package io.github.davidvornholt.glimpse.features.capture

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.settings.CaptureMode

/**
 * The shutter: a white ring around a shape that squeezes into a cookie while
 * pressed. In scan mode the circle becomes a page-like rounded square.
 */
@Composable
fun Shutter(
    mode: CaptureMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = MaterialTheme.motionScheme
    val press by animateFloatAsState(if (pressed) 1f else 0f, motion.fastSpatialSpec(), label = "press")
    val scan by animateFloatAsState(if (mode == CaptureMode.Scan) 1f else 0f, motion.defaultSpatialSpec(), label = "mode")
    val photoMorph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val scanMorph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie4Sided) }
    val path = remember { Path() }
    val scanColor = MaterialTheme.colorScheme.primary
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val label = stringResource(if (mode == CaptureMode.Photo) R.string.capture_take_photo else R.string.capture_scan)
    Canvas(
        modifier
            .size(SHUTTER_SIZE)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        val ring = RING_WIDTH.toPx()
        drawCircle(Color.White.copy(alpha = alpha), radius = size.minDimension / 2 - ring / 2, style = Stroke(ring))
        val inner = size.minDimension - 2 * (ring + RING_GAP.toPx())
        rotate(degrees = PRESS_TURN * press) {
            val photoSide = inner * (1f - PRESS_SHRINK * press)
            drawMorph(photoMorph, press, photoSide, Color.White.copy(alpha = alpha * (1f - scan)), path)
            val scanSide = photoSide * SCAN_SCALE
            drawMorph(scanMorph, press, scanSide, scanColor.copy(alpha = alpha * scan), path)
        }
    }
}

private fun DrawScope.drawMorph(
    morph: Morph,
    progress: Float,
    side: Float,
    color: Color,
    path: Path,
) {
    if (color.alpha <= 0f) return
    path.rewind()
    morph.toPath(progress, path)
    val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2)
    translate(topLeft.x, topLeft.y) {
        scale(side, side, pivot = Offset.Zero) { drawPath(path, color) }
    }
}

private val SHUTTER_SIZE = 80.dp
private val RING_WIDTH = 4.dp
private val RING_GAP = 5.dp
private const val PRESS_SHRINK = 0.12f
private const val PRESS_TURN = 40f
private const val SCAN_SCALE = 0.82f
private const val DISABLED_ALPHA = 0.4f
