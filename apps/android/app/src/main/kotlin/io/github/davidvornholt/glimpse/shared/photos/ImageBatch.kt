package io.github.davidvornholt.glimpse.shared.photos

import kotlinx.serialization.Serializable

/** The ids chosen for a single press of Send, in selection order. */
@Serializable
data class ImageBatch(
    val id: String?,
    val photoIds: List<String>,
) {
    suspend fun import(
        alreadyAdded: suspend (String) -> Boolean,
        stillPaired: suspend () -> Boolean,
        add: suspend (index: Int, id: String, first: Boolean) -> Boolean,
    ): ImportResult {
        val committed = photoIds.filter { alreadyAdded(it) }.toSet()
        var added = committed.size
        for ((index, id) in photoIds.withIndex()) {
            if (id in committed) continue
            if (!stillPaired()) break
            if (add(index, id, added == 0)) added++
        }
        return ImportResult(added, photoIds.size)
    }
}
