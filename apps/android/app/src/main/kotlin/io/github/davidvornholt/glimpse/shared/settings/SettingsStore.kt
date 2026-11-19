package io.github.davidvornholt.glimpse.shared.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CaptureMode { Photo, Scan }

enum class FlashSetting { Off, Auto, On }

data class Settings(
    val keepCopyInGallery: Boolean = false,
    /** A note waiting for the next photo. */
    val pendingNote: String? = null,
    val mode: CaptureMode = CaptureMode.Photo,
    val flash: FlashSetting = FlashSetting.Off,
)

class SettingsStore(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO)) {
            context.preferencesDataStoreFile("settings")
        },
        scope,
    )

    val settings: StateFlow<Settings> = store.data.map { it.toSettings() }.stateIn(scope, SharingStarted.Eagerly, Settings())

    suspend fun current(): Settings = store.data.first().toSettings()

    private fun Preferences.toSettings() =
        Settings(
            keepCopyInGallery = this[KEEP_COPY] ?: false,
            pendingNote = this[PENDING_NOTE],
            mode = this[MODE]?.let { name -> CaptureMode.entries.firstOrNull { it.name == name } } ?: CaptureMode.Photo,
            flash = this[FLASH]?.let { name -> FlashSetting.entries.firstOrNull { it.name == name } } ?: FlashSetting.Off,
        )

    suspend fun setKeepCopyInGallery(keep: Boolean) = store.edit { it[KEEP_COPY] = keep }

    suspend fun setPendingNote(note: String?) =
        store.edit {
            if (note.isNullOrBlank()) it.remove(PENDING_NOTE) else it[PENDING_NOTE] = note
        }

    private val reservations = Mutex()
    private val active = mutableSetOf<String>()

    /** A capture owns its note from the shutter press until it produces a photo or fails. */
    suspend fun reserveNote(id: String) =
        reservations.withLock {
            store.edit {
                val key = reservation(id)
                if (key !in it) {
                    it[PENDING_NOTE]?.let { note ->
                        it[key] = note
                        it.remove(PENDING_NOTE)
                    }
                }
            }
            active.add(id)
            Unit
        }

    suspend fun reservedNote(id: String): String? = store.data.first()[reservation(id)]

    suspend fun finishNote(
        id: String,
        used: Boolean,
    ) = reservations.withLock {
        store.edit {
            val key = reservation(id)
            val note = it[key]
            it.remove(key)
            if (!used && note != null && PENDING_NOTE !in it) it[PENDING_NOTE] = note
        }
        active.remove(id)
        Unit
    }

    /** Reconcile a process death between reserving a note and receiving or recording the image. */
    suspend fun recoverNotes(
        waiting: Set<String>,
        used: Set<String>,
    ) = reservations.withLock {
        store.edit { prefs ->
            for (key in prefs.asMap().keys.filter { it.name.startsWith(RESERVED) }) {
                val id = key.name.removePrefix(RESERVED)
                if (id in active || id in waiting) continue
                val note = prefs[reservation(id)]
                if (id !in used && note != null && PENDING_NOTE !in prefs) prefs[PENDING_NOTE] = note
                prefs.remove(key)
            }
        }
        Unit
    }

    private fun reservation(id: String) = stringPreferencesKey(RESERVED + id)

    /** Drops the next-photo note and every capture's reserved copy of it, so none reaches another server. */
    suspend fun clearNotes() =
        reservations.withLock {
            store.edit { prefs ->
                prefs.remove(PENDING_NOTE)
                prefs
                    .asMap()
                    .keys
                    .filter { it.name.startsWith(RESERVED) }
                    .forEach { prefs.remove(it) }
            }
            Unit
        }

    suspend fun setMode(mode: CaptureMode) = store.edit { it[MODE] = mode.name }

    suspend fun setFlash(flash: FlashSetting) = store.edit { it[FLASH] = flash.name }

    suspend fun clear() = store.edit { it.clear() }

    private companion object {
        const val RESERVED = "capture_note_"
        val KEEP_COPY = booleanPreferencesKey("keep_copy_in_gallery")
        val PENDING_NOTE = stringPreferencesKey("pending_note")
        val MODE = stringPreferencesKey("mode")
        val FLASH = stringPreferencesKey("flash")
    }
}
