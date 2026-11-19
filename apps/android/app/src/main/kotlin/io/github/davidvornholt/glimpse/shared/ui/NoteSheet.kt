package io.github.davidvornholt.glimpse.shared.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.davidvornholt.glimpse.R
import kotlinx.coroutines.launch

/** The longest note the server takes. */
const val MAX_NOTE_LENGTH = 2000

private const val COUNTER_FROM = MAX_NOTE_LENGTH - 200

/** A note typed or dictated, which the agent reads with the photo. */
@Composable
fun NoteField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
) {
    val context = LocalContext.current
    val prompt = stringResource(R.string.note_dictation_prompt)
    val dictation =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) {
                val current = state.text.toString().trimEnd()
                val joined = if (current.isEmpty()) spoken else "$current $spoken"
                state.setTextAndPlaceCursorAtEnd(joined.take(MAX_NOTE_LENGTH))
            }
        }
    val speech =
        remember(prompt) {
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
    val canDictate = remember(speech) { speech.resolveActivity(context.packageManager) != null }
    val focusRequester = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        state = state,
        modifier = modifier.fillMaxWidth().focusRequester(focusRequester),
        label = { Text(label) },
        inputTransformation = InputTransformation.maxLength(MAX_NOTE_LENGTH),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        trailingIcon =
            if (canDictate) {
                {
                    IconButton(onClick = {
                        try {
                            dictation.launch(speech)
                        } catch (_: ActivityNotFoundException) {
                            // The speech service went away since the check; typing still works.
                        }
                    }) {
                        Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.note_dictate))
                    }
                }
            } else {
                null
            },
        supportingText = {
            if (state.text.length >= COUNTER_FROM) {
                Text(stringResource(R.string.note_counter, state.text.length, MAX_NOTE_LENGTH))
            }
        },
    )
}

/**
 * A sheet for writing or changing a note. [header] goes between the title
 * and the field, for choosing which photo the note is for; it gets the
 * field's text so it can swap in that photo's note.
 */
@Composable
fun NoteSheet(
    title: String,
    initial: String?,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
    header: (@Composable (TextFieldState) -> Unit)? = null,
) {
    val sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    val text = rememberTextFieldState(initial.orEmpty())
    val close: (() -> Unit) -> Unit = { then ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            header?.invoke(text)
            NoteField(text, label = stringResource(R.string.note_label), autoFocus = true)
            val trimmed = text.text.trim().toString()
            val removes = trimmed.isEmpty() && !initial.isNullOrBlank()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { close(onDismiss) }) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { close { onSave(trimmed.ifEmpty { null }) } },
                    enabled = trimmed.isNotEmpty() || removes,
                ) {
                    Text(stringResource(if (removes) R.string.note_remove else R.string.note_save))
                }
            }
        }
    }
}
