package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.ReminderRow
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod

/** A reminder of one occurrence, shown at [fireAt] (epoch millis). */
data class DueReminder(val row: ReminderRow, val reminder: Reminder, val fireAt: Long) {
    /** As in the desktop app: event, occurrence start, method and minutes. */
    val key: String get() = keyOf(row.eventId, row.beginUtc, reminder)

    companion object {
        fun keyOf(eventId: String, beginUtc: Long, reminder: Reminder) = "$eventId|$beginUtc|${reminder.method}|${reminder.minutes}"
    }
}

/**
 * Which reminders are due, ported from the desktop `ReminderCalculator`. Occurrences come from the `instances` table, so
 * exceptions and EXDATEs are already applied; all-day occurrences begin at local midnight, which is where Google counts
 * their "N minutes before" from.
 */
object ReminderCalculator {
    /** Popup and alarm reminders of [row]: its own, or the calendar's defaults (Google's "useDefault"). */
    fun remindersOf(row: ReminderRow): List<Reminder> {
        val own = row.reminders ?: row.defaultReminders.map {
            // ggAlarm can also mark default reminders as alarms; only "has an alarm" is stored for them.
            if (row.hasAlarm && it.method == ReminderMethod.POPUP) it.copy(method = ReminderMethod.ALARM) else it
        }
        return own.filter { it.method != ReminderMethod.EMAIL }.distinct()
    }

    /**
     * One copy per occurrence: an event in two accounts (an invitation between them) has the same iCalUID and start.
     * The copy with an alarm wins, then the organizer's.
     */
    fun distinctOccurrences(rows: List<ReminderRow>): List<ReminderRow> =
        rows.groupBy { (it.iCalUid ?: "id:${it.eventId}") to it.beginUtc }.values.map { copies ->
            copies.maxWith(
                compareBy<ReminderRow> { r -> remindersOf(r).any { it.method == ReminderMethod.ALARM } }
                    .thenBy { it.organizerEmail != null && (it.organizerEmail == it.accountEmail || it.organizerEmail == it.calendarRemoteId) }
                    .thenBy { remindersOf(it).isNotEmpty() },
            )
        }

    /** Declined invitations ring no reminder, as in Google Calendar. */
    private fun declined(row: ReminderRow): Boolean =
        row.attendees.any { it.email.equals(row.accountEmail, ignoreCase = true) && it.response == "declined" }

    /** Reminders firing in (after, until] whose occurrence has not ended at [after]. */
    fun due(rows: List<ReminderRow>, after: Long, until: Long): List<DueReminder> =
        distinctOccurrences(rows).asSequence()
            .filter { it.endUtc > after && !declined(it) }
            .flatMap { row -> remindersOf(row).map { DueReminder(row, it, row.beginUtc - it.minutes * 60_000L) } }
            .filter { it.fireAt > after && it.fireAt <= until }
            .sortedBy { it.fireAt }
            .toList()
}
