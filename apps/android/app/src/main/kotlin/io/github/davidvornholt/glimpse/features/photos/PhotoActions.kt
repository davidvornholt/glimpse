package io.github.davidvornholt.glimpse.features.photos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.content.FileProvider
import io.github.davidvornholt.glimpse.R
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.photos.GalleryItem
import io.github.davidvornholt.glimpse.shared.photos.PhotoExport
import io.github.davidvornholt.glimpse.shared.photos.PhotoLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
            val photo = exportFor(item, R.string.viewer_save_preview) ?: return@launch
            val result =
                try {
                    library.saveToGallery(item.id, photo)
                    R.string.viewer_saved
                } catch (_: IOException) {
                    R.string.viewer_save_failed
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
            val photo = exportFor(item, R.string.viewer_share_preview) ?: return@launch
            try {
                library.shareFile(item.id, photo) { shared ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType(photo.type.mimeType)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri(null, uri)
                    item.note?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
                    from.startActivity(Intent.createChooser(send, null))
                }
            } catch (_: IOException) {
                snackbar.showSnackbar(message(R.string.viewer_share_failed))
            }
        }
    }

    private suspend fun exportFor(
        item: GalleryItem,
        previewAction: Int,
    ): PhotoExport? {
        val photo =
            try {
                library.fileFor(item)
            } catch (_: IOException) {
                null
            } catch (_: ApiException) {
                null
            }
        if (photo == null) {
            snackbar.showSnackbar(message(R.string.viewer_download_failed))
            return null
        }
        if (!photo.isPreview) return photo
        val answer =
            snackbar.showSnackbar(
                message = message(R.string.viewer_original_unavailable),
                actionLabel = message(previewAction),
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite,
            )
        return photo.takeIf { answer == SnackbarResult.ActionPerformed }
    }

    /** Hides the photo at once and deletes it unless the user undoes it in time. */
    fun delete(item: GalleryItem) {
        scope.launch { library.startDelete(item.id, undoMillis) }
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
}
