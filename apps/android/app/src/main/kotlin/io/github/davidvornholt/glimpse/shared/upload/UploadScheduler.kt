package io.github.davidvornholt.glimpse.shared.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration

/** How far an upload is, from 0 to 1, while it runs. */
data class UploadProgress(
    val fraction: Float,
)

/**
 * Queues uploads with WorkManager: one unique work per photo, expedited
 * where the quota allows, waiting for a network and retried with backoff,
 * which survives the app's process dying.
 */
class UploadScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context)
    private val running = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())

    val progress: StateFlow<Map<String, UploadProgress>> = running.asStateFlow()

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueue(id: String) {
        val request =
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(UploadWorker.KEY_ID to id))
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
                .addTag(TAG)
                .build()
        // A change while an upload runs, such as a new note, queues another run behind it.
        workManager.enqueueUniqueWork(name(id), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel(id: String) {
        workManager.cancelUniqueWork(name(id))
        finished(id)
    }

    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG)
        running.value = emptyMap()
    }

    fun reportProgress(
        id: String,
        step: UploadStep,
        sent: Long,
        total: Long,
    ) {
        val within = if (total > 0) sent.toFloat() / total else 1f
        // The preview counts for the first half: it is what makes the photo ready.
        val fraction =
            when (step) {
                UploadStep.Register, UploadStep.Note -> 0f
                UploadStep.Preview -> within * PREVIEW_SHARE
                UploadStep.Original -> PREVIEW_SHARE + within * (1 - PREVIEW_SHARE)
            }
        running.update { it + (id to UploadProgress(fraction.coerceIn(0f, 1f))) }
    }

    fun finished(id: String) {
        running.update { it - id }
    }

    private fun name(id: String) = "upload-$id"

    companion object {
        const val TAG = "upload"
        private const val PREVIEW_SHARE = 0.5f
        private val BACKOFF: Duration = Duration.ofSeconds(10)
    }
}
