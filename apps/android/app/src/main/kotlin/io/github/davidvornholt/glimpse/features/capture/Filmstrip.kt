package io.github.davidvornholt.glimpse.features.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import io.github.davidvornholt.glimpse.shared.ui.PhotoThumbnail
import io.github.davidvornholt.glimpse.shared.ui.SharedPhotoSource
import io.github.davidvornholt.glimpse.shared.ui.scrollToPhoto
import io.github.davidvornholt.glimpse.shared.ui.sharedPhotoKey

/** The origin of shared-element keys for photos opened from the filmstrip. */
const val FILMSTRIP = "filmstrip"

/** Today's photos, newest first. */
@Composable
fun Filmstrip(
    items: List<GalleryItem>,
    state: LazyListState,
    hiddenKey: String?,
    viewerPhotoId: String?,
    flyingId: String?,
    onOpen: (GalleryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(viewerPhotoId, items) {
        if (viewerPhotoId != null) state.scrollToPhoto(items.indexOfFirst { it.id == viewerPhotoId })
    }
    LazyRow(
        state = state,
        modifier = modifier.fillMaxWidth().height(STRIP_HEIGHT),
        contentPadding = PaddingValues(horizontal = STRIP_PADDING),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(items, key = { it.id }) { item ->
            val key = sharedPhotoKey(FILMSTRIP, item.id)
            SharedPhotoSource(
                key = key,
                hiddenKey = hiddenKey,
                modifier =
                    Modifier
                        .animateItem()
                        .size(ITEM_SIZE)
                        .alpha(if (item.id == flyingId) 0f else 1f)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onOpen(item) },
            ) {
                PhotoThumbnail(item, Modifier.matchParentSize())
            }
        }
    }
}

/** The new photo lands in the slot at the start of [strip]. */
fun firstSlotIn(
    strip: Rect,
    density: Float,
    direction: LayoutDirection,
): Rect {
    val size = ITEM_SIZE.value * density
    val centerY = strip.center.y
    val padding = STRIP_PADDING.value * density
    val start = if (direction == LayoutDirection.Ltr) strip.left + padding else strip.right - padding - size
    return Rect(start, centerY - size / 2, start + size, centerY + size / 2)
}

private val ITEM_SIZE = 56.dp
private val STRIP_HEIGHT = 64.dp
private val STRIP_PADDING = 16.dp
