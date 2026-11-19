package io.github.davidvornholt.glimpse.shared.api

/** The error tags the server answers with. */
enum class ApiErrorTag {
    InvalidRequest,
    Unauthorized,
    Forbidden,
    NotFound,
    Conflict,
    TooLarge,
    RateLimited,
    ServerError,

    /** A tag this version of the app doesn't know, or a body that isn't an error. */
    Unknown,
    ;

    companion object {
        fun of(tag: String?): ApiErrorTag = entries.firstOrNull { it.name == tag && it != Unknown } ?: Unknown
    }
}

/**
 * The server answered, but not with success. Network failures stay
 * [java.io.IOException]s, so callers can tell a server that said no from
 * one they couldn't reach.
 */
class ApiException(
    val status: Int,
    val tag: ApiErrorTag,
    message: String,
) : Exception(message) {
    /** Trying again later may work. */
    val isTransient: Boolean get() = status >= 500 || status == 429 || status == 408
}
