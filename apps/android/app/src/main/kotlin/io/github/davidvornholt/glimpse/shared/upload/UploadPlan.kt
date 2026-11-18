package io.github.davidvornholt.glimpse.shared.upload

import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.api.Photo
import java.io.File
import java.io.IOException

/** A photo's file vanished from the phone, so that part of its upload can't happen. */
class MissingFileException(
    file: File,
) : Exception("${file.name} is missing")

/** One request of an upload. */
enum class UploadStep { Register, Note, Preview, Original }

/**
 * The order an upload goes in, and what each answer means for it. The
 * preview goes before the original because it is what makes the photo ready
 * for an agent, and a changed note is sent as soon as the photo exists.
 */
object UploadPlan {
    /** How often the app registers a photo again after the server lost it. */
    private const val MAX_REGISTRATIONS = 2

    fun nextStep(record: CaptureRecord): UploadStep? =
        when {
            record.failure != null -> null
            !record.registered -> UploadStep.Register
            record.note != record.serverNote -> UploadStep.Note
            !record.previewSent -> UploadStep.Preview
            !record.originalSent && !record.originalSkipped -> UploadStep.Original
            else -> null
        }

    /** The record after [step] succeeded with [photo] as the answer. */
    fun succeeded(
        record: CaptureRecord,
        step: UploadStep,
        photo: Photo,
    ): CaptureRecord =
        when (step) {
            UploadStep.Register ->
                record.copy(registered = true, serverNote = photo.note, registrations = record.registrations + 1)
            UploadStep.Note -> record.copy(serverNote = photo.note)
            UploadStep.Preview -> record.copy(previewSent = true, serverNote = photo.note)
            UploadStep.Original -> record.copy(originalSent = true, serverNote = photo.note)
        }

    sealed interface Decision {
        /** Change the record and go on at once. */
        data class Update(
            val change: (CaptureRecord) -> CaptureRecord,
        ) : Decision

        /** Try the step again later, when the network or the server may be back. */
        data object RetryLater : Decision

        /** The server no longer knows this phone; nothing uploads until it pairs again. */
        data object Unpaired : Decision

        data class Fail(
            val failure: UploadFailure,
        ) : Decision
    }

    fun failed(
        record: CaptureRecord,
        step: UploadStep,
        error: Throwable,
    ): Decision {
        if (error is MissingFileException) {
            // Without the original the preview stands in for it; without the preview nothing can.
            return if (step == UploadStep.Original) {
                Decision.Update { it.copy(originalSkipped = true) }
            } else {
                Decision.Fail(UploadFailure.Rejected)
            }
        }
        if (error is IOException) return Decision.RetryLater
        if (error !is ApiException) return Decision.Fail(UploadFailure.Rejected)
        if (error.isTransient) return Decision.RetryLater
        return when (error.tag) {
            ApiErrorTag.Unauthorized -> Decision.Unpaired
            ApiErrorTag.Conflict -> Decision.Fail(UploadFailure.Conflict)
            ApiErrorTag.NotFound ->
                if (step != UploadStep.Register && record.registrations < MAX_REGISTRATIONS) {
                    // The server lost the photo, perhaps because its data was reset.
                    Decision.Update { it.copy(registered = false, previewSent = false, originalSent = false) }
                } else {
                    Decision.Fail(UploadFailure.Rejected)
                }
            ApiErrorTag.TooLarge ->
                if (step == UploadStep.Original) {
                    Decision.Update { it.copy(originalSkipped = true) }
                } else {
                    Decision.Fail(UploadFailure.Rejected)
                }
            else -> Decision.Fail(UploadFailure.Rejected)
        }
    }
}
