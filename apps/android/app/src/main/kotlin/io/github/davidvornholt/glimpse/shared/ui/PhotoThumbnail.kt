package io.github.davidvornholt.glimpse.shared.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import io.github.davidvornholt.glimpse.shared.photos.UploadState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A photo's thumbnail with where its upload stands: a ring while it uploads
 * that completes into a check, and an eye once an agent has seen it.
 */
@Composable
fun PhotoThumbnail(
    item: GalleryItem,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    val description = photoDescription(item)
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        PhotoImage(item.thumbnail, contentDescription = null, modifier = Modifier.matchParentSize().clip(shape))
        UploadOverlay(item.upload, shape, Modifier.matchParentSize())
        SeenBadge(seen = item.isViewed, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp))
    }
}

@Composable
private fun UploadOverlay(
    state: UploadState,
    shape: Shape,
    modifier: Modifier,
) {
    val uploaded = state == UploadState.Uploaded
    // The check plays only when an upload finishes on screen, not for every old photo.
    var wasPending by remember { mutableStateOf(!uploaded) }
    var showCheck by remember { mutableStateOf(false) }
    LaunchedEffect(uploaded) {
        if (uploaded && wasPending) {
            showCheck = true
            delay(CHECK_MILLIS)
            showCheck = false
        }
        wasPending = !uploaded
    }
    val scrim by animateFloatAsState(if (uploaded && !showCheck) 0f else SCRIM_ALPHA, label = "scrim")
    val fraction by animateFloatAsState(
        targetValue =
            when (state) {
                is UploadState.Uploading -> state.fraction
                UploadState.Uploaded -> 1f
                else -> 0f
            },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "progress",
    )
    Box(modifier.clip(shape).background(Color.Black.copy(alpha = scrim)), contentAlignment = Alignment.Center) {
        val ringModifier = Modifier.size(RING_SIZE)
        when (state) {
            UploadState.Preparing -> {
                CircularWavyProgressIndicator(
                    modifier = ringModifier,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = TRACK_ALPHA),
                )
            }

            UploadState.Queued -> {
                Icon(Icons.Rounded.CloudUpload, contentDescription = null, tint = Color.White, modifier = Modifier.size(ICON_SIZE))
            }

            is UploadState.Uploading -> {
                CircularWavyProgressIndicator(
                    progress = { fraction },
                    modifier = ringModifier,
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = TRACK_ALPHA),
                )
            }

            is UploadState.Failed -> {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                    Icon(
                        Icons.Rounded.PriorityHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(4.dp).size(ICON_SIZE),
                    )
                }
            }

            UploadState.Uploaded -> {}
        }
        AnimatedVisibility(
            visible = showCheck,
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(4.dp).size(ICON_SIZE),
                )
            }
        }
    }
}

/** An eye that springs in the moment an agent first looks at the photo. */
@Composable
fun SeenBadge(
    seen: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = remember { MutableTransitionState(seen) }
    state.targetState = seen
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = scaleIn(spring(dampingRatio = SEEN_BOUNCE, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = scaleOut() + fadeOut(),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, shadowElevation = 2.dp) {
            Icon(
                Icons.Rounded.Visibility,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(3.dp).size(14.dp),
            )
        }
    }
}

/** What TalkBack reads for a photo: its kind, time and where it stands. */
@Composable
fun photoDescription(item: GalleryItem): String {
    val kind =
        stringResource(
            when (item.source) {
                PhotoSource.Camera -> R.string.photo_kind_photo
                PhotoSource.Scan -> R.string.photo_kind_scan
                PhotoSource.Share -> R.string.photo_kind_shared
            },
        )
    return stringResource(R.string.photo_description, kind, formatTime(item.takenAt), uploadStatus(item))
}

/** Where a photo stands, in a few words. */
@Composable
fun uploadStatus(item: GalleryItem): String =
    when (val upload = item.upload) {
        UploadState.Preparing -> {
            stringResource(R.string.upload_preparing)
        }

        UploadState.Queued -> {
            stringResource(R.string.upload_queued)
        }

        is UploadState.Uploading -> {
            stringResource(R.string.upload_uploading, (upload.fraction * PERCENT).roundToInt())
        }

        is UploadState.Failed -> {
            stringResource(R.string.upload_failed)
        }

        UploadState.Uploaded -> {
            item.viewedAt?.let { stringResource(R.string.photo_seen_at, formatTime(it)) }
                ?: stringResource(R.string.photo_not_seen)
        }
    }

private const val CHECK_MILLIS = 1100L
private const val SCRIM_ALPHA = 0.45f
private const val TRACK_ALPHA = 0.3f
private const val SEEN_BOUNCE = 0.4f
private const val PERCENT = 100
private val RING_SIZE = 28.dp
private val ICON_SIZE = 16.dp
