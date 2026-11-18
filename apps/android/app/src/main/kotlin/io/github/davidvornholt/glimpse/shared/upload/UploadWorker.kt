package io.github.davidvornholt.glimpse.shared.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.davidvornholt.glimpse.GlimpseApp
import io.github.davidvornholt.glimpse.shared.api.GlimpseApi
import io.github.davidvornholt.glimpse.shared.api.Photo
import java.io.File

/** Uploads one photo, picking up wherever an earlier run stopped. */
class UploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val graph = (applicationContext as GlimpseApp).graph
        val session = graph.sessions.currentSession() ?: return Result.failure()
        val api = graph.api(session)
        val store = graph.captures
        val runner = UploadRunner(ApiTarget(api, store), store, graph.uploads::reportProgress)
        val outcome =
            try {
                runner.run(id)
            } finally {
                graph.uploads.finished(id)
            }
        return when (outcome) {
            UploadOutcome.Done, UploadOutcome.Gone -> {
                Result.success()
            }

            UploadOutcome.RetryLater -> {
                Result.retry()
            }

            UploadOutcome.Failed -> {
                Result.failure()
            }

            UploadOutcome.Unpaired -> {
                graph.sessions.markRemoved(session.token)
                Result.failure()
            }
        }
    }

    private class ApiTarget(
        private val api: GlimpseApi,
        private val store: CaptureStore,
    ) : UploadTarget {
        override suspend fun register(record: CaptureRecord): Photo = api.register(record.id, record.metadata).photo

        override suspend fun setNote(record: CaptureRecord): Photo = api.setNote(record.id, record.note)

        override suspend fun putPreview(
            record: CaptureRecord,
            onProgress: (Long, Long) -> Unit,
        ): Photo = api.putPreview(record.id, existing(store.previewFile(record.id)), onProgress)

        override suspend fun putOriginal(
            record: CaptureRecord,
            onProgress: (Long, Long) -> Unit,
        ): Photo = api.putOriginal(record.id, existing(store.originalFile(record)), record.originalType, onProgress)

        private fun existing(file: File) = file.takeIf { it.isFile } ?: throw MissingFileException(file)
    }

    companion object {
        const val KEY_ID = "id"
    }
}
