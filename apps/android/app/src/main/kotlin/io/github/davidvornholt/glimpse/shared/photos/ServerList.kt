package io.github.davidvornholt.glimpse.shared.photos

import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.ulid.UlidGenerator
import io.github.davidvornholt.glimpse.shared.upload.CaptureRecord
import java.time.Duration
import java.time.Instant

/** Every photo the server of [session] had since [since], fetched as the event stream (re)opened. */
data class ServerList(
    val session: Session,
    val ids: Set<String>,
    val since: Instant,
) {
    /**
     * The photos among [records] someone deleted on the server while the phone
     * wasn't listening, with `glimpse delete` or from another phone, or that
     * expired there. A photo the list lacks is only a candidate, because its
     * upload may have finished while the list loaded, so [exists] asks the
     * server about each; null, for no answer, keeps the photo.
     */
    suspend fun deletedAmong(
        records: Collection<CaptureRecord>,
        exists: suspend (String) -> Boolean?,
    ): List<String> =
        records
            .filter { it.isDone && it.isReady && it.id !in ids && sentAfterSince(it.id) }
            .filter { exists(it.id) == false }
            .map { it.id }

    private fun sentAfterSince(id: String): Boolean = Instant.ofEpochMilli(UlidGenerator.timeOf(id)).isAfter(since + CLOCK_MARGIN)

    private companion object {
        /** How far the phone's clock may be from the server's. */
        val CLOCK_MARGIN: Duration = Duration.ofDays(1)
    }
}
