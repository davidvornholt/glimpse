package io.github.davidvornholt.glimpse.features.photos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

/**
 * A photo to pinch, pan and double-tap into. While it isn't zoomed in,
 * horizontal swipes go to the pager around it.
 */
@Composable
fun ZoomableImage(
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tap by rememberUpdatedState(onTap)
    val zoomedChange by rememberUpdatedState(onZoomedChange)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val offsetSpring = MaterialTheme.motionScheme.defaultSpatialSpec<Offset>()

    fun clamp(
        value: Offset,
        zoom: Float,
    ): Offset {
        val maxX = size.width * (zoom - 1) / 2
        val maxY = size.height * (zoom - 1) / 2
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    val zoomed = scale.value > 1.01f
    LaunchedEffect(zoomed) { zoomedChange(zoomed) }
    val state =
        rememberTransformableState { centroid, zoomChange, panChange, _ ->
            val next = (scale.value * zoomChange).coerceIn(1f, MAX_ZOOM)
            // Keep the point between the fingers where it is while the zoom changes.
            val applied = next / scale.value
            val fromCenter = centroid - Offset(size.width / 2f, size.height / 2f)
            val moved = offset.value * applied + fromCenter * (1 - applied) + panChange
            scope.launch {
                scale.snapTo(next)
                offset.snapTo(clamp(moved, next))
            }
        }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { at ->
                        scope.launch {
                            if (scale.value > 1.01f) {
                                launch { offset.animateTo(Offset.Zero, offsetSpring) }
                                scale.animateTo(1f, spring)
                            } else {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                launch { offset.animateTo(clamp((center - at) * (DOUBLE_TAP_ZOOM - 1), DOUBLE_TAP_ZOOM), offsetSpring) }
                                scale.animateTo(DOUBLE_TAP_ZOOM, spring)
                            }
                        }
                    },
                )
            }.transformable(state, canPan = { scale.value > 1.01f })
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offset.value.x
                translationY = offset.value.y
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f
