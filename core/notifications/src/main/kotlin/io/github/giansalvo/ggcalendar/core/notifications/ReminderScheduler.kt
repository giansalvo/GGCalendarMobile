package io.github.giansalvo.ggcalendar.core.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.giansalvo.ggcalendar.core.data.ReminderCalculator
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers with AlarmManager the reminders due in the next 48 hours (plus the snoozed ones), one pending intent per
 * reminder key, and cancels the ones that are no longer due (event moved, deleted, reminder changed).
 * Alarms use `setAlarmClock`, which also fires in Doze; notifications use an exact alarm that is allowed while idle.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: GGCalendarDatabase,
    private val notifier: ReminderNotifier,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val store = context.getSharedPreferences("reminder_schedule", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private data class Slot(val at: Long, val alarm: Boolean)

    suspend fun reschedule(now: Long = System.currentTimeMillis()) = mutex.withLock {
        val dao = db.reminderDao()
        val horizon = now + Reminders.HORIZON_MILLIS
        val rows = dao.rows(now, horizon + Reminders.MAX_MINUTES_BEFORE * 60_000)
        val due = ReminderCalculator.due(rows, now, horizon)
        val fired = due.map { it.key }.chunked(500).flatMap { dao.fired(it) }.toSet()

        val wanted = LinkedHashMap<String, Slot>()
        due.filter { it.key !in fired }.forEach { wanted[it.key] = Slot(it.fireAt, it.reminder.method == ReminderMethod.ALARM) }
        // A snooze that came due while the phone was off rings at once.
        dao.snoozed().forEach { wanted[ReminderEngine.snoozeKey(it.id)] = Slot(maxOf(it.fireAt, now + 1_000), it.method == ReminderMethod.ALARM.name) }

        val previous = store.getStringSet(SCHEDULED, emptySet()).orEmpty()
        (previous - wanted.keys).forEach { alarms.cancel(pendingIntent(it)) }
        wanted.forEach { (key, slot) -> register(key, slot) }
        store.edit().putStringSet(SCHEDULED, wanted.keys.toSet()).apply()
    }

    /** The alarms that will ring in the next [days] days (snoozed ones included), soonest first. */
    suspend fun upcomingAlarms(now: Long = System.currentTimeMillis(), days: Int = 60): List<ActiveAlarm> {
        val dao = db.reminderDao()
        val until = now + days * 86_400_000L
        val rows = dao.rows(now, until + Reminders.MAX_MINUTES_BEFORE * 60_000)
        val due = ReminderCalculator.due(rows, now, until).filter { it.reminder.method == ReminderMethod.ALARM }
        val fired = due.map { it.key }.chunked(500).flatMap { dao.fired(it) }.toSet()
        val planned = due.filter { it.key !in fired }.map {
            ActiveAlarm(it.key, null, it.fireAt, it.row.eventId, it.row.beginUtc, it.row.title, it.reminder.minutes, snoozed = false, allDay = it.row.allDay)
        }
        val snoozed = dao.snoozed().filter { it.method == ReminderMethod.ALARM.name }.mapNotNull { s ->
            dao.row(s.eventId, s.beginUtc)?.let {
                ActiveAlarm(ReminderEngine.snoozeKey(s.id), s.id, maxOf(s.fireAt, now), it.eventId, it.beginUtc, it.title, s.minutes, snoozed = true, allDay = it.allDay)
            }
        }
        return (planned + snoozed).sortedBy { it.fireAt }.take(MAX_ACTIVE_ALARMS)
    }

    private fun register(key: String, slot: Slot) {
        val fire = pendingIntent(key)
        if (slot.alarm) {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(slot.at, openAppIntent()), fire)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
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

    /** What the alarm icon in the status bar opens. */
    private fun openAppIntent(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Whether notification reminders are exact (always before Android 12). */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private companion object {
        const val SCHEDULED = "keys"
        const val MAX_ACTIVE_ALARMS = 300
    }
}

/** An alarm that has not rung yet: when it rings (fireAt), for which occurrence, and how long before it starts. */
data class ActiveAlarm(
    /** Key of the reminder (DueReminder.key), or of the snooze. */
    val key: String,
    /** Id of the snooze row, for a snoozed alarm. */
    val snoozeId: Long?,
    val fireAt: Long,
    val eventId: String,
    val beginUtc: Long,
    val title: String,
    val minutesBefore: Int,
    val snoozed: Boolean,
    val allDay: Boolean,
)
