package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.model.Diag
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
    private val reminders: ReminderHooks,
    private val birthdays: BirthdaySync,
    private val subscriptions: SubscriptionService,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Diag.log { "worker begin attempt=$runAttemptCount" }
        engine.syncAll()
        runCatching { birthdays.sync() }
        runCatching { subscriptions.refreshDue() } // the subscriptions whose hour is up; a failure is noted on the calendar, not here
        reminders.changed()
        Diag.log { "worker end" }
        return Result.success()
    }
}

/** Sync every 15 minutes (the WorkManager minimum) and on demand, only with a network connection. */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext context: Context) {
    private val work = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow() {
        val builder = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network)
        // Before Android 12 expedited work runs as a foreground service and the worker would have to provide a notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val request = builder.build()
        // A change saved while a sync runs must still be pushed: queue another sync after the current one.
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val PERIODIC = "sync-periodic"
        const val NOW = "sync-now"
    }
}
