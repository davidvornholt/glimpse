package io.github.davidvornholt.glimpse.shared.photos

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.api.TEST_PHOTO_ID
import io.github.davidvornholt.glimpse.shared.api.TEST_TIME
import io.github.davidvornholt.glimpse.shared.image.Gallery
import io.github.davidvornholt.glimpse.shared.image.ImageProcessor
import io.github.davidvornholt.glimpse.shared.image.MetadataStripper
import io.github.davidvornholt.glimpse.shared.settings.SessionState
import io.github.davidvornholt.glimpse.shared.settings.SessionStore
import io.github.davidvornholt.glimpse.shared.settings.SettingsStore
import io.github.davidvornholt.glimpse.shared.ulid.UlidGenerator
import io.github.davidvornholt.glimpse.shared.upload.CaptureRecord
import io.github.davidvornholt.glimpse.shared.upload.CaptureStore
import io.github.davidvornholt.glimpse.shared.upload.DeleteScheduler
import io.github.davidvornholt.glimpse.shared.upload.UploadProgress
import io.github.davidvornholt.glimpse.shared.upload.UploadScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The camera journal after the process dies, finished by the real image code. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Without the app manifest Robolectric would pick its oldest SDK, which has no ColorSpace.
@Config(sdk = [Config.NEWEST_SDK])
class CameraRecoveryTest {
    private val root = Files.createTempDirectory("recovery").toFile()
    private val capturesDir = File(root, "captures")
    private val record = CaptureRecord(TEST_PHOTO_ID, TEST_TIME, PhotoSource.Camera, 4032, 3024, OriginalType.Jpeg)
    private val queued = Collections.synchronizedList(mutableListOf<String>())
    private val recovered = CompletableDeferred<Unit>()
    private val uploads =
        mock(UploadScheduler::class.java) { call ->
            when (call.method.name) {
                "getProgress" -> {
                    MutableStateFlow(emptyMap<String, UploadProgress>())
                }

                "enqueue" -> {
                    queued.add(call.arguments[0] as String)
                    null
                }

                else -> {
                    null
                }
            }
        }
    private val deletes =
        mock(DeleteScheduler::class.java) { call -> if (call.method.name == "getPending") flowOf(emptySet<String>()) else null }
    private val sessions =
        mock(SessionStore::class.java) { call ->
            when (call.method.name) {
                "getState" -> {
                    MutableStateFlow<SessionState>(SessionState.Unpaired())
                }

                // Startup asks for the session once the camera recovery is done.
                "currentSession" -> {
                    recovered.complete(Unit)
                    null
                }

                else -> {
                    null
                }
            }
        }

    /** A photo as the camera writes it. */
    private val camera =
        ByteArrayOutputStream()
            .also { out ->
                val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
                for (x in 0 until 64) for (y in 0 until 48) image.setRGB(x, y, (x * 4 shl 16) or (y * 5 shl 8) or 0x80)
                ImageIO.write(image, "jpg", out)
            }.toByteArray()

    private val stripped = MetadataStripper.strip(camera, OriginalType.Jpeg)

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    /** Starts the app again over the files the previous process left, and waits for its camera recovery. */
    private suspend fun TestScope.restart(): CaptureStore {
        val captures = CaptureStore(capturesDir)
        PhotoLibrary(
            captures,
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(root, "undo.preferences_pb") },
            uploads,
            deletes,
            PhotoFeed(sessions, { error("Recovery needs no server") }, backgroundScope),
            sessions,
            SettingsStore(
                PreferenceDataStoreFactory.create(scope = backgroundScope) { File(root, "settings.preferences_pb") },
                backgroundScope,
            ),
            ImageProcessor(RuntimeEnvironment.getApplication().contentResolver),
            mock(Gallery::class.java),
            UlidGenerator(),
            { error("Recovery needs no server") },
            root,
            {},
            backgroundScope,
        )
        withContext(Dispatchers.IO) { withTimeout(10.seconds) { recovered.await() } }
        return captures
    }

    /** One capture went to the upload queue, with the camera's photo stripped and a preview of it. */
    private suspend fun assertQueuedOnce(captures: CaptureStore) {
        assertEquals(listOf(record.id), queued)
        val finished = record.copy(width = 64, height = 48)
        assertEquals(mapOf(record.id to finished), captures.records.value)
        val original = captures.originalFile(record)
        assertContentEquals(stripped, original.readBytes())
        assertNotNull(ImageIO.read(original), "The original doesn't decode")
        val preview = assertNotNull(ImageIO.read(captures.previewFile(record.id)), "The preview doesn't decode")
        assertEquals(64 to 48, preview.width to preview.height)
        val again = CaptureStore(capturesDir)
        assertTrue(again.interruptedCameras().isEmpty())
        assertEquals(finished, again.get(record.id))
    }

    @Test fun `a photo CameraX saved but hadn't renamed yet is queued after a restart`() =
        runTest {
            val dir = CaptureStore(capturesDir).also { it.beginCamera(record) }.directory(record.id)
            // CameraX writes to a file of its own beside the one it was asked for, and renames it once done.
            val unrenamed = File(dir, "CameraX3f2a9c1e-7b4d-4e8a-9c2f-5d6e7f8a9b0c.jpg").apply { writeBytes(camera) }
            assertQueuedOnce(restart())
            assertFalse(unrenamed.exists())
        }

    @Test fun `a photo CameraX renamed is queued after a restart`() =
        runTest {
            val output = CaptureStore(capturesDir).beginCamera(record).apply { writeBytes(camera) }
            assertQueuedOnce(restart())
            assertFalse(output.exists())
        }

    @Test fun `a photo already stripped before the restart is queued without changing it`() =
        runTest {
            val before = CaptureStore(capturesDir)
            before.beginCamera(record)
            // The process died after the original was saved and the camera's file deleted, before the record.
            before.originalFile(record).writeBytes(stripped)
            // Only a later process recovers it, never the one whose camera may still be writing.
            assertTrue(before.interruptedCameras().isEmpty())
            assertQueuedOnce(restart())
        }

    @Test fun `a journal whose photo never arrived is dropped after a restart`() =
        runTest {
            val dir = CaptureStore(capturesDir).also { it.beginCamera(record) }.directory(record.id)
            val captures = restart()
            assertEquals(emptyList(), queued)
            assertEquals(emptyMap(), captures.records.value)
            assertFalse(dir.exists())
        }

    @Test fun `a photo recovery can't place stays on the phone after a restart`() =
        runTest {
            val dir = CaptureStore(capturesDir).also { it.beginCamera(record) }.directory(record.id)
            val first = File(dir, "CameraX3f2a9c1e-7b4d-4e8a-9c2f-5d6e7f8a9b0c.jpg").apply { writeBytes(camera) }
            val second = File(dir, "CameraX8d1e4b7a-2c5f-4a9e-b3d6-1f0e9c8b7a65.jpg").apply { writeBytes(camera) }
            val captures = restart()
            assertEquals(emptyList(), queued)
            assertEquals(emptyMap(), captures.records.value)
            assertTrue(first.isFile && second.isFile)
            assertEquals(listOf(record), CaptureStore(capturesDir).interruptedCameras())
        }
}
