package io.github.davidvornholt.glimpse.shared.photos

/** How many images in the selection now have a committed capture record. */
data class ImportResult(
    val added: Int,
    val total: Int,
) {
    val failed: Int get() = total - added

    val isComplete: Boolean get() = total > 0 && added == total

    val isPartial: Boolean get() = added in 1 until total
}
