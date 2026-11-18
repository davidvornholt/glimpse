package io.github.davidvornholt.glimpse.shared.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whether [url] points at the paired [server], so a request to it may carry
 * the phone's token: the same scheme, host and port, and a path inside the
 * server's own.
 */
fun isOnServer(
    url: HttpUrl,
    server: String,
): Boolean {
    val base = server.toHttpUrlOrNull() ?: return false
    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return false
    val prefix = base.encodedPath.trimEnd('/')
    return prefix.isEmpty() || url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/")
}
