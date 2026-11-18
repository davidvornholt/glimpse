package io.github.davidvornholt.glimpse.features.capture

import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The camera preview, edge to edge. CameraX handles pinch to zoom and tap to
 * focus itself; this draws the focus ring and turns a horizontal swipe into
 * a change of mode.
 */
@Composable
fun Viewfinder(
    controller: LifecycleCameraController,
    active: Boolean,
    onPreviewView: (PreviewView) -> Unit,
    onSwipe: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // The camera stops while something covers the viewfinder, which saves battery.
    DisposableEffect(controller, lifecycleOwner, active) {
        if (active) controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    var tap by remember { mutableStateOf<FocusTap?>(null) }
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    Box(
        modifier.pointerInput(Unit) {
            // Watches touches on their way to the preview without taking them.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var last = down.position
                var multiTouch = false
                var released = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) multiTouch = true
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    last = change.position
                    if (!change.pressed) {
                        released = true
                        break
                    }
                }
                if (multiTouch || !released) return@awaitEachGesture
                val dx = last.x - down.position.x
                val dy = last.y - down.position.y
                val slop = viewConfiguration.touchSlop
                when {
                    abs(dx) > SWIPE_DISTANCE.toPx() && abs(dx) > 2 * abs(dy) -> currentOnSwipe(dx < 0)
                    abs(dx) < slop && abs(dy) < slop -> tap = FocusTap(down.position, down.uptimeMillis)
                }
            }
        },
    ) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                    onPreviewView(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        tap?.let { FocusRing(it) }
    }
}

private data class FocusTap(
    val position: Offset,
    val time: Long,
)

@Composable
private fun FocusRing(tap: FocusTap) {
    val scale = remember(tap) { Animatable(RING_START_SCALE) }
    val alpha = remember(tap) { Animatable(1f) }
    LaunchedEffect(tap) {
        launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }
        delay(RING_HOLD_MILLIS)
        alpha.animateTo(0f, tween(RING_FADE_MILLIS))
    }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            color = Color.White.copy(alpha = alpha.value),
            radius = RING_RADIUS.toPx() * scale.value,
            center = tap.position,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private val SWIPE_DISTANCE = 72.dp
private val RING_RADIUS = 36.dp
private const val RING_START_SCALE = 1.4f
private const val RING_HOLD_MILLIS = 700L
private const val RING_FADE_MILLIS = 250
