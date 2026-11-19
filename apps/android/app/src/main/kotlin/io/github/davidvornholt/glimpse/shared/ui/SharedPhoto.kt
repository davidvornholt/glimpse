package io.github.davidvornholt.glimpse.shared.ui

import androidx.compose.animation.SharedTransitionDefaults
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.SharedContentConfig
import androidx.compose.animation.SharedTransitionScope.SharedContentState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

/** The scope that lets a thumbnail grow into the viewer and back. */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Whether the viewer is fully open: done growing out of a thumbnail and not
 * yet shrinking back into one.
 */
val LocalViewerSettled = compositionLocalOf { false }

/** The key that ties a thumbnail in one place to the viewer, such as "filmstrip-<id>". */
fun sharedPhotoKey(
    origin: String,
    id: String,
): String = "$origin-$id"

/**
 * A thumbnail the viewer can grow out of. It hides while the viewer shows
 * the same photo, so only one copy is ever on screen. [hiddenKey] is the
 * key of the photo the viewer shows, or null while the viewer is closed.
 */
@Composable
fun SharedPhotoSource(
    key: String,
    hiddenKey: String?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = LocalSharedTransitionScope.current
    // While the viewer is fully open, the thumbnails stay out of it. A swipe would otherwise pair the
    // next photo with its thumbnail, and Compose draws a new pair at the thumbnail's bounds for a
    // frame or two before it settles: the screen flickered after every swipe. They pair again when
    // the viewer closes, and the photo shrinks from wherever it is on screen.
    val settled = LocalViewerSettled.current
    val hidden = key == hiddenKey
    val shared =
        if (scope == null) {
            Modifier
        } else {
            with(scope) {
                Modifier.sharedElementWithCallerManagedVisibility(
                    rememberSharedContentState(key, if (settled) Unpaired else Paired),
                    visible = !hidden,
                )
            }
        }
    // Unpaired, the shared element no longer hides the photo the viewer shows, and the back gesture
    // fades the viewer enough to show it twice.
    val hide = if (hidden && settled) Modifier.alpha(0f) else Modifier
    Box(modifier.then(shared).then(hide), content = content)
}

private val Paired = SharedTransitionDefaults.SharedContentConfig

private object Unpaired : SharedContentConfig {
    override val SharedContentState.isEnabled: Boolean
        get() = false
}
