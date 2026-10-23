package io.github.giansalvo.ggcalendar.core.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.model.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AlarmManager alarms (a reminder is due), the notification buttons, and the system events that cancel or shift
 * the registered alarms: reboot, app update, change of time or time zone.
 *
 * It is direct boot aware and has no injection: after a reboot, before the first unlock, Room and DataStore do not
 * exist yet and it works from the snapshot ([LightReminders]); once the phone is unlocked it uses the full layer
 * ([ReminderEngine]) through the Hilt entry point.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Diag.log { "receiver: ${intent.action} data=${intent.data} unlocked=${ReminderActions.isUnlocked(app)}" }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (ReminderActions.isUnlocked(app)) full(app, intent) else light(app, intent)
            } finally {
                pending.finish()
            }
        }
    }

    /** Before the first unlock: the snapshot and the journal only. */
    private fun light(context: Context, intent: Intent) {
        val light = LightReminders(context)
        when (intent.action) {
            Reminders.ACTION_FIRE -> intent.data?.lastPathSegment?.let { light.onAlarm(it) }
            Reminders.ACTION_SNOOZE -> ReminderAlert.from(intent)?.let {
                light.snooze(it, intent.getIntExtra(Reminders.EXTRA_SNOOZE_MINUTES, AppPreferences.DEFAULT_SNOOZE_MINUTES))
            }
            Reminders.ACTION_DISMISS -> ReminderAlert.from(intent)?.let { dismissAlert(context, it) }
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            -> light.restore()
        }
    }

    private suspend fun full(context: Context, intent: Intent) {
        val hilt = ReminderActions.entryPoint(context)
        val engine = hilt.engine()
        val scheduler = hilt.scheduler()
        hilt.notifier().createChannels()
        when (intent.action) {
            Reminders.ACTION_FIRE -> {
                intent.data?.lastPathSegment?.let { engine.onAlarm(it) }
                // The snapshot of the direct boot layer must not keep a reminder that has just been shown.
                scheduler.reschedule()
            }
            Reminders.ACTION_SNOOZE -> ReminderAlert.from(intent)?.let {
                engine.snooze(it, intent.getIntExtra(Reminders.EXTRA_SNOOZE_MINUTES, AppPreferences.DEFAULT_SNOOZE_MINUTES))
            }
            Reminders.ACTION_DISMISS -> ReminderAlert.from(intent)?.let { engine.dismiss(it) }
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                engine.restoreAfterBoot()
                scheduler.reschedule()
            }
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                // The days of the instances depend on the zone, and a change of zone also arrives as a change of time:
                // both wait for the instances to be rebuilt (it does nothing if they are up to date), then the
                // reminders follow the new instances.
                hilt.repository().refreshInstances()
                scheduler.reschedule()
            }
        }
    }
}
