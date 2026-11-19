package io.github.davidvornholt.glimpse.shared.settings

import android.content.Context
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
    context: Context,
    scope: CoroutineScope,
) {
    private val store =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO)) {
            context.preferencesDataStoreFile("settings")
        }

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

    /** Hands out the note waiting for the next photo, once. */
    suspend fun takePendingNote(): String? {
        var note: String? = null
        store.edit {
            note = it[PENDING_NOTE]
            it.remove(PENDING_NOTE)
        }
        return note
    }

    suspend fun setMode(mode: CaptureMode) = store.edit { it[MODE] = mode.name }

    suspend fun setFlash(flash: FlashSetting) = store.edit { it[FLASH] = flash.name }

    suspend fun clear() = store.edit { it.clear() }

    private companion object {
        val KEEP_COPY = booleanPreferencesKey("keep_copy_in_gallery")
        val PENDING_NOTE = stringPreferencesKey("pending_note")
        val MODE = stringPreferencesKey("mode")
        val FLASH = stringPreferencesKey("flash")
    }
}
