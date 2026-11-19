package io.github.davidvornholt.glimpse.shared.photos

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UndoDeletesTest {
    @get:Rule val folder = TemporaryFolder()
    private val server = "https://glimpse.example.com"

    @Test
    fun `reopening Undo uses the original deadline and commits without a snackbar host`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("undo.preferences_pb") }
            val deleted = mutableListOf<String>()

            fun open(scope: CoroutineScope) = UndoDeletes(store, scope, { testScheduler.currentTime }, {}) { id, _ -> deleted.add(id) }
            val oldScope = CoroutineScope(coroutineContext + Job())
            val old = open(oldScope)
            old.start("photo", server, 8000)
            advanceTimeBy(3000)
            oldScope.cancel()
            val restored = open(backgroundScope)
            runCurrent()
            assertEquals(PendingDelete("photo", server, 8000), restored.pending.value)
            advanceTimeBy(5001)
            runCurrent()
            assertEquals(listOf("photo"), deleted)
            assertNull(restored.pending.value)
        }

    @Test
    fun `undo before the deadline cancels the deletion`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("undo.preferences_pb") }
            val deleted = mutableListOf<String>()
            val undo = UndoDeletes(store, backgroundScope, { testScheduler.currentTime }, {}) { id, _ -> deleted.add(id) }
            undo.start("first", server, 8000)
            undo.start("second", server, 8000)
            undo.undo("second")
            advanceTimeBy(8001)
            runCurrent()
            assertEquals(listOf("first"), deleted)
            assertNull(undo.pending.value)
        }

    @Test
    fun `a delete that fails at the deadline stays pending and is tried again`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("undo.preferences_pb") }
            val deleted = mutableListOf<String>()
            var failures = 1
            val crashes = mutableListOf<Throwable>()
            // Like the app's scope, where an exception nobody catches ends the process.
            val appScope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, error -> crashes += error })
            val undo =
                UndoDeletes(store, appScope, { testScheduler.currentTime }, {}) { id, _ ->
                    if (failures-- > 0) throw IOException("No space left on device")
                    deleted.add(id)
                }
            undo.start("photo", server, 8000)
            advanceTimeBy(8001)
            runCurrent()
            assertEquals(emptyList(), crashes)
            assertTrue(appScope.isActive)
            assertEquals("photo", undo.pending.value?.id)
            assertEquals(emptyList(), deleted)
            advanceTimeBy(UndoDeletes.RETRY_MILLIS)
            runCurrent()
            assertEquals(listOf("photo"), deleted)
            assertNull(undo.pending.value)
            appScope.cancel()
        }

    @Test
    fun `a second delete waits when the first can't be sent instead of ending the app`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("undo.preferences_pb") }
            val deleted = mutableListOf<String>()
            var failures = 1
            val undo =
                UndoDeletes(store, backgroundScope, { testScheduler.currentTime }, {}) { id, _ ->
                    if (failures-- > 0) throw IOException("No space left on device")
                    deleted.add(id)
                }
            undo.start("first", server, 8000)
            undo.start("second", server, 8000)
            runCurrent()
            assertEquals("first", undo.pending.value?.id)
            assertEquals(emptyList(), deleted)
            advanceTimeBy(8001)
            runCurrent()
            assertEquals(listOf("first"), deleted)
            assertNull(undo.pending.value)
        }

    @Test
    fun `a delete that can't be saved leaves the photo instead of ending the app`() =
        runTest {
            val store = FullStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("undo.preferences_pb") })
            val deleted = mutableListOf<String>()
            val undo = UndoDeletes(store, backgroundScope, { testScheduler.currentTime }, {}) { id, _ -> deleted.add(id) }
            undo.start("first", server, 8000)
            store.full = true
            // The first delete goes, but the phone can't save the second.
            undo.start("second", server, 8000)
            runCurrent()
            assertEquals(listOf("first"), deleted)
            assertEquals("first", undo.pending.value?.id)
            store.full = false
            advanceTimeBy(8001)
            runCurrent()
            assertNull(undo.pending.value)
            assertEquals(listOf("first", "first"), deleted)
        }

    @Test
    fun `a delete schedules a wake-up at its deadline before it is saved`() =
        runTest {
            val store = FullStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("undo.preferences_pb") })
            val wakes = mutableListOf<Long>()
            val undo = UndoDeletes(store, backgroundScope, { testScheduler.currentTime }, { wakes.add(it) }) { _, _ -> }
            advanceTimeBy(1000)
            store.full = true
            undo.start("photo", server, 8000)
            assertEquals(listOf(9000L), wakes)
            assertNull(undo.current())
        }

    @Test
    fun `the wake-up sends a delete whose app was killed during Undo, once`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("undo.preferences_pb") }
            val deleted = mutableListOf<String>()
            val killed = CoroutineScope(coroutineContext + Job())
            UndoDeletes(store, killed, { testScheduler.currentTime }, {}) { id, _ -> deleted.add(id) }.start("photo", server, 8000)
            advanceTimeBy(3000)
            killed.cancel()
            advanceTimeBy(5001)
            runCurrent()
            assertEquals(emptyList(), deleted)
            // The work the wake-up scheduled starts a new process, which makes its own UndoDeletes.
            val woken = UndoDeletes(store, backgroundScope, { testScheduler.currentTime }, {}) { id, _ -> deleted.add(id) }
            assertTrue(woken.settleDue())
            runCurrent()
            assertEquals(listOf("photo"), deleted)
            assertNull(woken.current())
        }

    @Test
    fun `the wake-up sends nothing after an Undo`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("undo.preferences_pb") }
            val deleted = mutableListOf<String>()
            val undo = UndoDeletes(store, backgroundScope, { testScheduler.currentTime }, {}) { id, _ -> deleted.add(id) }
            undo.start("photo", server, 8000)
            advanceTimeBy(2000)
            undo.undo("photo")
            assertTrue(undo.settleDue())
            advanceTimeBy(8000)
            runCurrent()
            assertEquals(emptyList(), deleted)
        }

    @Test
    fun `a wake-up before the deadline waits for it, and an Undo in the meantime wins`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("undo.preferences_pb") }
            val deleted = mutableListOf<String>()
            val undo = UndoDeletes(store, backgroundScope, { testScheduler.currentTime }, {}) { id, _ -> deleted.add(id) }
            undo.start("photo", server, 8000)
            val woken = async { undo.settleDue() }
            advanceTimeBy(4000)
            undo.undo("photo")
            advanceTimeBy(5000)
            runCurrent()
            assertTrue(woken.await())
            assertEquals(emptyList(), deleted)
        }

    /** Fails every write while [full] is set, as a phone with no space left does. */
    private class FullStore(
        private val inner: DataStore<Preferences>,
    ) : DataStore<Preferences> by inner {
        var full = false

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (full) throw IOException("No space left on device")
            return inner.updateData(transform)
        }
    }
}
