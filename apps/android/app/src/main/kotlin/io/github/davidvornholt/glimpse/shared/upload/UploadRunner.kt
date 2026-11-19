package io.github.davidvornholt.glimpse.shared.upload

import io.github.davidvornholt.glimpse.shared.api.Photo
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** The requests an upload makes, so the runner can be tested without a server. */
interface UploadTarget {
    suspend fun register(record: CaptureRecord): Photo

    suspend fun setNote(record: CaptureRecord): Photo

    suspend fun putPreview(
        record: CaptureRecord,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Photo

    suspend fun putOriginal(
        record: CaptureRecord,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): Photo

    /** Whether the server still has the photo; throws when it can't say. */
    suspend fun exists(record: CaptureRecord): Boolean
}

/** Where the runner keeps records. [update] applies a change to the newest copy. */
interface CaptureRecords {
    suspend fun get(id: String): CaptureRecord?

    suspend fun update(
        id: String,
        change: (CaptureRecord) -> CaptureRecord,
    ): CaptureRecord?
}

enum class UploadOutcome { Done, RetryLater, Unpaired, Failed, Gone }

/** Takes one photo through [UploadPlan], step by step, until it is done or has to wait. */
class UploadRunner(
    private val target: UploadTarget,
    private val records: CaptureRecords,
    private val onProgress: (id: String, step: UploadStep, sent: Long, total: Long) -> Unit = { _, _, _, _ -> },
) {
    suspend fun run(id: String): UploadOutcome =
        try {
            runSteps(id)
        } catch (_: IOException) {
            // The phone couldn't save how far the upload got, perhaps because its storage is full.
            // The server takes each step again, so the next run starts from the last one saved.
            UploadOutcome.RetryLater
        }

    private suspend fun runSteps(id: String): UploadOutcome {
        while (true) {
            val record = records.get(id) ?: return UploadOutcome.Gone
            if (record.failure != null) return UploadOutcome.Failed
            val step = UploadPlan.nextStep(record) ?: return UploadOutcome.Done
            val photo =
                try {
                    perform(record, step)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    when (val decision = UploadPlan.failed(record, step, error)) {
                        is UploadPlan.Decision.Update -> {
                            records.update(id, decision.change) ?: return UploadOutcome.Gone
                            continue
                        }

                        UploadPlan.Decision.RetryLater -> {
                            return UploadOutcome.RetryLater
                        }

                        UploadPlan.Decision.ConfirmDeleted -> {
                            if (!confirmedGone(record)) return UploadOutcome.RetryLater
                            records.update(id) { it.copy(deletedOnServer = true) } ?: return UploadOutcome.Gone
                            continue
                        }

                        UploadPlan.Decision.Unpaired -> {
                            return UploadOutcome.Unpaired
                        }

                        is UploadPlan.Decision.Fail -> {
                            records.update(id) { it.copy(failure = decision.failure) }
                            return UploadOutcome.Failed
                        }
                    }
                }
            records.update(id) { UploadPlan.succeeded(it, step, photo) } ?: return UploadOutcome.Gone
        }
    }

    private suspend fun confirmedGone(record: CaptureRecord): Boolean =
        try {
            !target.exists(record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun perform(
        record: CaptureRecord,
        step: UploadStep,
    ): Photo =
        when (step) {
            UploadStep.Register -> target.register(record)
            UploadStep.Note -> target.setNote(record)
            UploadStep.Preview -> target.putPreview(record) { sent, total -> onProgress(record.id, step, sent, total) }
            UploadStep.Original -> target.putOriginal(record) { sent, total -> onProgress(record.id, step, sent, total) }
        }
}
