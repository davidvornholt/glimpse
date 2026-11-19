package io.github.davidvornholt.glimpse.features.photos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.core.content.FileProvider
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import io.github.davidvornholt.glimpse.shared.photos.PhotoLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * What the viewer and the inbox do with a photo. The work runs in [scope],
 * which outlives the screen, so a delete still happens after the viewer closes.
 */
class PhotoActions(
    private val context: Context,
    private val library: PhotoLibrary,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val undoMillis: Long,
) {
    private fun message(id: Int) = context.getString(id)

    fun save(item: GalleryItem) {
        scope.launch {
            val result =
                try {
                    library.saveToGallery(item)
                    R.string.viewer_saved
                } catch (_: IOException) {
                    R.string.viewer_save_failed
                } catch (_: ApiException) {
                    R.string.viewer_download_failed
                }
            snackbar.showSnackbar(message(result))
        }
    }

    /** Opens the share sheet with the photo and its note. */
    fun share(
        item: GalleryItem,
        from: Context,
    ) {
        scope.launch {
            try {
                val (file, type) = library.fileFor(item)
                val shared = withContext(Dispatchers.IO) { shareableCopy(file, "glimpse-${item.id}.${type.extension}") }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType(type.mimeType)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(null, uri)
                item.note?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
                from.startActivity(Intent.createChooser(send, null))
            } catch (_: IOException) {
                snackbar.showSnackbar(message(R.string.viewer_share_failed))
            } catch (_: ApiException) {
                snackbar.showSnackbar(message(R.string.viewer_download_failed))
            }
        }
    }

    /** Hides the photo at once and deletes it unless the user undoes it in time. */
    fun delete(item: GalleryItem) {
        scope.launch { library.undo.start(item.id, undoMillis) }
    }

    fun setNote(
        item: GalleryItem,
        note: String?,
    ) {
        scope.launch {
            try {
                library.setNote(item.id, note)
            } catch (_: ApiException) {
                snackbar.showSnackbar(message(R.string.note_save_failed))
            } catch (_: IOException) {
                snackbar.showSnackbar(message(R.string.note_save_failed))
            }
        }
    }

    fun retry(item: GalleryItem) {
        scope.launch { library.retry(item.id) }
    }

    /** Copies into the folder the file provider shares, so only that copy leaves the app. */
    private fun shareableCopy(
        file: File,
        name: String,
    ): File {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val target = File(directory, name)
        if (file.canonicalPath != target.canonicalPath) file.copyTo(target, overwrite = true)
        return target
    }
}
