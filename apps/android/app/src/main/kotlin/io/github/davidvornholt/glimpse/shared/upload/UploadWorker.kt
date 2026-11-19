package io.github.davidvornholt.glimpse.shared.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.davidvornholt.glimpse.GlimpseApp
import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
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
        if (!graph.sessions.isCurrent(session.pairing)) return Result.retry()
        val api = graph.api(session)
        val store = graph.captures
        // The server's answers go to the feed too, so the photo shows the note the phone just sent.
        val target = ApiTarget(api, store) { photo -> if (photo.readyAt != null) graph.feed.upsert(session, photo) }
        val runner = UploadRunner(target, store, graph.uploads::reportProgress)
        val outcome =
            try {
                runner.run(id, session.pairing)
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
        private val answered: (Photo) -> Unit,
    ) : UploadTarget {
        override suspend fun register(record: CaptureRecord): Photo = api.register(record.id, record.metadata).photo.also(answered)

        override suspend fun setNote(record: CaptureRecord): Photo = api.setNote(record.id, record.note).also(answered)

        override suspend fun putPreview(
            record: CaptureRecord,
            onProgress: (Long, Long) -> Unit,
        ): Photo = api.putPreview(record.id, existing(store.previewFile(record.id)), onProgress).also(answered)

        override suspend fun putOriginal(
            record: CaptureRecord,
            onProgress: (Long, Long) -> Unit,
        ): Photo =
            api
                .putOriginal(record.id, existing(store.originalFile(record)), record.originalType, onProgress)
                .also(answered)

        override suspend fun exists(record: CaptureRecord): Boolean =
            try {
                api.photo(record.id)
                true
            } catch (error: ApiException) {
                if (error.tag == ApiErrorTag.NotFound) false else throw error
            }

        private fun existing(file: File) = file.takeIf { it.isFile } ?: throw MissingFileException(file)
    }

    companion object {
        const val KEY_ID = "id"
    }
}
