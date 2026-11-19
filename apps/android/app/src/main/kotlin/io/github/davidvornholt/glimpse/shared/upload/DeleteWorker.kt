package io.github.davidvornholt.glimpse.shared.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import io.github.davidvornholt.glimpse.GlimpseApp
import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.pairing.PairingLink
import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.settings.SessionState
import io.github.davidvornholt.glimpse.shared.settings.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.io.IOException
import java.time.Duration

/** Deletes photos on the server, waiting for a network when there is none. */
class DeleteScheduler(
    context: Context,
    sessions: SessionStore,
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * The photos whose delete hasn't reached the server yet. They stay out of
     * every list, even after a restart, so a list from the server can't bring
     * them back in the meantime.
     */
    val pending: Flow<Set<String>> =
        combine(workManager.getWorkInfosByTagFlow(TAG), sessions.state) { works, state ->
            val server = (state as? SessionState.Paired)?.session?.server ?: return@combine emptySet()
            val serverTag = serverTag(server)
            works
                .filter { !it.state.isFinished && serverTag in it.tags }
                .mapNotNull { work -> work.tags.firstOrNull { it.startsWith(PHOTO_TAG) }?.removePrefix(PHOTO_TAG) }
                .toSet()
        }

    suspend fun enqueue(
        id: String,
        server: String,
    ) {
        val request =
            OneTimeWorkRequestBuilder<DeleteWorker>()
                .setInputData(workDataOf(DeleteWorker.KEY_ID to id, DeleteWorker.KEY_SERVER to server))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
                .addTag(TAG)
                .addTag(serverTag(server))
                .addTag(PHOTO_TAG + id)
                .build()
        workManager.enqueueUniqueWork("delete-$server-$id", ExistingWorkPolicy.KEEP, request).await()
    }

    private fun serverTag(server: String) = "delete-server:" + PairingLink.normalizeServer(server, allowCleartext = true)

    private companion object {
        const val TAG = "delete"
        const val PHOTO_TAG = "delete-photo:"
    }
}

internal fun deletionSession(
    origin: String?,
    current: Session?,
): Session? = current?.takeIf { origin != null && PairingLink.sameServer(it.server, origin) }

class DeleteWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val graph = (applicationContext as GlimpseApp).graph
        // Older work carries no server. Its destination cannot be recovered safely.
        val session = deletionSession(inputData.getString(KEY_SERVER), graph.sessions.currentSession()) ?: return Result.success()
        return try {
            graph.api(session).delete(id)
            Result.success()
        } catch (error: ApiException) {
            when {
                // The photo was never registered, or is gone already.
                error.tag == ApiErrorTag.NotFound -> {
                    Result.success()
                }

                error.isTransient -> {
                    Result.retry()
                }

                error.tag == ApiErrorTag.Unauthorized -> {
                    graph.sessions.markRemoved(session.token)
                    Result.failure()
                }

                else -> {
                    Result.failure()
                }
            }
        } catch (_: IOException) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_SERVER = "server"
    }
}
