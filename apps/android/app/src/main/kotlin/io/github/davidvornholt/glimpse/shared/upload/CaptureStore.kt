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
    private val all = MutableStateFlow<Map<String, CaptureRecord>>(emptyMap())

    /** Every record, once [load] has run. */
    val records: StateFlow<Map<String, CaptureRecord>> = all.asStateFlow()

    fun directory(id: String) = File(root, id)

    fun previewFile(id: String) = File(directory(id), "preview.jpg")

    fun originalFile(record: CaptureRecord) = File(directory(record.id), "original.${record.originalType.extension}")

    private fun metaFile(id: String) = File(directory(id), "meta.json")

    /**
     * Makes the directory a new photo's files go in. The store loads first,
     * because loading deletes directories without a record, as this one is
     * until [add] writes it.
     */
    suspend fun prepare(id: String): File =
        mutex.withLock {
            loadLocked()
            withContext(Dispatchers.IO) {
                directory(id).apply { if (!isDirectory && !mkdirs()) throw IOException("Couldn't make $this") }
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
                            // A directory without a record is a capture the process
                            // died in the middle of.
                            directory.deleteRecursively()
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
        CaptureRecord(newId, takenAt, source, width, height, originalType, note, batchId)

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

    private suspend fun write(record: CaptureRecord) =
        withContext(Dispatchers.IO) {
            val target = metaFile(record.id)
            val partial = File(target.parentFile, "meta.json.part")
            partial.writeText(GlimpseJson.encodeToString(CaptureRecord.serializer(), record))
            if (!partial.renameTo(target)) throw IOException("Couldn't save the record of ${record.id}")
        }

    private companion object {
        val KEEP_FOR: Duration = Duration.ofDays(31)
    }
}
