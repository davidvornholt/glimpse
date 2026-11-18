package io.github.davidvornholt.glimpse.shared.photos

import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.Photo
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.ulid.UlidGenerator
import io.github.davidvornholt.glimpse.shared.upload.CaptureRecord
import io.github.davidvornholt.glimpse.shared.upload.UploadFailure
import io.github.davidvornholt.glimpse.shared.upload.UploadProgress
import java.time.Instant

/** Where a photo's upload stands, as the filmstrip and the inbox show it. */
sealed interface UploadState {
    /** Just taken: the app is still preparing its files. */
    data object Preparing : UploadState

    /** Waiting for a network or its turn. */
    data object Queued : UploadState

    data class Uploading(
        val fraction: Float,
    ) : UploadState

    /** On the server, and an agent can see it. */
    data object Uploaded : UploadState

    data class Failed(
        val failure: UploadFailure,
    ) : UploadState
}

/** A photo as the app shows it, from the phone's record, the server's, or both. */
data class GalleryItem(
    val id: String,
    /** When the photo reached, or will reach, the server; what the inbox groups by. */
    val sentAt: Instant,
    val takenAt: Instant,
    val note: String?,
    val source: PhotoSource,
    val width: Int,
    val height: Int,
    val originalType: OriginalType,
    val viewedAt: Instant?,
    val expiresAt: Instant?,
    val deviceName: String?,
    val batchId: String?,
    val upload: UploadState,
    /** What Coil loads for the thumbnail: a file, a URL, or the frame grabbed at capture. */
    val thumbnail: Any?,
    val isLocal: Boolean,
) {
    val isViewed: Boolean get() = viewedAt != null

    companion object {
        fun merge(
            id: String,
            record: CaptureRecord?,
            remote: Photo?,
            progress: UploadProgress?,
            localPreview: Any?,
            remotePreview: Any?,
        ): GalleryItem? {
            val upload =
                when {
                    record == null -> UploadState.Uploaded
                    record.failure != null -> UploadState.Failed(record.failure)
                    record.isDone || (record.isReady && remote != null) -> UploadState.Uploaded
                    progress != null -> UploadState.Uploading(progress.fraction)
                    else -> UploadState.Queued
                }
            return when {
                record != null -> {
                    GalleryItem(
                        id = id,
                        sentAt = remote?.createdAt ?: Instant.ofEpochMilli(UlidGenerator.timeOf(id)),
                        takenAt = record.takenAt,
                        note = record.note,
                        source = record.source,
                        width = record.width,
                        height = record.height,
                        originalType = record.originalType,
                        viewedAt = remote?.viewedAt,
                        expiresAt = remote?.expiresAt,
                        deviceName = remote?.deviceName,
                        batchId = record.batchId,
                        upload = upload,
                        thumbnail = localPreview ?: remotePreview,
                        isLocal = true,
                    )
                }

                remote != null -> {
                    GalleryItem(
                        id = id,
                        sentAt = remote.createdAt,
                        takenAt = remote.takenAt,
                        note = remote.note,
                        source = remote.source,
                        width = remote.width,
                        height = remote.height,
                        originalType = remote.originalType,
                        viewedAt = remote.viewedAt,
                        expiresAt = remote.expiresAt,
                        deviceName = remote.deviceName,
                        batchId = remote.batchId,
                        upload = UploadState.Uploaded,
                        thumbnail = remotePreview,
                        isLocal = false,
                    )
                }

                else -> {
                    null
                }
            }
        }
    }
}
