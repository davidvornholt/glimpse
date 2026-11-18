package io.github.davidvornholt.glimpse.features.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import io.github.davidvornholt.glimpse.shared.ui.NoteSheet
import java.time.Duration
import java.time.Instant

/** Which photo a note from the camera goes with. */
enum class NoteTarget { Next, Last }

/**
 * Writes a note for the next photo, which waits until the shutter, or
 * changes the last photo's note. Right after a photo, it starts on that one.
 */
@Composable
fun CaptureNoteSheet(
    pendingNote: String?,
    last: GalleryItem?,
    onDismiss: () -> Unit,
    onSave: (NoteTarget, String?) -> Unit,
) {
    var target by remember {
        val recent = last != null && Duration.between(last.takenAt, Instant.now()) < RECENT
        mutableStateOf(if (pendingNote == null && recent) NoteTarget.Last else NoteTarget.Next)
    }
    val noteFor: (NoteTarget) -> String? = { if (it == NoteTarget.Next) pendingNote else last?.note }
    NoteSheet(
        title = stringResource(R.string.note_title),
        initial = noteFor(target),
        onDismiss = onDismiss,
        onSave = { onSave(target, it) },
        header = { text ->
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                NoteTarget.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == target,
                        onClick = {
                            // Untouched text follows the choice; typed text stays.
                            if (text.text.toString() == noteFor(target).orEmpty()) {
                                text.setTextAndPlaceCursorAtEnd(noteFor(option).orEmpty())
                            }
                            target = option
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, NoteTarget.entries.size),
                        enabled = option == NoteTarget.Next || last != null,
                    ) {
                        Text(stringResource(if (option == NoteTarget.Next) R.string.note_for_next else R.string.note_for_last))
                    }
                }
            }
        },
    )
}

/** The note that goes with the next photo, above the filmstrip. */
@Composable
fun PendingNotePill(
    note: String,
    onEdit: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = PILL_ALPHA),
        contentColor = Color.White,
        modifier = modifier.widthIn(max = 360.dp),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(start = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f, fill = false).clickable(onClick = onEdit),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.EditNote, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    stringResource(R.string.capture_note_pending, note),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDiscard) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.capture_note_discard))
            }
        }
    }
}

private val RECENT = Duration.ofMinutes(2)
private const val PILL_ALPHA = 0.6f
