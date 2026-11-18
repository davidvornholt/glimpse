package io.github.davidvornholt.glimpse.features.capture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.davidvornholt.glimpse.R

private val chipEnter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) + fadeIn()
private val chipExit = scaleOut() + fadeOut()

/** Shows that an agent runs `glimpse wait` and is looking out for a photo. */
@Composable
fun WaitingChip(
    count: Int,
    modifier: Modifier = Modifier,
) {
    // Keeps the last count while the chip animates out at zero.
    val last = remember { intArrayOf(maxOf(count, 1)) }
    if (count > 0) last[0] = count
    val shown = last[0]
    AnimatedVisibility(count > 0, modifier, enter = chipEnter, exit = chipExit) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulsingDot(MaterialTheme.colorScheme.primary)
                Text(pluralStringResource(R.plurals.agents_waiting, shown, shown), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Shows that the server can't be reached; photos wait on the phone meanwhile. */
@Composable
fun OfflineChip(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible, modifier, enter = chipEnter, exit = chipExit) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.6f),
            contentColor = Color.White,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.status_offline), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
        label = "dot",
    )
    Canvas(Modifier.size(10.dp)) {
        drawCircle(color.copy(alpha = pulse), radius = size.minDimension / 2 * pulse)
    }
}

private const val PULSE_MILLIS = 900
