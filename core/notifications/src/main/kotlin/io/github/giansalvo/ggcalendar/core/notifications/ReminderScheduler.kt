package io.github.giansalvo.ggcalendar.core.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.data.ReminderCalculator
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.model.Diag
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers with AlarmManager the reminders due in the next 48 hours (plus the snoozed ones), one pending intent per
 * reminder key, and cancels the ones that are no longer due (event moved, deleted, reminder changed).
 * Every time it also writes the [Snapshot] that the direct boot layer reads while the phone is locked after a reboot.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val db: GGCalendarDatabase,
    private val prefs: AppPreferences,
    private val store: DirectBootStore,
    private val reconciler: JournalReconciler,
) {
    private val registrar = AlarmRegistrar(context, store)
    private val mutex = Mutex()

    suspend fun reschedule(now: Long = System.currentTimeMillis()) = mutex.withLock {
        // What the direct boot layer did while Room was closed comes first: shown reminders must not be planned again.
        reconciler.reconcile()
        val dao = db.reminderDao()
        val horizon = now + Reminders.HORIZON_MILLIS
        val rows = dao.rows(now, horizon + Reminders.MAX_MINUTES_BEFORE * 60_000)
        val due = ReminderCalculator.due(rows, now, horizon)
        val fired = due.map { it.key }.chunked(500).flatMap { dao.fired(it) }.toSet()

        val wanted = LinkedHashMap<String, ReminderSlot>()
        val entries = ArrayList<SnapshotEntry>()
        due.filter { it.key !in fired }.forEach {
            wanted[it.key] = ReminderSlot(it.fireAt, it.reminder.method == ReminderMethod.ALARM)
            entries += SnapshotEntry(it.key, it.fireAt, ReminderAlert.of(it))
        }
        // A snooze that came due while the phone was off rings at once.
        dao.snoozed().forEach { s ->
            val at = maxOf(s.fireAt, now + 1_000)
            val key = ReminderEngine.snoozeKey(s.id)
            wanted[key] = ReminderSlot(at, s.method == ReminderMethod.ALARM.name)
            val method = runCatching { ReminderMethod.valueOf(s.method) }.getOrDefault(ReminderMethod.POPUP)
            dao.row(s.eventId, s.beginUtc)?.let { entries += SnapshotEntry(key, at, ReminderAlert.of(it, Reminder(method, s.minutes))) }
        }

        // The snapshot goes first: if the process dies in between, the alarms are still restorable.
        store.setAlarmMinutes(prefs.alarmMinutes.first())
        store.setAlarmSound(prefs.alarmSound.first())
        store.writeSnapshot(Snapshot(now, entries))
        Diag.log {
            val cancelled = store.scheduledKeys() - wanted.keys
            "reschedule: ${rows.size} occurrences of visible calendars, ${wanted.size} alarms wanted, ${cancelled.size} cancelled; next: " +
                entries.sortedBy { it.fireAt }.take(5).joinToString("; ") { "'${it.alert.title}' at ${Diag.time(it.fireAt)}" }
        }
        registrar.sync(wanted)
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

    /** Whether notification reminders are exact (always before Android 12). */
    fun canScheduleExact(): Boolean = registrar.canScheduleExact()

    private companion object {
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
