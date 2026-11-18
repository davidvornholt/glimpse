package io.github.davidvornholt.glimpse.features.photos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import io.github.davidvornholt.glimpse.shared.ui.PhotoThumbnail
import io.github.davidvornholt.glimpse.shared.ui.SharedPhotoSource
import io.github.davidvornholt.glimpse.shared.ui.dayLabel
import io.github.davidvornholt.glimpse.shared.ui.formatShortDate
import io.github.davidvornholt.glimpse.shared.ui.formatTime
import io.github.davidvornholt.glimpse.shared.ui.localDate
import io.github.davidvornholt.glimpse.shared.ui.sharedPhotoKey
import io.github.davidvornholt.glimpse.shared.ui.uploadStatus

/** The origin of shared-element keys for photos opened from the inbox. */
const val INBOX = "inbox"

/** The height of the part of the sheet that peeks over the camera. */
val INBOX_PEEK_HEIGHT = 64.dp

/**
 * Everything sent in the last 30 days, newest first and grouped by day,
 * with each photo's note, whether an agent has seen it and when it goes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InboxSheet(
    items: List<GalleryItem>,
    offline: Boolean,
    expanded: Boolean,
    hiddenKey: String?,
    onToggle: () -> Unit,
    onOpen: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = remember(items) { items.groupBy { it.sentAt.localDate() }.toList() }
    Column(modifier.fillMaxSize()) {
        InboxHeader(count = items.size, expanded = expanded, onToggle = onToggle)
        if (offline) OfflineBanner()
        if (items.isEmpty()) {
            EmptyInbox(Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                days.forEach { (day, photos) ->
                    stickyHeader(key = "day-$day") {
                        Text(
                            dayLabel(day),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(horizontal = 24.dp, vertical = 12.dp)
                                    .semantics { heading() },
                        )
                    }
                    items(photos, key = { it.id }) { item ->
                        InboxRow(item, hiddenKey, onOpen, Modifier.animateItem())
                    }
                }
            }
        }
        Box(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun InboxHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val label = stringResource(if (expanded) R.string.inbox_collapse else R.string.inbox_expand)
    Column(
        Modifier
            .fillMaxWidth()
            .height(INBOX_PEEK_HEIGHT)
            .clickable(onClickLabel = label, onClick = onToggle),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(top = 10.dp)
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HANDLE_ALPHA)),
        )
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.inbox_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (count > 0) {
                Text(
                    pluralStringResource(R.plurals.inbox_count, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InboxRow(
    item: GalleryItem,
    hiddenKey: String?,
    onOpen: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = sharedPhotoKey(INBOX, item.id)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clickable { onOpen(item) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SharedPhotoSource(
            key = key,
            visible = key != hiddenKey,
            modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.medium),
        ) {
            PhotoThumbnail(item, Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (item.note != null) {
                Text(item.note, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(
                    stringResource(R.string.inbox_no_note),
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                listOfNotNull(formatTime(item.takenAt), sourceLabel(item.source), uploadStatus(item)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.expiresAt?.let {
                Text(
                    stringResource(R.string.inbox_kept_until, formatShortDate(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun sourceLabel(source: PhotoSource): String? =
    when (source) {
        PhotoSource.Camera -> null
        PhotoSource.Scan -> stringResource(R.string.photo_kind_scan)
        PhotoSource.Share -> stringResource(R.string.photo_kind_shared)
    }

@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null)
            Text(stringResource(R.string.inbox_offline), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyInbox(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Rounded.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(stringResource(R.string.inbox_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.inbox_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

private const val HANDLE_ALPHA = 0.4f
