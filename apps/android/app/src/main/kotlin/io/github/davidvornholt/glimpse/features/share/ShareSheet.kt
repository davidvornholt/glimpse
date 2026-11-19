package io.github.davidvornholt.glimpse.features.share

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.haptics.Haptics
import io.github.davidvornholt.glimpse.shared.photos.ImportResult
import io.github.davidvornholt.glimpse.shared.photos.PhotoLibrary
import io.github.davidvornholt.glimpse.shared.ui.NoteField
import io.github.davidvornholt.glimpse.shared.ui.PhotoImage
import kotlinx.coroutines.delay

/**
 * Sends images shared from another app, with an optional note. The images
 * lose their location and other metadata before they leave the phone.
 */
@Composable
internal fun ShareSheet(
    images: List<Uri>,
    server: String?,
    library: PhotoLibrary,
    haptics: Haptics,
    onOpenApp: () -> Unit,
    onFinish: () -> Unit,
) {
    val view = LocalView.current
    val model: ShareImport = viewModel()
    val stage = model.stage
    val sheetState =
        rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)) { value ->
            value != SheetValue.Hidden || model.stage != Stage.Sending
        }
    val note = rememberTextFieldState()
    val finish by rememberUpdatedState(onFinish)
    LaunchedEffect(model) { model.resume(images, library) }
    LaunchedEffect(stage) {
        when (stage) {
            Stage.Sent -> {
                haptics.success(view)
                delay(SENT_MILLIS)
                sheetState.hide()
                finish()
            }

            Stage.Partial, Stage.Failed -> {
                sheetState.show()
                haptics.reject(view)
            }

            Stage.Sending, Stage.Editing -> {}
        }
    }
    ModalBottomSheet(onDismissRequest = { if (model.stage != Stage.Sending) onFinish() }, sheetState = sheetState) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (server == null && stage == Stage.Editing) {
                NotPaired(onOpenApp, onFinish)
                return@Column
            }
            Text(
                pluralStringResource(R.plurals.share_title, images.size, images.size),
                style = MaterialTheme.typography.titleLarge,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                items(images) { uri ->
                    PhotoImage(uri, contentDescription = null, modifier = Modifier.size(88.dp).clip(MaterialTheme.shapes.medium))
                }
            }
            AnimatedContent(
                targetState = stage,
                transitionSpec = { (fadeIn() + scaleIn(spring(Spring.DampingRatioMediumBouncy))).togetherWith(fadeOut()) },
                label = "share",
            ) { shown ->
                when (shown) {
                    Stage.Editing, Stage.Failed -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            NoteField(note, label = stringResource(R.string.share_note_label))
                            if (shown == Stage.Failed) {
                                Text(
                                    pluralStringResource(R.plurals.share_failed, images.size),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                TextButton(onClick = onFinish) { Text(stringResource(R.string.action_cancel)) }
                                Button(enabled = server != null, onClick = {
                                    server?.let {
                                        model.send(
                                            images,
                                            note.text
                                                .toString()
                                                .trim()
                                                .ifEmpty { null },
                                            it,
                                            library,
                                        )
                                    }
                                }) { Text(stringResource(R.string.share_send)) }
                            }
                        }
                    }

                    Stage.Sending -> {
                        Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                            LoadingIndicator(Modifier.size(64.dp))
                        }
                    }

                    Stage.Sent -> {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 120.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        ) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(12.dp).size(32.dp),
                                )
                            }
                            Text(stringResource(R.string.share_sent), style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    Stage.Partial -> {
                        Partial(model.result, onFinish)
                    }
                }
            }
        }
    }
}

@Composable
private fun Partial(
    result: ImportResult,
    onFinish: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.share_partial_title, result.total, result.added, result.total),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            pluralStringResource(R.plurals.share_partial_body, result.failed, result.failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onFinish) { Text(stringResource(R.string.action_close)) }
        }
    }
}

@Composable
private fun NotPaired(
    onOpenApp: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.share_not_paired_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.share_not_paired_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onFinish) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onOpenApp) { Text(stringResource(R.string.share_open_app)) }
        }
    }
}

private const val SENT_MILLIS = 900L
