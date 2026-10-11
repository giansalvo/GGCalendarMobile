package io.github.giansalvo.ggcalendar.core.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** When a reminder goes off and whether it is an alarm (rings, also in Doze) or a notification. */
data class ReminderSlot(val at: Long, val alarm: Boolean)

/**
 * Registers reminders with AlarmManager, one pending intent per key. It only needs the context and the
 * [DirectBootStore] (where it remembers the registered keys), so it works before the first unlock too.
 * Alarms use `setAlarmClock`, which also fires in Doze; notifications use an exact alarm that is allowed while idle.
 */
class AlarmRegistrar(private val context: Context, private val store: DirectBootStore) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Registers [wanted] and cancels the keys registered before that are no longer wanted. */
    fun sync(wanted: Map<String, ReminderSlot>) {
        (store.scheduledKeys() - wanted.keys).forEach { alarms.cancel(pendingIntent(it)) }
        wanted.forEach { (key, slot) -> register(key, slot) }
        store.setScheduledKeys(wanted.keys.toSet())
    }

    /** Adds one more alarm, remembered with the others. */
    fun add(key: String, slot: ReminderSlot) {
        register(key, slot)
        store.setScheduledKeys(store.scheduledKeys() + key)
    }

    private fun register(key: String, slot: ReminderSlot) {
        val fire = pendingIntent(key)
        if (slot.alarm) {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(slot.at, openAppIntent()), fire)
        } else if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.at, fire)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, slot.at, fire)
        }
    }

    private fun pendingIntent(key: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(Reminders.ACTION_FIRE)
            .setData(Uri.parse("ggcalendar://reminder/" + Uri.encode(key)))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** What the alarm icon in the status bar opens (before the first unlock the app cannot open: an empty intent). */
    private fun openAppIntent(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Whether notification reminders are exact (always before Android 12). */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
}
