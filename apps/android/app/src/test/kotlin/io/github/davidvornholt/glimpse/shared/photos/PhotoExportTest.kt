package io.github.davidvornholt.glimpse.shared.photos

import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.api.OriginalType
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PhotoExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val offline: suspend () -> PhotoExport = { throw IOException("Offline") }

    @Test
    fun `offers the local JPEG as a preview when the original is unreachable`() =
        runTest {
            val preview = folder.newFile()
            val result = resolveExport(preview, offline) { error("Tried to download the local preview") }
            assertEquals(PhotoExport(preview, OriginalType.Jpeg, isPreview = true), result)
        }

    @Test
    fun `keeps the download failure if there is no local copy`() =
        runTest {
            assertFailsWith<IOException> {
                resolveExport(File(folder.root, "missing"), offline) { error("Retried while offline") }
            }
        }

    @Test
    fun `prefers the original and identifies a downloaded preview when the original is missing`() =
        runTest {
            val file = folder.newFile()
            val original = PhotoExport(file, OriginalType.Png)
            assertEquals(original, resolveExport(file, { original }) { error("Downloaded a preview") })
            val unavailable = { throw ApiException(404, ApiErrorTag.NotFound, "Missing") }
            val result = resolveExport(File(folder.root, "missing"), unavailable) { file }
            assertEquals(PhotoExport(file, OriginalType.Jpeg, isPreview = true), result)
        }
}
