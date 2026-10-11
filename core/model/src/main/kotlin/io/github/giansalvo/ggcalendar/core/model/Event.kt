package io.github.giansalvo.ggcalendar.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/** Popup = notification; Email = sent by Google; Alarm = popup with a looping sound (at most 2 minutes), local to this app. */
enum class ReminderMethod { POPUP, EMAIL, ALARM }

@Serializable
data class Reminder(val method: ReminderMethod, val minutes: Int, val soundUri: String? = null)

@Serializable
data class Attendee(
    val email: String,
    val optional: Boolean = false,
    val displayName: String? = null,
    /** Google's responseStatus: needsAction, declined, tentative, accepted. */
    val response: String? = null,
)

enum class Availability { BUSY, FREE }

enum class Visibility { DEFAULT, PUBLIC, PRIVATE }

/**
 * Start and end of an event. Timed events are instants with the time zone they were created in (used to expand
 * recurrences across DST changes); all-day events are dates, with an exclusive end as in Google Calendar.
 */
sealed interface EventTime {
    data class Timed(val start: Instant, val end: Instant, val zoneId: String?) : EventTime
    data class AllDay(val startDay: LocalDate, val endDay: LocalDate) : EventTime
}

/** One occurrence to draw in a view: a single event, or one occurrence of a series. */
data class EventInstance(
    val eventId: String,
    val calendarId: String,
    val title: String,
    val location: String,
    val colorHex: String,
    val allDay: Boolean,
    val begin: Instant,
    val end: Instant,
    /** First day and exclusive last day, in the device time zone for timed events. */
    val startDay: LocalDate,
    val endDay: LocalDate,
    val hasMeet: Boolean,
    val hasAlarm: Boolean,
    val isRecurring: Boolean,
    val isBirthday: Boolean = false,
    /** The calendar can be written and the event is an ordinary one (not a birthday): it can be edited and dragged. */
    val canWrite: Boolean = false,
)
