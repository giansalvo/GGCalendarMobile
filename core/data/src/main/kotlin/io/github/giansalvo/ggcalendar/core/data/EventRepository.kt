package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.model.Attendee
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** An event with its calendar and, for a modified occurrence, the series it belongs to. */
data class EventDetail(val event: EventEntity, val calendar: CalendarEntity, val series: EventEntity?) {
    /** A writable calendar, an ordinary event, and the organizer is this calendar (or guests may modify it). */
    val canWrite: Boolean
        get() = calendar.canWrite && event.eventType == "default" &&
            (event.organizerEmail == null || event.organizerEmail == calendar.remoteId || event.organizerEmail == calendar.accountEmail || event.guestsCanModify)
}

/** Events for the detail sheet and the editor; changes go through [EventWriter]. */
@Singleton
class EventRepository @Inject constructor(
    private val db: GGCalendarDatabase,
    val writer: EventWriter,
    val series: SeriesEditor,
    val contacts: ContactsSource,
) {
    fun observe(id: String): Flow<EventDetail?> =
        combine(db.eventDao().observe(id), db.calendarDao().observeAll()) { event, calendars -> event to calendars }
            .map { (event, calendars) ->
                event ?: return@map null
                val calendar = calendars.firstOrNull { it.id == event.calendarId } ?: return@map null
                EventDetail(event, calendar, event.seriesMasterId?.let { db.eventDao().get(it) })
            }

    /** The modified and cancelled occurrences of a series (for sharing and exporting it). */
    suspend fun exceptionsOf(masterId: String): List<EventEntity> = db.eventDao().getExceptions(masterId)

    /** Calendars where the user can create events, account by account in the order they were added. */
    val writableCalendars: Flow<List<CalendarEntity>> =
        combine(db.accountDao().observeAll(), db.calendarDao().observeAll()) { accounts, calendars ->
            val order = accounts.mapIndexed { i, a -> a.email to i }.toMap()
            calendars.filter { it.canWrite }.sortedBy { order[it.accountEmail] ?: Int.MAX_VALUE }
        }

    /** Where new events go: the primary calendar of the first account added, else any writable calendar. */
    suspend fun defaultCalendarId(): String? {
        val writable = db.calendarDao().observeAll().first().filter { it.canWrite }
        val accounts = db.accountDao().getAll().map { it.email }
        return accounts.firstNotNullOfOrNull { email -> writable.firstOrNull { it.accountEmail == email && it.isPrimary } }?.id
            ?: writable.firstOrNull()?.id
    }

    /** The guests of the stored events and the phone's contacts (if allowed), by address, for the editor. */
    suspend fun knownAttendees(): List<Attendee> {
        val json = Json { ignoreUnknownKeys = true }
        return (db.eventDao().allAttendees().flatMap { runCatching { json.decodeFromString<List<Attendee>>(it) }.getOrDefault(emptyList()) } + contacts.emails())
            .map { Attendee(it.email, displayName = it.displayName) }
            .groupBy { it.email.lowercase() }
            .map { (_, same) -> same.firstOrNull { it.displayName != null } ?: same.first() }
            .sortedBy { (it.displayName ?: it.email).lowercase() }
    }

    fun empty(): Flow<EventDetail?> = flowOf(null)
}
