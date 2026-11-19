package io.github.davidvornholt.glimpse.shared.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/** Shows a photo from whatever the app has of it: a file, a URL, or the frame grabbed at capture. */
@Composable
fun PhotoImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHighest
    when (model) {
        is Bitmap -> {
            val image = remember(model) { model.asImageBitmap() }
            Image(bitmap = image, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
        }

        null -> {
            Box(modifier.background(placeholder))
        }

        else -> {
            val painter = remember(placeholder) { ColorPainter(placeholder) }
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = modifier,
                placeholder = painter,
                error = painter,
                contentScale = contentScale,
            )
        }
    }
}
