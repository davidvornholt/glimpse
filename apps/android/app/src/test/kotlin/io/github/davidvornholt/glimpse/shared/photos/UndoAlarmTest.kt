package io.github.davidvornholt.glimpse.shared.photos

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The work that starts the app at an Undo deadline, queued in WorkManager's test database. */
@RunWith(RobolectricTestRunner::class)
// The test application, because Glimpse's own would build the whole app graph.
@Config(sdk = [Config.NEWEST_SDK], application = android.app.Application::class)
class UndoAlarmTest {
    @get:Rule val folder = TemporaryFolder()
    private val context = RuntimeEnvironment.getApplication()

    @BeforeTest
    fun startWorkManager() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    @Test
    fun `the newest deadline replaces an earlier one`() =
        runTest {
            val alarm = UndoAlarm(context) { 1_000 }
            alarm.wakeAt(9_000)
            alarm.wakeAt(4_000)
            val works = WorkManager.getInstance(context).getWorkInfosForUniqueWork(UndoAlarm.NAME).get()
            assertEquals(listOf(WorkInfo.State.ENQUEUED to 3_000L), works.map { it.state to it.initialDelayMillis })
        }

    @Test
    fun `a delete with no deadline wakes nothing and drops an earlier wake-up`() =
        runTest {
            val alarm = UndoAlarm(context) { 1_000 }
            alarm.wakeAt(9_000)
            alarm.wakeAt(Long.MAX_VALUE)
            val works = WorkManager.getInstance(context).getWorkInfosForUniqueWork(UndoAlarm.NAME).get()
            assertEquals(listOf(WorkInfo.State.CANCELLED), works.map { it.state })
        }

    @Test
    fun `a delete with no deadline waits for Undo`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("undo.preferences_pb") }
            val deleted = mutableListOf<String>()
            val undo =
                UndoDeletes(store, backgroundScope, { testScheduler.currentTime }, UndoAlarm(context)::wakeAt) { id, _ -> deleted.add(id) }
            undo.start("photo", "https://glimpse.example.com", Long.MAX_VALUE)
            assertEquals(PendingDelete("photo", "https://glimpse.example.com", Long.MAX_VALUE), undo.current())
            undo.undo("photo")
            assertNull(undo.current())
            assertEquals(emptyList(), deleted)
        }
}
