package io.github.davidvornholt.glimpse.shared.photos

/**
 * How many of the images given to [PhotoLibrary.addImages] it took in. The
 * ones it took in are queued, so sending the batch again would send them twice.
 */
data class ImportResult(
    val added: Int,
    val total: Int,
) {
    val failed: Int get() = total - added

    val isComplete: Boolean get() = total > 0 && added == total

    val isPartial: Boolean get() = added in 1 until total
}
