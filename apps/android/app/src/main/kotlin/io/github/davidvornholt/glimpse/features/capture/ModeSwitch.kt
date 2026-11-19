package io.github.davidvornholt.glimpse.features.capture

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.haptics.Haptics
import io.github.davidvornholt.glimpse.shared.settings.CaptureMode
import kotlin.math.roundToInt

/** Photo or Scan, with a pill that springs to the chosen side. */
@Composable
fun ModeSwitch(
    mode: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val position by animateFloatAsState(mode.ordinal.toFloat(), MaterialTheme.motionScheme.fastSpatialSpec(), label = "pill")
    Box(
        modifier
            .height(SEGMENT_HEIGHT)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = TRACK_ALPHA))
            .selectableGroup(),
    ) {
        Box(
            Modifier
                .offset { IntOffset((position * SEGMENT_WIDTH.toPx()).roundToInt(), 0) }
                .width(SEGMENT_WIDTH)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Row {
            CaptureMode.entries.forEach { option ->
                val selected = option == mode
                val color by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                    MaterialTheme.motionScheme.fastEffectsSpec(),
                    label = "label",
                )
                Box(
                    Modifier
                        .width(SEGMENT_WIDTH)
                        .fillMaxHeight()
                        .selectable(selected = selected, role = Role.Tab) {
                            if (!selected) {
                                haptics.tick(view)
                                onSelect(option)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(if (option == CaptureMode.Photo) R.string.capture_mode_photo else R.string.capture_mode_scan),
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                    )
                }
            }
        }
    }
}

private val SEGMENT_WIDTH = 96.dp
private val SEGMENT_HEIGHT = 48.dp
private const val TRACK_ALPHA = 0.45f
