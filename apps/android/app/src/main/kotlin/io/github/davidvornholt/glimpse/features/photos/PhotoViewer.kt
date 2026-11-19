package io.github.davidvornholt.glimpse.features.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Transition
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import io.github.davidvornholt.glimpse.shared.photos.UploadState
import io.github.davidvornholt.glimpse.shared.ui.LocalSharedTransitionScope
import io.github.davidvornholt.glimpse.shared.ui.LocalViewerSettled
import io.github.davidvornholt.glimpse.shared.ui.NoteSheet
import io.github.davidvornholt.glimpse.shared.ui.PhotoImage
import io.github.davidvornholt.glimpse.shared.ui.SeenBadge
import io.github.davidvornholt.glimpse.shared.ui.backGesture
import io.github.davidvornholt.glimpse.shared.ui.dayLabel
import io.github.davidvornholt.glimpse.shared.ui.formatShortDate
import io.github.davidvornholt.glimpse.shared.ui.formatTime
import io.github.davidvornholt.glimpse.shared.ui.localDate
import io.github.davidvornholt.glimpse.shared.ui.photoDescription
import io.github.davidvornholt.glimpse.shared.ui.rememberPredictiveBack
import io.github.davidvornholt.glimpse.shared.ui.sharedPhotoKey
import io.github.davidvornholt.glimpse.shared.ui.uploadStatus

/**
 * A photo full screen, grown out of the thumbnail that opened it. Swipe
 * for the next one; tap to hide the controls. [shown] runs it open and
 * closed.
 */
@Composable
fun PhotoViewer(
    shown: Transition<Boolean>,
    items: List<GalleryItem>,
    origin: String,
    currentId: String?,
    onPageChange: (String) -> Unit,
    onClose: () -> Unit,
    actions: PhotoActions,
    modifier: Modifier = Modifier,
) {
    shown.AnimatedVisibility(
        visible = { it },
        modifier = modifier,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
    ) {
        ViewerContent(
            visibility = this,
            items = items,
            origin = origin,
            currentId = currentId.orEmpty(),
            open = shown.targetState,
            onPageChange = onPageChange,
            onClose = onClose,
            actions = actions,
        )
    }
}

@Composable
private fun ViewerContent(
    visibility: AnimatedVisibilityScope,
    items: List<GalleryItem>,
    origin: String,
    currentId: String,
    open: Boolean,
    onPageChange: (String) -> Unit,
    onClose: () -> Unit,
    actions: PhotoActions,
) {
    val context = LocalContext.current
    var chrome by remember { mutableStateOf(true) }
    var zoomedId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<GalleryItem?>(null) }
    val back by rememberPredictiveBack(enabled = open && editing == null, onBack = onClose)
    val pager =
        rememberPagerState(initialPage = items.indexOfFirst { it.id == currentId }.coerceAtLeast(0)) { items.size }
    val pageChange by rememberUpdatedState(onPageChange)
    LaunchedEffect(pager, items) {
        snapshotFlow { pager.currentPage }.collect { page -> items.getOrNull(page)?.id?.let(pageChange) }
    }
    // The photo that fills most of the screen is the one that grows and shrinks with the thumbnail,
    // even when the viewer closes before a swipe has come to rest. It changes only while the viewer
    // is fully open: a swipe that settles after the viewer began to close, or the very frame it
    // does, would otherwise hand the flight to a photo with nowhere to start from.
    val settled = LocalViewerSettled.current
    val flight = remember { Flight(currentId) }
    val flyingId = if (settled) currentId else flight.id
    SideEffect { flight.id = flyingId }
    LaunchedEffect(items.isEmpty()) { if (items.isEmpty()) onClose() }
    // The controls follow the page that fills most of the screen, so they never act on a photo that
    // is sliding away.
    val current = items.getOrNull(pager.currentPage) ?: items.firstOrNull { it.id == currentId }

    Box(
        Modifier
            .fillMaxSize()
            .backGesture({ back })
            .background(Color.Black.copy(alpha = 1f - back * BACK_FADE)),
    ) {
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            userScrollEnabled = zoomedId == null || zoomedId != current?.id,
            beyondViewportPageCount = 1,
            pageSpacing = 16.dp,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            ZoomableImage(
                onTap = { chrome = !chrome },
                onZoomedChange = { zoomed ->
                    if (zoomed) {
                        zoomedId = item.id
                    } else if (zoomedId == item.id) {
                        zoomedId = null
                    }
                },
            ) {
                ViewerImage(item, origin, shared = item.id == flyingId, visibility = visibility)
            }
        }

        // The photo flies above everything else on its way in and out, so the controls wait for it to
        // land and leave as it lifts off. Shown during the flight, they vanished under the photo and
        // popped back when it landed.
        val controls = chrome && settled
        if (current != null) {
            AnimatedVisibility(
                controls,
                Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 },
            ) {
                ViewerTopBar(current, onClose)
            }
            AnimatedVisibility(
                controls,
                Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                ViewerBottom(
                    item = current,
                    onNote = { editing = current },
                    onSave = { actions.save(current) },
                    onShare = { actions.share(current, context) },
                    onDelete = { actions.delete(current) },
                    onRetry = { actions.retry(current) },
                )
            }
        }
    }

    editing?.let { item ->
        NoteSheet(
            title = stringResource(R.string.viewer_note_title),
            initial = item.note,
            onDismiss = { editing = null },
            onSave = { note ->
                editing = null
                actions.setNote(item, note)
            },
        )
    }
}

@Composable
private fun ViewerImage(
    item: GalleryItem,
    origin: String,
    shared: Boolean,
    visibility: AnimatedVisibilityScope,
) {
    val transition = LocalSharedTransitionScope.current
    val sharedModifier =
        if (transition != null && shared) {
            with(transition) {
                Modifier.sharedElement(rememberSharedContentState(sharedPhotoKey(origin, item.id)), visibility)
            }
        } else {
            Modifier
        }
    val ratio = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 1f
    PhotoImage(
        model = item.thumbnail,
        contentDescription = photoDescription(item),
        modifier = Modifier.aspectRatio(ratio).then(sharedModifier),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun ViewerTopBar(
    item: GalleryItem,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.viewer_close), tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.viewer_taken, dayLabel(item.takenAt.localDate()), formatTime(item.takenAt)),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            val expiry = item.expiresAt?.let { stringResource(R.string.inbox_kept_until, formatShortDate(it)) }
            Text(
                listOfNotNull(uploadStatus(item), expiry).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = SECONDARY_ALPHA),
            )
        }
        SeenBadge(item.isViewed, Modifier.padding(end = 12.dp))
    }
}

@Composable
private fun ViewerBottom(
    item: GalleryItem,
    onNote: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (item.note != null) {
            Surface(
                onClick = onNote,
                shape = MaterialTheme.shapes.large,
                color = Color.Black.copy(alpha = SCRIM_ALPHA),
                contentColor = Color.White,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            ) {
                Text(
                    item.note,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = NOTE_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        HorizontalFloatingToolbar(expanded = true, colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()) {
            ToolbarButton(
                Icons.Rounded.EditNote,
                stringResource(if (item.note == null) R.string.viewer_add_note else R.string.viewer_edit_note),
                onNote,
            )
            if (item.upload is UploadState.Failed) {
                ToolbarButton(Icons.Rounded.Refresh, stringResource(R.string.viewer_retry), onRetry)
            }
            ToolbarButton(Icons.Rounded.Download, stringResource(R.string.viewer_save), onSave)
            ToolbarButton(Icons.Rounded.Share, stringResource(R.string.viewer_share), onShare)
            ToolbarButton(Icons.Rounded.Delete, stringResource(R.string.viewer_delete), onDelete)
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description)
    }
}

/** The photo that flies, kept across compositions without asking for another. */
private class Flight(var id: String)

private const val SCRIM_ALPHA = 0.5f
private const val SECONDARY_ALPHA = 0.75f
private const val BACK_FADE = 0.4f
private const val NOTE_LINES = 4

