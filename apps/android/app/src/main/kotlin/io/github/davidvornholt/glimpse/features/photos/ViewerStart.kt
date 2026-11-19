package io.github.davidvornholt.glimpse.features.photos

/** Null waits for the first list. A negative index means the saved photo is gone. */
internal fun viewerStart(
    index: Int,
    loaded: Boolean,
): Int? = index.takeIf { it >= 0 || loaded }
