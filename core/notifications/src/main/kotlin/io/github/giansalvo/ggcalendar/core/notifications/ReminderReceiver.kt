package io.github.giansalvo.ggcalendar.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.data.CalendarRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AlarmManager alarms (a reminder is due), the notification buttons, and the system events that cancel or shift
 * the registered alarms: reboot, app update, change of time or time zone.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var engine: ReminderEngine

    @Inject lateinit var scheduler: ReminderScheduler

    @Inject lateinit var notifier: ReminderNotifier

    @Inject lateinit var repository: CalendarRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                handle(intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(intent: Intent) {
        notifier.createChannels()
        when (intent.action) {
            Reminders.ACTION_FIRE -> intent.data?.lastPathSegment?.let { engine.onAlarm(it) }
            Reminders.ACTION_SNOOZE -> ReminderAlert.from(intent)?.let {
                engine.snooze(it, intent.getIntExtra(Reminders.EXTRA_SNOOZE_MINUTES, AppPreferences.DEFAULT_SNOOZE_MINUTES))
            }
            Reminders.ACTION_DISMISS -> ReminderAlert.from(intent)?.let { engine.dismiss(it) }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                engine.restoreAfterBoot()
                scheduler.reschedule()
            }
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // The days of the instances depend on the zone; the reminders follow the new instances.
                repository.refreshInstances()
                scheduler.reschedule()
            }
            Intent.ACTION_TIME_CHANGED -> scheduler.reschedule()
        }
    }
}
