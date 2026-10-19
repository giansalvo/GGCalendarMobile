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
    /** Google accepts reminders up to four weeks before an event. */
    const val MAX_MINUTES_BEFORE = 40_320L

    /** How far back the missed reminders are looked for (after a restart, or when a calendar is shown again). */
    const val CATCH_UP_MILLIS = 24L * 60 * 60 * 1000

    /** Popup and alarm reminders of [row]: its own, or the calendar's defaults (Google's "useDefault"). */
    fun remindersOf(row: ReminderRow): List<Reminder> {
        val own = row.reminders ?: row.defaultReminders.map {
            // ggAlarm can also mark default reminders as alarms; only "has an alarm" is stored for them.
            if (row.hasAlarm && it.method == ReminderMethod.POPUP) it.copy(method = ReminderMethod.ALARM) else it
        }
        return own.filter { it.method != ReminderMethod.EMAIL }.distinct()
    }

    /** Declined invitations ring no reminder, as in Google Calendar. */
    private fun declined(row: ReminderRow): Boolean =
        row.attendees.any { it.email.equals(row.accountEmail, ignoreCase = true) && it.response == "declined" }

    /**
     * Reminders firing in (after, until] whose occurrence has not ended at [after].
     *
     * An event in two accounts (an invitation between them) has one copy per account, with the same iCalUID and start,
     * and each copy has its own reminders: all of them ring. Only identical ones (same occurrence, same method, same
     * time) are collapsed into one; then the organizer's copy is the one that is shown.
     */
    fun due(rows: List<ReminderRow>, after: Long, until: Long): List<DueReminder> =
        rows.asSequence()
            .filter { it.endUtc > after && !declined(it) }
            .flatMap { row -> remindersOf(row).map { DueReminder(row, it, row.beginUtc - it.minutes * 60_000L) } }
            .filter { it.fireAt > after && it.fireAt <= until }
            .groupBy { Triple(it.row.iCalUid ?: "id:${it.row.eventId}", it.row.beginUtc, it.reminder.method to it.reminder.minutes) }
            .values
            .map { copies -> copies.maxWith(compareBy<DueReminder> { organizers(it.row) }) }
            .sortedBy { it.fireAt }

    private fun organizers(row: ReminderRow) = row.organizerEmail != null && (row.organizerEmail == row.accountEmail || row.organizerEmail == row.calendarRemoteId)
}
