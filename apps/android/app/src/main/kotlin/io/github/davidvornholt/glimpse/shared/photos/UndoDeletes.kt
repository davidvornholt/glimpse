package io.github.davidvornholt.glimpse.shared.photos

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [server] is the one the photo was on when Delete was tapped, so the delete goes nowhere else. */
data class PendingDelete(
    val id: String,
    val server: String,
    val deadline: Long,
)

/** The deadline and the decision survive the activity. A new process settles an overdue delete. */
class UndoDeletes(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    private val now: () -> Long,
    private val delete: suspend (id: String, server: String) -> Unit,
) {
    private val mutex = Mutex()
    private val saved =
        store.data.map { prefs ->
            val id = prefs[ID]
            val server = prefs[SERVER]
            if (id != null && server != null) PendingDelete(id, server, prefs[DEADLINE] ?: 0) else null
        }
    val pending = saved.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            pending.collectLatest { item ->
                if (item != null) {
                    delay((item.deadline - now()).coerceAtLeast(0))
                    mutex.withLock {
                        if (saved.first() == item) {
                            delete(item.id, item.server)
                            store.edit { it.clear() }
                        }
                    }
                }
            }
        }
    }

    suspend fun current(): PendingDelete? = saved.first()

    suspend fun start(
        id: String,
        server: String,
        timeout: Long,
    ) = mutex.withLock {
        val previous = saved.first()
        if (previous?.id == id) return@withLock
        if (previous != null) delete(previous.id, previous.server)
        store.edit {
            it[ID] = id
            it[SERVER] = server
            val started = now()
            it[DEADLINE] = started + timeout.coerceAtMost(Long.MAX_VALUE - started)
        }
        Unit
    }

    suspend fun undo(id: String) =
        mutex.withLock {
            val item = saved.first()
            if (item?.id == id && now() < item.deadline) store.edit { it.clear() }
            Unit
        }

    suspend fun clear() = mutex.withLock { store.edit { it.clear() } }

    private companion object {
        val ID = stringPreferencesKey("id")
        val SERVER = stringPreferencesKey("server")
        val DEADLINE = longPreferencesKey("deadline")
    }
}
