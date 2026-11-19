package io.github.davidvornholt.glimpse.shared.photos

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
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

/** The deadline and the decision survive the activity. A running process settles a delete at its deadline. */
class UndoDeletes(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    private val now: () -> Long,
    private val wake: suspend (deadline: Long) -> Unit,
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
                    while (!settle(item)) delay(RETRY_MILLIS)
                }
            }
        }
    }

    /** Deletes [item] unless Undo or a newer delete replaced it. False when that failed and is worth another try. */
    private suspend fun settle(item: PendingDelete): Boolean =
        mutex.withLock {
            try {
                if (saved.first() == item) {
                    delete(item.id, item.server)
                    store.edit { it.clear() }
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Storage or WorkManager failed, perhaps because the phone is full. An exception here would end the
                // app, and the next launch would hit the same overdue delete, so it stays saved and is tried again.
                false
            }
        }

    suspend fun current(): PendingDelete? = saved.first()

    /** For the work [wake] scheduled: settles the pending delete once its deadline has passed. False when that failed. */
    suspend fun settleDue(): Boolean {
        val item =
            try {
                // Under the lock, so a delete that start is still saving is seen. The lock is let go before the wait,
                // so an Undo can still come first.
                mutex.withLock { saved.first() } ?: return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // As in settle, storage may have failed, so the work is tried again.
                return false
            }
        delay((item.deadline - now()).coerceAtLeast(0))
        return settle(item)
    }

    suspend fun start(
        id: String,
        server: String,
        timeout: Long,
    ) = mutex.withLock {
        try {
            val previous = saved.first()
            if (previous?.id == id) return@withLock
            if (previous != null) {
                delete(previous.id, previous.server)
                // Cleared at once, so the earlier photo can't be undone after its delete is queued, even if the new
                // one then fails to start.
                store.edit { it.clear() }
            }
            val started = now()
            val deadline = started + timeout.coerceAtMost(Long.MAX_VALUE - started)
            // Scheduled before it is saved, so a process that dies right after still wakes for it.
            wake(deadline)
            store.edit {
                it[ID] = id
                it[SERVER] = server
                it[DEADLINE] = deadline
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // As in settle, the phone may be full. The new delete isn't started, so the photo stays visible instead
            // of the app ending, and an earlier delete that hasn't been cleared is tried again at its deadline.
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

    internal companion object {
        const val RETRY_MILLIS = 30_000L

        private val ID = stringPreferencesKey("id")
        private val SERVER = stringPreferencesKey("server")
        private val DEADLINE = longPreferencesKey("deadline")
    }
}
