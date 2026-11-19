package io.github.davidvornholt.glimpse.shared.photos

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageBatchTest {
    private val batch = ImageBatch("batch", listOf("one", "two", "three"))

    @Test
    fun `switching pairings during import stops the remaining images`() =
        runTest {
            val added = mutableListOf<String>()
            val result = batch.import({ false }, { added.isEmpty() }) { _, id, _ -> added.add(id) }
            assertEquals(listOf("one"), added)
            assertEquals(ImportResult(1, 3), result)
        }

    @Test
    fun `resuming a partial batch skips committed ids and leaves the note on its first success`() =
        runTest {
            val added = mutableListOf<String>()
            val result =
                batch.import({ it == "two" }, { true }) { _, id, first ->
                    assertEquals(false, first)
                    added.add(id)
                }
            assertEquals(listOf("one", "three"), added)
            assertEquals(ImportResult(3, 3), result)
        }
}
