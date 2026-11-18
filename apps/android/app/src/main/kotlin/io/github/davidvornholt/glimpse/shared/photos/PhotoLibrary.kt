package io.github.davidvornholt.glimpse.shared.photos

import android.graphics.Bitmap
import android.net.Uri
import io.github.davidvornholt.glimpse.shared.api.ApiErrorTag
import io.github.davidvornholt.glimpse.shared.api.ApiException
import io.github.davidvornholt.glimpse.shared.api.GlimpseApi
import io.github.davidvornholt.glimpse.shared.api.OriginalType
import io.github.davidvornholt.glimpse.shared.api.PhotoSource
import io.github.davidvornholt.glimpse.shared.image.Gallery
import io.github.davidvornholt.glimpse.shared.image.ImageProcessor
import io.github.davidvornholt.glimpse.shared.settings.Session
import io.github.davidvornholt.glimpse.shared.settings.SessionState
import io.github.davidvornholt.glimpse.shared.settings.SessionStore
import io.github.davidvornholt.glimpse.shared.settings.SettingsStore
import io.github.davidvornholt.glimpse.shared.ulid.UlidGenerator
import io.github.davidvornholt.glimpse.shared.upload.CaptureRecord
import io.github.davidvornholt.glimpse.shared.upload.CaptureStore
import io.github.davidvornholt.glimpse.shared.upload.DeleteScheduler
import io.github.davidvornholt.glimpse.shared.upload.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.Instant

/** A photo just taken, shown before its files are ready. */
private data class PendingCapture(
    val id: String,
    val takenAt: Instant,
    val source: PhotoSource,
    val frame: Bitmap?,
)

/**
 * Everything the screens do with photos: taking them in, merging the phone's
 * uploads with the server's list, and changing or deleting them.
 */
class PhotoLibrary(
    private val captures: CaptureStore,
    private val uploads: UploadScheduler,
    private val deletes: DeleteScheduler,
    private val feed: PhotoFeed,
    private val sessions: SessionStore,
    private val settings: SettingsStore,
    private val processor: ImageProcessor,
    private val gallery: Gallery,
    private val ids: UlidGenerator,
    private val apiFor: (Session) -> GlimpseApi,
    private val cacheDir: File,
    scope: CoroutineScope,
) {
    private val pending = MutableStateFlow<Map<String, PendingCapture>>(emptyMap())
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    init {
        scope.launch {
            captures.load()
            captures.prune()
        }
    }

    /** Every photo, newest first. */
    val items: StateFlow<List<GalleryItem>> =
        combine(captures.records, feed.photos, uploads.progress, pending, hidden) { records, remote, progress, pending, hidden ->
            val api = (sessions.state.value as? SessionState.Paired)?.session?.let(apiFor)
            val ids = records.keys + remote.keys + pending.keys - hidden
            ids
                .mapNotNull { id ->
                    val record = records[id]
                    val waiting = pending[id]
                    if (record == null && remote[id] == null && waiting != null) {
                        preparing(waiting)
                    } else {
                        GalleryItem.merge(
                            id = id,
                            record = record,
                            remote = remote[id],
                            progress = progress[id],
                            localPreview = record?.let { localPreview(it) } ?: waiting?.frame,
                            remotePreview = api?.previewUrl(id)?.toString(),
                        )
                    }
                }.sortedByDescending { it.id }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun preparing(capture: PendingCapture) =
        GalleryItem(
            id = capture.id,
            sentAt = capture.takenAt,
            takenAt = capture.takenAt,
            note = null,
            source = capture.source,
            width = capture.frame?.width ?: 1,
            height = capture.frame?.height ?: 1,
            originalType = OriginalType.Jpeg,
            viewedAt = null,
            expiresAt = null,
            deviceName = null,
            batchId = null,
            upload = UploadState.Preparing,
            thumbnail = capture.frame,
            isLocal = true,
        )

    private fun localPreview(record: CaptureRecord): File? = captures.previewFile(record.id).takeIf { it.isFile }

    fun newId(): String = ids.next()

    /** Shows a photo the camera is still saving, with the frame grabbed from the viewfinder. */
    fun showPending(
        id: String,
        source: PhotoSource,
        frame: Bitmap?,
    ) {
        pending.update { it + (id to PendingCapture(id, Instant.now(), source, frame)) }
    }

    fun dropPending(id: String) {
        pending.update { it - id }
    }

    /** Takes in a JPEG the camera saved, then queues its upload. */
    suspend fun addCapture(
        id: String,
        jpeg: File,
        takenAt: Instant,
    ) {
        try {
            val directory = captures.prepare(id)
            val record = CaptureRecord(id, takenAt, PhotoSource.Camera, 1, 1, OriginalType.Jpeg)
            val original = captures.originalFile(record)
            val prepared = processor.prepareCapture(jpeg, original)
            processor.writePreview(original, File(directory, "preview.jpg"))
            val note = settings.takePendingNote()
            captures.add(record.copy(width = prepared.width, height = prepared.height, note = note))
            uploads.enqueue(id)
            keepCopyIfWanted(original, OriginalType.Jpeg, id)
        } finally {
            dropPending(id)
        }
    }

    /**
     * Takes in images from another app or the document scanner. They share
     * one batch when there are several, as the pages of a scan do.
     */
    suspend fun addImages(
        uris: List<Uri>,
        source: PhotoSource,
        note: String?,
    ): Int {
        val batchId = if (uris.size > 1 || source == PhotoSource.Scan) ids.next() else null
        var added = 0
        for (uri in uris) {
            val id = ids.next()
            try {
                val directory = captures.prepare(id)
                val (original, prepared) =
                    processor.prepareShared(uri) { type -> File(directory, "original.${type.extension}") }
                processor.writePreview(original, File(directory, "preview.jpg"))
                // The note goes with the first image; the rest belong to it through the batch.
                captures.add(
                    CaptureRecord(
                        id = id,
                        takenAt = Instant.now(),
                        source = source,
                        width = prepared.width,
                        height = prepared.height,
                        originalType = prepared.type,
                        note = note.takeIf { added == 0 && !it.isNullOrBlank() },
                        batchId = batchId,
                    ),
                )
                uploads.enqueue(id)
                if (source == PhotoSource.Scan) keepCopyIfWanted(original, prepared.type, id)
                added++
            } catch (error: IOException) {
                captures.remove(id)
                if (uris.size == 1) throw error
            }
        }
        return added
    }

    private suspend fun keepCopyIfWanted(
        original: File,
        type: OriginalType,
        id: String,
    ) {
        if (!settings.current().keepCopyInGallery) return
        try {
            gallery.save(original, type.mimeType, "glimpse-$id.${type.extension}")
        } catch (_: IOException) {
            // The upload matters more than the copy; the gallery may be full.
        }
    }

    /** Changes a photo's note; the upload queue sends it, or the server gets it at once. */
    suspend fun setNote(
        id: String,
        note: String?,
    ) {
        val cleaned = note?.trim()?.takeIf { it.isNotEmpty() }
        if (captures.update(id) { it.copy(note = cleaned) } != null) {
            uploads.enqueue(id)
            return
        }
        val session = sessions.currentSession() ?: return
        try {
            feed.upsert(apiFor(session).setNote(id, cleaned))
        } catch (error: ApiException) {
            if (error.tag == ApiErrorTag.Unauthorized) sessions.markRemoved(session.token)
            throw error
        }
    }

    /** Takes a photo out of every list at once; [delete] or [restore] decides later. */
    fun hide(id: String) {
        hidden.update { it + id }
    }

    fun restore(id: String) {
        hidden.update { it - id }
    }

    suspend fun delete(id: String) {
        uploads.cancel(id)
        captures.remove(id)
        feed.forget(id)
        deletes.enqueue(id)
        hidden.update { it - id }
    }

    suspend fun retry(id: String) {
        captures.update(id) { it.copy(failure = null, registrations = 0) }
        uploads.enqueue(id)
    }

    /**
     * The best copy of a photo as a file: the phone's original while it has
     * one, otherwise the server's original or preview, downloaded to the cache.
     */
    suspend fun fileFor(item: GalleryItem): Pair<File, OriginalType> {
        val record = captures.get(item.id)
        if (record != null) {
            val original = captures.originalFile(record)
            if (original.isFile) return original to record.originalType
        }
        val session = sessions.currentSession() ?: throw IOException("Not paired")
        val api = apiFor(session)
        val directory = File(cacheDir, "shared").apply { mkdirs() }
        val remote = feed.photos.value[item.id]
        return if (remote?.hasOriginal != false) {
            val target = File(directory, "glimpse-${item.id}.${item.originalType.extension}")
            try {
                api.download(api.originalUrl(item.id), target)
                target to item.originalType
            } catch (error: ApiException) {
                if (error.tag != ApiErrorTag.NotFound) throw error
                downloadPreview(api, item.id, directory)
            }
        } else {
            downloadPreview(api, item.id, directory)
        }
    }

    private suspend fun downloadPreview(
        api: GlimpseApi,
        id: String,
        directory: File,
    ): Pair<File, OriginalType> {
        val local = captures.previewFile(id)
        if (local.isFile) return local to OriginalType.Jpeg
        val target = File(directory, "glimpse-$id.jpg")
        api.download(api.previewUrl(id), target)
        return target to OriginalType.Jpeg
    }

    suspend fun saveToGallery(item: GalleryItem) {
        val (file, type) = fileFor(item)
        gallery.save(file, type.mimeType, "glimpse-${item.id}.${type.extension}")
    }

    /** Forgets every photo on the phone, for unpairing. */
    suspend fun clear() {
        uploads.cancelAll()
        captures.clear()
        pending.value = emptyMap()
        hidden.value = emptySet()
    }

    /** Uploads whatever waited while the phone was unpaired. */
    suspend fun resumeUploads() {
        captures.load()
        captures.records.value.values
            .filter { !it.isDone && it.failure == null }
            .forEach { uploads.enqueue(it.id) }
    }
}
