package io.github.davidvornholt.glimpse.shared.image

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Saves copies to the phone's photo library, in Pictures/Glimpse. No permission is needed for that. */
class Gallery(
    private val resolver: ContentResolver,
) {
    suspend fun save(
        file: File,
        mimeType: String,
        displayName: String,
    ) = withContext(Dispatchers.IO) {
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Glimpse")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("The photo library refused the copy")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("The photo library can't be written")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (error: IOException) {
            resolver.delete(uri, null, null)
            throw error
        }
        uri
    }
}
