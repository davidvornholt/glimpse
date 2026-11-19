package io.github.davidvornholt.glimpse.features.pairing

import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.api.GlimpseApi
import io.github.davidvornholt.glimpse.shared.pairing.PairingLink
import io.github.davidvornholt.glimpse.shared.photos.PhotoLibrary
import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.settings.SessionStore
import java.io.IOException

/** Why pairing didn't work, each with its own fix. */
enum class PairingFailure {
    /** The code ran out or another phone used it first. */
    CodeExpired,

    /** The server stopped taking codes for a while. */
    TooManyTries,

    /** The server refused the device name or the code's format. */
    Rejected,

    /** The server couldn't be reached, or isn't a Glimpse server. */
    Unreachable,

    /** The server is on the local network, which the person didn't let the app reach. */
    LocalNetworkDenied,

    Unknown,
}

/** Trades a pairing code for this phone's own token, then starts using it. */
class PairingClaimer(
    private val anonymousApi: (String) -> GlimpseApi,
    private val sessions: SessionStore,
    private val library: PhotoLibrary,
) {
    data class SwitchWarning(
        val previous: String,
        val unsent: Int,
    )

    suspend fun switchWarning(link: PairingLink): SwitchWarning? {
        val previous = sessions.lastServer() ?: return null
        return if (PairingLink.sameServer(previous, link.server)) null else SwitchWarning(previous, library.unsentCount())
    }

    suspend fun claim(
        link: PairingLink,
        deviceName: String,
    ): Session {
        val result = anonymousApi(link.server).claim(link.code, deviceName.trim())
        // Photos waiting for another server don't belong on this one.
        val previous = sessions.lastServer()
        if (previous != null) {
            if (PairingLink.sameServer(previous, link.server)) {
                library.prepareForNewDevice(sessions.lastDeviceId())
            } else {
                library.clear()
            }
        }
        val session = Session(link.server, result.token, result.device.id, result.device.name)
        sessions.save(session)
        library.resumeUploads(session.deviceId)
        return session
    }

    companion object {
        fun failureOf(error: Exception): PairingFailure =
            when (error) {
                is ApiException -> {
                    when (error.tag) {
                        ApiErrorTag.NotFound -> PairingFailure.CodeExpired
                        ApiErrorTag.RateLimited -> PairingFailure.TooManyTries
                        ApiErrorTag.InvalidRequest -> PairingFailure.Rejected
                        else -> PairingFailure.Unknown
                    }
                }

                is IOException -> {
                    PairingFailure.Unreachable
                }

                else -> {
                    PairingFailure.Unknown
                }
            }
    }
}
