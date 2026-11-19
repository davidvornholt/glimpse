package io.github.davidvornholt.glimpse.shared.upload

import io.github.davidvornholt.glimpse.shared.api.GlimpseJson
import io.github.davidvornholt.glimpse.shared.api.OriginalType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant

/**
 * The photos on the phone, one directory each under [root]: `meta.json` with
 * the [CaptureRecord], `preview.jpg`, and the original until it is uploaded.
 */
class CaptureStore(
    private val root: File,
) : CaptureRecords {
    private val mutex = Mutex()
    private val loaded = MutableStateFlow(false)
    private val interrupted = mutableListOf<CaptureRecord>()
    private val all = MutableStateFlow<Map<String, CaptureRecord>>(emptyMap())

    /** Every record, once [load] has run. */
    val records: StateFlow<Map<String, CaptureRecord>> = all.asStateFlow()

    fun directory(id: String) = File(root, id)

    fun previewFile(id: String) = File(directory(id), "preview.jpg")

    fun originalFile(record: CaptureRecord) = File(directory(record.id), "original.${record.originalType.extension}")

    private fun metaFile(id: String) = File(directory(id), "meta.json")

    suspend fun prepare(id: String): File =
        mutex.withLock {
            loadLocked()
            withContext(Dispatchers.IO) {
                directory(id).apply { if (!isDirectory && !mkdirs()) throw IOException("Couldn't make $this") }
            }
        }

    /** Written before CameraX receives its output file. A new process can finish either JPEG. */
    suspend fun beginCamera(record: CaptureRecord): File {
        val dir = prepare(record.id)
        write(record, File(dir, JOURNAL))
        return File(dir, CAMERA_OUTPUT)
    }

    /** Only records found on the first load, never a camera currently writing its JPEG. */
    suspend fun interruptedCameras(): List<CaptureRecord> =
        mutex.withLock {
            loadLocked()
            interrupted.toList()
        }

    /**
     * Finishes each interrupted camera with the JPEG it left, or [abandon]s it when only its
     * journal is there. A directory holding an image recovery can't place stays as it is,
     * because that image may be the only copy of the photo.
     */
    suspend fun recoverCameras(
        finish: suspend (CaptureRecord, File) -> Unit,
        abandon: suspend (String) -> Unit,
    ) {
        for (record in interruptedCameras()) {
            val left =
                withContext(Dispatchers.IO) {
                    directory(record.id).list().orEmpty().filterNot { it == JOURNAL || it == "$JOURNAL.part" }
                }
            val jpeg =
                listOf(CAMERA_OUTPUT, originalFile(record).name).firstOrNull { it in left }
                    // CameraX writes the photo to a file of its own beside the one it was given, and
                    // renames it only once it's complete. Two of them can't be told apart.
                    ?: left.singleOrNull { it.startsWith("CameraX") && it.endsWith(".jpg") }
            if (jpeg != null) {
                try {
                    finish(record, File(directory(record.id), jpeg))
                } catch (_: IOException) {
                    // Keep the journal and JPEG for recovery, including when storage is full.
                }
            } else if (left.isEmpty()) {
                abandon(record.id)
            }
        }
    }

    suspend fun load() {
        mutex.withLock { loadLocked() }
    }

    private suspend fun loadLocked() {
        if (loaded.value) return
        all.value =
            withContext(Dispatchers.IO) {
                root
                    .listFiles()
                    .orEmpty()
                    .mapNotNull { directory ->
                        val meta = File(directory, "meta.json")
                        if (meta.isFile) {
                            // A record this version can't read stays on disk, so the
                            // photo it describes isn't lost with it.
                            read(meta)?.let { record ->
                                // Renaming the directory commits a new registration even if the
                                // process stops before its metadata can be rewritten.
                                if (record.id == directory.name) record else record.freshRegistration(directory.name)
                            }
                        } else {
                            val preparing = File(directory, JOURNAL)
                            if (preparing.isFile) read(preparing)?.let(interrupted::add)
                            // Older captures can have an original but no identifying metadata.
                            // Keep the only copy even when we cannot reconstruct its record.
                            null
                        }
                    }.associateBy { it.id }
            }
        loaded.value = true
    }

    private fun read(meta: File): CaptureRecord? =
        try {
            GlimpseJson.decodeFromString(CaptureRecord.serializer(), meta.readText())
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }

    /** Keeps a new record whose files are already in its directory. */
    suspend fun add(record: CaptureRecord) =
        mutex.withLock {
            loadLocked()
            write(record)
            withContext(Dispatchers.IO) { File(directory(record.id), JOURNAL).delete() }
            all.value += record.id to record
        }

    override suspend fun get(id: String): CaptureRecord? =
        mutex.withLock {
            loadLocked()
            all.value[id]
        }

    override suspend fun update(
        id: String,
        change: (CaptureRecord) -> CaptureRecord,
    ): CaptureRecord? =
        mutex.withLock {
            loadLocked()
            val current = all.value[id] ?: return@withLock null
            val next = change(current)
            if (next != current) {
                write(next)
                all.value += id to next
                // An uploaded original isn't needed on the phone any more.
                if (next.originalSent && !current.originalSent) {
                    withContext(Dispatchers.IO) { originalFile(next).delete() }
                }
            }
            next
        }

    /**
     * Moves a photo and both its image files to [newId], so its upload starts again from scratch.
     * A worker for the old id can no longer edit the record.
     */
    suspend fun renewRegistration(
        id: String,
        newId: String,
    ): CaptureRecord? =
        mutex.withLock {
            loadLocked()
            val record = all.value[id] ?: return@withLock null
            val fresh = record.freshRegistration(newId)
            withContext(Dispatchers.IO) {
                Files.move(directory(id).toPath(), directory(newId).toPath(), StandardCopyOption.ATOMIC_MOVE)
                all.value = all.value - id + (newId to fresh)
            }
            fresh
        }

    private fun CaptureRecord.freshRegistration(newId: String) =
        CaptureRecord(newId, takenAt, source, width, height, originalType, note, batchId, server = server)

    suspend fun remove(id: String) =
        mutex.withLock {
            loadLocked()
            all.value -= id
            withContext(Dispatchers.IO) { directory(id).deleteRecursively() }
        }

    suspend fun clear() =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (root.exists() && !root.deleteRecursively()) throw IOException("Couldn't clear captures")
            }
            all.value = emptyMap()
            interrupted.clear()
            loaded.value = true
        }

    /**
     * How many originals [clear] would delete that the server doesn't have. A directory whose
     * record can't be read counts when it holds an original, and a record whose original is
     * gone doesn't. An original the server refused as too large stays here and counts.
     */
    suspend fun unsentOriginals(): Int =
        mutex.withLock {
            loadLocked()
            withContext(Dispatchers.IO) {
                root.listFiles().orEmpty().count { directory ->
                    val record = all.value[directory.name]
                    val held = OriginalType.entries.any { File(directory, "original.${it.extension}").isFile }
                    held && record?.originalSent != true && record?.deletedOnServer != true
                }
            }
        }

    /** Drops uploaded photos the server has deleted by now. */
    suspend fun prune(now: Instant = Instant.now()) {
        val expired =
            mutex.withLock {
                loadLocked()
                all.value.values.filter { it.isDone && Duration.between(it.takenAt, now) > KEEP_FOR }
            }
        expired.forEach { remove(it.id) }
    }

    private suspend fun write(
        record: CaptureRecord,
        target: File = metaFile(record.id),
    ) = withContext(Dispatchers.IO) {
        val partial = File(target.parentFile, "${target.name}.part")
        partial.writeText(GlimpseJson.encodeToString(CaptureRecord.serializer(), record))
        if (!partial.renameTo(target)) throw IOException("Couldn't save the record of ${record.id}")
    }

    private companion object {
        /** The record of a photo the camera is still taking. */
        const val JOURNAL = "preparing.json"
        const val CAMERA_OUTPUT = "camera.jpg"
        val KEEP_FOR: Duration = Duration.ofDays(31)
    }
}
