package io.github.davidvornholt.glimpse.shared.photos

import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.api.OriginalType
import java.io.File
import java.io.IOException

internal data class PhotoExport(
    val file: File,
    val type: OriginalType,
    val isPreview: Boolean = false,
) {
    fun displayName(id: String): String {
        val suffix = if (isPreview) "-preview" else ""
        return "glimpse-$id$suffix.${type.extension}"
    }
}

internal suspend fun resolveExport(
    localPreview: File,
    downloadOriginal: (suspend () -> PhotoExport)?,
    downloadPreview: suspend () -> File,
): PhotoExport {
    if (downloadOriginal != null) {
        try {
            return downloadOriginal()
        } catch (error: ApiException) {
            if (error.tag != ApiErrorTag.NotFound && !localPreview.isFile) throw error
        } catch (error: IOException) {
            if (!localPreview.isFile) throw error
        }
    }
    val file = if (localPreview.isFile) localPreview else downloadPreview()
    return PhotoExport(file, OriginalType.Jpeg, isPreview = true)
}
