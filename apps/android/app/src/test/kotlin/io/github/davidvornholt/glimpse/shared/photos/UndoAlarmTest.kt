package io.github.davidvornholt.glimpse.shared.photos

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The work that starts the app at an Undo deadline, queued in WorkManager's test database. */
@RunWith(RobolectricTestRunner::class)
// The test application, because Glimpse's own would build the whole app graph.
@Config(sdk = [Config.NEWEST_SDK], application = android.app.Application::class)
class UndoAlarmTest {
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
}
