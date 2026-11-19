package io.github.davidvornholt.glimpse.shared.photos

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

            fun open(scope: CoroutineScope) = UndoDeletes(store, scope, { testScheduler.currentTime }) { id, _ -> deleted.add(id) }
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
            val undo = UndoDeletes(store, backgroundScope, { testScheduler.currentTime }) { id, _ -> deleted.add(id) }
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
                UndoDeletes(store, appScope, { testScheduler.currentTime }) { id, _ ->
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
}
