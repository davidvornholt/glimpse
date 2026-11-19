package io.github.davidvornholt.glimpse.shared.upload

import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.PhotoMetadata
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.api.Time
import io.github.davidvornholt.glimpse.shared.settings.PairingId
import kotlinx.serialization.Serializable

/**
 * A photo the phone took or was given, and how far its upload got. It is kept
 * next to the photo's files, so the upload carries on after the process dies.
 */
@Serializable
data class CaptureRecord(
    val id: String,
    val takenAt: Time,
    val source: PhotoSource,
    val width: Int,
    val height: Int,
    val originalType: OriginalType,
    val note: String? = null,
    val batchId: String? = null,
    val registered: Boolean = false,
    /** The note the server has, as its last answer said. */
    val serverNote: String? = null,
    val previewSent: Boolean = false,
    val originalSent: Boolean = false,
    /** The server refused the original as too large; the preview stands in for it. */
    val originalSkipped: Boolean = false,
    /** How often the server had lost the registration and the app made it again. */
    val registrations: Int = 0,
    /** Someone deleted the photo on the server after an agent could see it, so the rest of its upload is moot. */
    val deletedOnServer: Boolean = false,
    val failure: UploadFailure? = null,
    /** Zero once the server acknowledged the latest phone edit. Older records used the note difference. */
    val noteEdit: Long = if (registered && note != serverNote) 1 else 0,
    val pairing: PairingId? = null,
) {
    fun withNote(value: String?) = copy(note = value, noteEdit = noteEdit + 1)

    val metadata: PhotoMetadata
        get() = PhotoMetadata(takenAt, source, width, height, originalType, note, batchId)

    /** An agent can see the photo once its preview is on the server. */
    val isReady: Boolean get() = previewSent

    val isDone: Boolean get() = failure == null && UploadPlan.nextStep(this) == null
}

/** Why an upload stopped for good. */
@Serializable
enum class UploadFailure {
    /** The server refused the photo, for example because it isn't a valid image. */
    Rejected,

    /** The server has a different photo under this id. */
    Conflict,
}
