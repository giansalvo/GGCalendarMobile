package io.github.giansalvo.ggcalendar.core.data

import androidx.room.withTransaction
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarKind
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calendars that live on this phone only: no account, no guests, no Meet, nothing sent to Google. Their events go
 * through [EventWriter] like the others, which skips the outbox for them.
 */
@Singleton
class LocalCalendars @Inject constructor(
    private val db: GGCalendarDatabase,
    private val reminders: ReminderHooks,
) {
    /** [reminderMinutes]: the setting of the default reminder at this moment, kept by the new calendar (it never follows the setting afterwards). */
    suspend fun create(name: String, colorHex: String, reminderMinutes: Int = DEFAULT_REMINDER_MINUTES, id: String = "local:" + UUID.randomUUID()): String {
        val order = db.calendarDao().maxSortOrder(CalendarKind.LOCAL) + 1
        db.calendarDao().upsert(
            CalendarEntity(
                id = id, accountEmail = null, remoteId = null, name = name.trim().take(MAX_NAME), color = colorHex,
                accessRole = "owner", isVisible = true, defaultReminders = AppPreferences.remindersFor(reminderMinutes),
                sortOrder = order, kind = CalendarKind.LOCAL,
            ),
        )
        return id
    }

    suspend fun update(id: String, name: String, colorHex: String) {
        val calendar = db.calendarDao().get(id) ?: return
        if (calendar.kind != CalendarKind.LOCAL) return
        db.calendarDao().setNameAndColor(id, name.trim().take(MAX_NAME), colorHex)
    }

    /** Removes the calendar with its events; snoozed reminders go too and the alarms are planned again without them. */
    suspend fun delete(id: String) {
        val calendar = db.calendarDao().get(id) ?: return
        if (calendar.kind != CalendarKind.LOCAL) return
        db.withTransaction {
            db.reminderDao().deleteSnoozedOfCalendar(id)
            db.calendarDao().delete(id)
        }
        reminders.changed()
    }

    companion object {
        const val MAX_NAME = 100
        const val DEFAULT_REMINDER_MINUTES = AppPreferences.DEFAULT_REMINDER_MINUTES
    }
}
