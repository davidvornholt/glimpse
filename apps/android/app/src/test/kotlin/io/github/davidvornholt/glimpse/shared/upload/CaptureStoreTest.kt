package io.github.davidvornholt.glimpse.shared.upload

import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.api.TEST_PHOTO_ID
import io.github.davidvornholt.glimpse.shared.api.TEST_TIME
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureStoreTest {
    private val root = Files.createTempDirectory("captures").toFile()
    private val record = CaptureRecord(TEST_PHOTO_ID, TEST_TIME, PhotoSource.Camera, 4032, 3024, OriginalType.Jpeg)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `keeps the first photo taken before the store loaded`() =
        runTest {
            val store = CaptureStore(root)

            store.prepare(record.id)
            store.originalFile(record).writeText("jpeg")
            store.add(record)

            assertEquals(record, store.get(record.id))
            assertTrue(store.originalFile(record).isFile)
        }

    @Test
    fun `keeps the only original when a legacy capture has no record`() =
        runTest {
            val unfinished = File(root, "01JD0000000000000000000000").apply { mkdirs() }
            File(unfinished, "original.jpg").writeText("jpeg")

            CaptureStore(root).load()

            assertTrue(File(unfinished, "original.jpg").isFile)
        }

    @Test
    fun `keeps a photo whose record it can't read`() =
        runTest {
            val directory = File(root, record.id).apply { mkdirs() }
            File(directory, "meta.json").writeText("{ not a record")
            val original = File(directory, "original.jpg").apply { writeText("jpeg") }
            val store = CaptureStore(root)

            store.load()

            assertTrue(store.records.value.isEmpty())
            assertTrue(original.isFile)
        }
}
