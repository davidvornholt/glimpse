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

    /** Every image was taken in. */
    val isComplete: Boolean get() = total > 0 && added == total

    /** Some images were taken in and some weren't. */
    val isPartial: Boolean get() = added in 1 until total
}
