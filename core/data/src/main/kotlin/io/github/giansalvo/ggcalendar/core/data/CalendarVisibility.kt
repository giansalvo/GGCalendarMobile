package io.github.giansalvo.ggcalendar.core.data

import androidx.room.withTransaction
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.ReminderFiredEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows or hides a calendar, and with it its reminders and alarms: a hidden calendar neither rings nor wakes the phone.
 * Hiding forgets its snoozes. Showing again must not ring what went off while it was hidden, so the reminders already
 * past are marked as shown first; the ones still to come are registered again by the scheduler. Notifications already
 * on screen are left to the user.
 */
@Singleton
class CalendarVisibility @Inject constructor(private val db: GGCalendarDatabase) {
    suspend fun set(calendarId: String, visible: Boolean, now: Long = System.currentTimeMillis()) = db.withTransaction {
        val reminders = db.reminderDao()
        if (visible) {
            // Before the flag changes: afterwards a catch-up could already see them. Events over are not caught up anyway.
            val rows = reminders.rowsOfCalendar(calendarId, now, now + ReminderCalculator.MAX_MINUTES_BEFORE * 60_000)
            ReminderCalculator.due(rows, now - ReminderCalculator.CATCH_UP_MILLIS, now).forEach {
                reminders.insertFired(ReminderFiredEntity(it.key, it.fireAt))
            }
        } else {
            reminders.deleteSnoozedOfCalendar(calendarId)
        }
        db.calendarDao().setVisible(calendarId, visible)
    }
}
