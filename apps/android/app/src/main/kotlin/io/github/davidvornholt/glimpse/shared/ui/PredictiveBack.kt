package io.github.davidvornholt.glimpse.shared.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Handles the back gesture for a panel: the progress follows the finger
 * from 0 to 1, springs back when the gesture is cancelled, and [onBack]
 * runs when it completes.
 */
@Composable
fun rememberPredictiveBack(
    enabled: Boolean,
    onBack: () -> Unit,
): State<Float> {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    LaunchedEffect(enabled) { if (enabled) progress.snapTo(0f) }
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { progress.snapTo(it.progress) }
            currentOnBack()
        } catch (cancelled: CancellationException) {
            scope.launch { progress.animateTo(0f) }
            throw cancelled
        }
    }
    return progress.asState()
}

/** Shrinks and rounds a panel as the back gesture pulls it away. */
fun Modifier.backGesture(
    progress: () -> Float,
    cornerRadius: Dp = 32.dp,
): Modifier =
    graphicsLayer {
        val fraction = progress()
        val scale = 1f - BACK_SHRINK * fraction
        scaleX = scale
        scaleY = scale
        if (fraction > 0f) {
            shape = RoundedCornerShape(cornerRadius * fraction)
            clip = true
        }
    }

private const val BACK_SHRINK = 0.1f
