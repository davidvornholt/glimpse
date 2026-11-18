package io.github.davidvornholt.glimpse.shared.pairing

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * What a pairing QR code holds: `glimpse://pair?server=<base URL>&code=<code>`.
 * The server is percent-encoded; the code is opaque, single use and good for
 * ten minutes.
 */
data class PairingLink(
    val server: String,
    val code: String,
) {
    /** The server's host, which is what the app shows people. */
    val host: String get() = URI(server).host

    companion object {
        private const val MAX_CODE_LENGTH = 128

        /**
         * Reads a pairing link, or returns null when [text] isn't one. Release
         * builds only accept HTTPS servers; [allowCleartext] lets debug builds
         * pair with a server on the development machine.
         */
        fun parse(
            text: String,
            allowCleartext: Boolean,
        ): PairingLink? {
            val uri =
                try {
                    URI(text.trim())
                } catch (_: URISyntaxException) {
                    return null
                }
            if (!uri.scheme.equals("glimpse", ignoreCase = true)) return null
            // `glimpse://pair` has `pair` as its host; some writers put it in the path.
            val target = uri.host ?: uri.path?.trimStart('/')
            if (!target.equals("pair", ignoreCase = true)) return null
            val query = queryParameters(uri.rawQuery ?: return null) ?: return null
            val server = normalizeServer(query["server"] ?: return null, allowCleartext) ?: return null
            val code = query["code"]?.takeIf { it.isNotEmpty() && it.length <= MAX_CODE_LENGTH } ?: return null
            return PairingLink(server, code)
        }

        private fun queryParameters(rawQuery: String): Map<String, String>? {
            val parameters = mutableMapOf<String, String>()
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                try {
                    // The first value counts, as with URLSearchParams.get.
                    parameters.putIfAbsent(decode(name), decode(value))
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }
            return parameters
        }

        // URLDecoder reads `+` as a space, as form encoding does. encodeURIComponent
        // never writes a bare `+`, so a literal one has to stay what it is.
        private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)

        /** The base URL without a trailing slash, or null when it isn't one the app may use. */
        fun normalizeServer(
            server: String,
            allowCleartext: Boolean,
        ): String? {
            val uri =
                try {
                    URI(server.trim())
                } catch (_: URISyntaxException) {
                    return null
                }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "https" && !(allowCleartext && scheme == "http")) return null
            if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return "$scheme://${uri.host.lowercase()}$port$path"
        }
    }
}
