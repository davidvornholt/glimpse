package io.github.davidvornholt.glimpse.shared.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.davidvornholt.glimpse.shared.security.TokenCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The pairing this phone has with a server. The token stays in memory only. */
data class Session(
    val server: String,
    val token: String,
    val deviceId: String,
    val deviceName: String,
) {
    override fun toString() = "Session(server=$server, deviceId=$deviceId, deviceName=$deviceName)"
}

sealed interface SessionState {
    data object Loading : SessionState

    /**
     * Not paired. [removedFrom] names the server that no longer knows this
     * phone, when that is why.
     */
    data class Unpaired(
        val removedFrom: String? = null,
    ) : SessionState

    data class Paired(
        val session: Session,
    ) : SessionState
}

class SessionStore(
    context: Context,
    scope: CoroutineScope,
    private val cipher: TokenCipher = TokenCipher(),
) {
    private val store =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO)) {
            context.preferencesDataStoreFile("session")
        }

    val state: StateFlow<SessionState> =
        store.data
            .map { preferences ->
                val server = preferences[SERVER]
                val sealedToken = preferences[TOKEN]
                val token = sealedToken?.let(cipher::decrypt)
                if (server != null && token != null) {
                    SessionState.Paired(
                        Session(server, token, preferences[DEVICE_ID].orEmpty(), preferences[DEVICE_NAME].orEmpty()),
                    )
                } else {
                    SessionState.Unpaired(removedFrom = server.takeIf { preferences[REMOVED] == true })
                }
            }.stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)

    /** Waits for the stored state, for code that runs before any screen. */
    suspend fun current(): SessionState = state.filterNot { it is SessionState.Loading }.first()

    suspend fun currentSession(): Session? = (current() as? SessionState.Paired)?.session

    /** The server this phone was last paired with, even after it was removed. */
    suspend fun lastServer(): String? = store.data.first()[SERVER]

    suspend fun save(session: Session) {
        val sealedToken = cipher.encrypt(session.token)
        store.edit {
            it[SERVER] = session.server
            it[TOKEN] = sealedToken
            it[DEVICE_ID] = session.deviceId
            it[DEVICE_NAME] = session.deviceName
            it.remove(REMOVED)
        }
    }

    /** The server answered 401: it doesn't know this phone any more. */
    suspend fun markRemoved(token: String) {
        store.edit {
            // Only the pairing that failed is dropped, not one made since.
            val current = it[TOKEN]?.let(cipher::decrypt)
            if (current == token) {
                it.remove(TOKEN)
                it[REMOVED] = true
            }
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val SERVER = stringPreferencesKey("server")
        val TOKEN = stringPreferencesKey("token")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val REMOVED = booleanPreferencesKey("removed")
    }
}
