package io.github.davidvornholt.glimpse.shared.ui

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** The scope that lets a thumbnail grow into the viewer and back. */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The key that ties a thumbnail in one place to the viewer, such as "filmstrip-<id>". */
fun sharedPhotoKey(
    origin: String,
    id: String,
): String = "$origin-$id"

/**
 * A thumbnail the viewer can grow out of. It hides while the viewer shows
 * the same photo, so only one copy is ever on screen.
 */
@Composable
fun SharedPhotoSource(
    key: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = LocalSharedTransitionScope.current
    val shared =
        if (scope == null) {
            Modifier
        } else {
            with(scope) {
                Modifier.sharedElementWithCallerManagedVisibility(rememberSharedContentState(key), visible)
            }
        }
    Box(modifier.then(shared), content = content)
}
