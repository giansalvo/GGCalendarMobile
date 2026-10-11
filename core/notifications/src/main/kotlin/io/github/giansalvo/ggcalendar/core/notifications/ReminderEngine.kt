package io.github.giansalvo.ggcalendar.core.notifications

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.data.DueReminder
import io.github.giansalvo.ggcalendar.core.data.ReminderCalculator
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.ReminderFiredEntity
import io.github.giansalvo.ggcalendar.core.database.ReminderSnoozedEntity
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happens when a reminder is due, ported from the desktop `ReminderScheduler`: the reminders already shown are
 * remembered (so a restart does not repeat them), snoozed ones come back, and the ones missed while the phone was off
 * are shown when the app or the phone starts again, if their event has not ended.
 */
@Singleton
class ReminderEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: GGCalendarDatabase,
    private val prefs: AppPreferences,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier,
) {
    private val dao get() = db.reminderDao()

    /** The AlarmManager alarm of [key] went off. */
    suspend fun onAlarm(key: String) {
        val now = System.currentTimeMillis()
        if (key.startsWith(SNOOZE_PREFIX)) {
            val snoozed = dao.snoozed().firstOrNull { it.id == key.removePrefix(SNOOZE_PREFIX).toLongOrNull() } ?: return
            fireSnoozed(snoozed, now)
            return
        }
        val (eventId, begin, method, minutes) = parse(key) ?: return
        if (dao.fired(listOf(key)).isNotEmpty()) return
        val row = dao.row(eventId, begin) ?: return
        val reminder = ReminderCalculator.remindersOf(row).firstOrNull { it.method == method && it.minutes == minutes } ?: return
        if (row.endUtc <= now) return
        dao.insertFired(ReminderFiredEntity(key, row.beginUtc - minutes * 60_000L))
        present(ReminderAlert.of(row, reminder))
    }

    /**
     * Restores the reminders after a reboot, even when the system did not deliver BOOT_COMPLETED (some phones hold it
     * back): the boot counter tells that the phone restarted since the last time. Cheap when nothing changed.
     */
    suspend fun restoreAfterBoot() {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (prefs.handledBootCount() == boot) return
        catchUp()
        if (boot >= 0) prefs.setHandledBootCount(boot)
    }

    /** Shows the reminders missed in the last 24 hours (only for events not over) and the snoozes that came due. */
    suspend fun catchUp() {
        val now = System.currentTimeMillis()
        val after = maxOf(prefs.reminderLastCheck() ?: now, now - CATCH_UP)
        val rows = dao.rows(now, now + Reminders.MAX_MINUTES_BEFORE * 60_000)
        val due = ReminderCalculator.due(rows, after, now)
        val fired = due.map { it.key }.chunked(500).flatMap { dao.fired(it) }.toSet()
        due.filter { it.key !in fired }.forEach { reminder -> fire(reminder) }
        dao.snoozed().filter { it.fireAt <= now }.forEach { fireSnoozed(it, now) }
        prefs.setReminderLastCheck(now)
        // Older keys can never be due again: checks never look further back than the catch-up window.
        dao.deleteFiredBefore(now - 2 * CATCH_UP)
    }

    private suspend fun fire(due: DueReminder) {
        dao.insertFired(ReminderFiredEntity(due.key, due.fireAt))
        present(ReminderAlert.of(due))
    }

    private suspend fun fireSnoozed(snoozed: ReminderSnoozedEntity, now: Long) {
        dao.deleteSnoozed(snoozed.id)
        val row = dao.row(snoozed.eventId, snoozed.beginUtc) ?: return
        if (row.endUtc <= now) return
        val method = runCatching { ReminderMethod.valueOf(snoozed.method) }.getOrDefault(ReminderMethod.POPUP)
        present(ReminderAlert.of(row, Reminder(method, snoozed.minutes)))
    }

    private fun present(alert: ReminderAlert) {
        if (alert.isAlarm) {
            if (!AlarmService.start(context, alert)) notifier.showPopup(alert)
        } else {
            notifier.showPopup(alert)
        }
    }

    /** "Snooze": the reminder comes back in [minutes], also after a restart. */
    suspend fun snooze(alert: ReminderAlert, minutes: Int) {
        dao.insertSnoozed(
            ReminderSnoozedEntity(
                fireAt = System.currentTimeMillis() + minutes * 60_000L,
                eventId = alert.eventId, beginUtc = alert.beginUtc, method = alert.method.name, minutes = alert.minutes,
            ),
        )
        dismiss(alert)
        scheduler.reschedule()
    }

    /**
     * Closes an alarm before it rings (the event stays): a planned one is marked as shown, so that it is neither
     * registered again nor caught up; a snooze is forgotten. The alarm registered with AlarmManager is cancelled.
     */
    suspend fun skip(alarm: ActiveAlarm) {
        val snoozeId = alarm.snoozeId
        if (snoozeId != null) dao.deleteSnoozed(snoozeId) else dao.insertFired(ReminderFiredEntity(alarm.key, alarm.fireAt))
        scheduler.reschedule()
    }

    /** The user closed the reminder: its notification goes away and, for an alarm, the ringing stops. */
    fun dismiss(alert: ReminderAlert) {
        notifier.cancel(alert)
        if (alert.isAlarm) AlarmService.stop(alert.key)
    }

    companion object {
        private const val SNOOZE_PREFIX = "snooze|"
        private const val CATCH_UP = 24L * 60 * 60 * 1000

        fun snoozeKey(id: Long) = SNOOZE_PREFIX + id

        private data class Parsed(val eventId: String, val begin: Long, val method: ReminderMethod, val minutes: Int)

        private fun parse(key: String): Parsed? {
            val parts = key.split('|')
            if (parts.size != 4) return null
            return Parsed(
                parts[0],
                parts[1].toLongOrNull() ?: return null,
                runCatching { ReminderMethod.valueOf(parts[2]) }.getOrNull() ?: return null,
                parts[3].toIntOrNull() ?: return null,
            )
        }

    }
}
