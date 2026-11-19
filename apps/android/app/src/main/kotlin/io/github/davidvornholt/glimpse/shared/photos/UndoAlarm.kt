package io.github.davidvornholt.glimpse.shared.photos

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import io.github.davidvornholt.glimpse.GlimpseApp
import java.util.concurrent.TimeUnit

/** Starts the app at an Undo deadline, so a delete reaches the server even when Android killed the app during Undo. */
class UndoAlarm(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val workManager = WorkManager.getInstance(context)

    /** Only the newest delete can be waiting for Undo, so its deadline replaces any earlier one. */
    suspend fun wakeAt(deadline: Long) {
        // Long.MAX_VALUE is how UndoDeletes.start marks an Undo that never runs out. WorkManager rejects a delay that
        // long, and there is nothing to wake for.
        if (deadline == Long.MAX_VALUE) {
            workManager.cancelUniqueWork(NAME).await()
            return
        }
        val request =
            OneTimeWorkRequestBuilder<UndoDeadlineWorker>()
                .setInitialDelay((deadline - now()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .build()
        workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request).await()
    }

    internal companion object {
        const val NAME = "undo-deadline"
    }
}

/** Settles the delete whose Undo time ran out; an Undo that came first leaves nothing to settle. */
class UndoDeadlineWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val undo = (applicationContext as GlimpseApp).graph.library.undo
        return if (undo.settleDue()) Result.success() else Result.retry()
    }
}
