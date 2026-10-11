package io.github.giansalvo.ggcalendar.core.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.giansalvo.ggcalendar.core.data.ReminderHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Once a day: moves the 48-hour window of registered reminders forward, even if the app is never opened. */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: ReminderScheduler,
    private val engine: ReminderEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        engine.restoreAfterBoot()
        scheduler.reschedule()
        return Result.success()
    }
}

/** Called when the app process starts: channels, missed reminders, registration of the next ones, daily worker. */
@Singleton
class ReminderStartup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: ReminderNotifier,
    private val engine: ReminderEngine,
    private val scheduler: ReminderScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        notifier.createChannels()
        scope.launch {
            engine.restoreAfterBoot()
            scheduler.reschedule()
        }
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork("reminders-check") // an experiment with a 15-minute check, dropped: polling gives no guarantee
        val daily = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
        work.enqueueUniquePeriodicWork("reminders-daily", ExistingPeriodicWorkPolicy.KEEP, daily)
    }
}

/** Registers the reminders again after the data layer reports changed events (sync, local edits). */
@Singleton
class RescheduleHook @Inject constructor(private val scheduler: ReminderScheduler, private val engine: ReminderEngine) : ReminderHook {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun remindersChanged() {
        scope.launch {
            engine.restoreAfterBoot()
            scheduler.reschedule()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationsModule {
    @Binds
    @IntoSet
    abstract fun rescheduleHook(hook: RescheduleHook): ReminderHook
}
