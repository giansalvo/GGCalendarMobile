package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.MonthDay
import javax.inject.Inject
import javax.inject.Singleton

/** A birthday of a contact: who, and which day of the year (the year of birth, if known, is not needed). */
data class ContactBirthday(val contactId: String, val name: String, val day: MonthDay)

/** Reading and matching of the birthdays of the phone's contacts; no Android classes, so it is tested on the JVM. */
object Birthdays {
    private val FULL = Regex("""^(?:\d{4}|-)-(\d{2})-(\d{2})""")
    private val COMPACT = Regex("""^\d{4}(\d{2})(\d{2})$""")

    /** `1980-05-17`, `--05-17` (no year) or `19800517`; null for anything else. */
    fun parse(text: String?): MonthDay? {
        val t = text?.trim() ?: return null
        val m = FULL.find(t) ?: COMPACT.find(t) ?: return null
        return runCatching { MonthDay.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()
    }

    /**
     * Contacts whose birthday is not already in the calendar as a Google birthday: Google's own birthday events
     * (from the contacts of the account) have the same day and a title that holds the name (or is the name).
     */
    fun withoutDuplicates(contacts: List<ContactBirthday>, google: List<Pair<MonthDay, String>>): List<ContactBirthday> =
        contacts.distinctBy { it.contactId }.filter { c ->
            val name = c.name.normalized()
            name.isNotEmpty() && google.none { (day, title) ->
                val t = title.normalized()
                day == c.day && t.isNotEmpty() && (t == name || t.contains(name) || name.contains(t))
            }
        }

    private fun String.normalized() = lowercase().trim()
}

/**
 * The "Birthdays" calendar made from the phone's contacts (optional, needs READ_CONTACTS): a local read-only calendar
 * with a yearly all-day event for each birthday. It is rebuilt at each start and when the setting changes; turning the
 * setting off, or losing the permission, removes it.
 */
@Singleton
class BirthdaySync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: GGCalendarDatabase,
    private val contacts: ContactsSource,
    private val prefs: AppPreferences,
    private val engine: SyncEngine,
) {
    /** One rebuild at a time: a slow one (many contacts) must not bring the calendar back after it was turned off. */
    private val lock = Mutex()

    suspend fun sync() = lock.withLock {
        if (!prefs.birthdaysEnabled.first() || !contacts.granted) {
            db.calendarDao().delete(CALENDAR_ID)
            return@withLock
        }
        val existingCalendar = db.calendarDao().get(CALENDAR_ID)
        db.calendarDao().upsert(
            CalendarEntity(
                id = CALENDAR_ID, accountEmail = null, remoteId = null, name = context.getString(R.string.birthdays_calendar),
                color = COLOR, accessRole = "reader", isVisible = existingCalendar?.isVisible ?: true, sortOrder = Int.MAX_VALUE,
            ),
        )
        val google = db.eventDao().birthdaysOutside(CALENDAR_ID).mapNotNull { e ->
            val day = e.startDay?.let { LocalDate.ofEpochDay(it) } ?: return@mapNotNull null
            MonthDay.from(day) to e.title
        }
        val year = LocalDate.now().year - 2
        val wanted = Birthdays.withoutDuplicates(contacts.birthdays(), google).associate { c ->
            // Starting two years back keeps the last year's birthday in the instances window; a 29 February starts in a leap year.
            val start = runCatching { c.day.atYear(year) }.getOrElse { c.day.atYear(year - year % 4) }
            val id = "birthday:${c.contactId}"
            id to EventEntity(
                id = id, calendarId = CALENDAR_ID, title = c.name, allDay = true,
                startDay = start.toEpochDay(), endDay = start.toEpochDay() + 1, rrule = "FREQ=YEARLY",
                availability = "FREE", reminders = emptyList(), eventType = "birthday", updatedAt = 0,
            )
        }
        val existing = db.eventDao().getForCalendar(CALENDAR_ID).associateBy { it.id }
        val gone = existing.keys - wanted.keys
        if (gone.isNotEmpty()) db.eventDao().deleteWithSeries(gone.toList())
        val changed = wanted.values.filter { existing[it.id] != it }
        if (changed.isNotEmpty()) {
            db.eventDao().upsert(changed)
            changed.forEach { engine.recompute(it.id) }
        }
    }

    companion object {
        const val CALENDAR_ID = "local:birthdays"
        private const val COLOR = "#D81B60"
    }
}
