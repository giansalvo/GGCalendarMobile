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

/** One event to export: a single event or a series master, with the exceptions of the series. */
class ExportItem(val event: EventEntity, val calendar: CalendarEntity, val exceptions: List<EventEntity>)

/** One occurrence to export in a table: its event, the series it belongs to (itself if single), calendar and times. */
class ExportRow(val event: EventEntity, val series: EventEntity, val calendar: CalendarEntity, val instance: io.github.giansalvo.ggcalendar.core.database.InstanceEntity)

class ExportOccurrences(val rows: List<ExportRow>)

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

    /**
     * Every event of [calendarIds] with an occurrence in the days [from, to] (both included), for exporting: each series
     * once, with its modified and cancelled occurrences.
     */
    suspend fun forExport(calendarIds: Collection<String>, from: java.time.LocalDate, to: java.time.LocalDate): List<ExportItem> {
        if (calendarIds.isEmpty()) return emptyList()
        val ids = db.eventDao().exportRootIds(calendarIds.toList(), from.toEpochDay(), to.toEpochDay() + 1)
        val calendars = db.calendarDao().observeAll().first().associateBy { it.id }
        // SQLite limits the number of variables of one query: read the events in chunks.
        return ids.chunked(500).flatMap { db.eventDao().getAll(it) }.mapNotNull { e ->
            val calendar = calendars[e.calendarId] ?: return@mapNotNull null
            ExportItem(e, calendar, if (e.rrule.isNullOrBlank()) emptyList() else db.eventDao().getExceptions(e.id))
        }.sortedWith(compareBy({ it.event.startUtc ?: (it.event.startDay ?: 0) * 86_400_000L }, { it.event.title }))
    }

    /**
     * Every occurrence of [calendarIds] in the days [from, to] (both included), one row each, for exporting as a table.
     * It is the user's own copy: private events are in, in full.
     */
    suspend fun occurrencesForExport(calendarIds: Collection<String>, from: java.time.LocalDate, to: java.time.LocalDate): ExportOccurrences {
        if (calendarIds.isEmpty()) return ExportOccurrences(emptyList())
        val instances = db.instanceDao().exportInstances(calendarIds.toList(), from.toEpochDay(), to.toEpochDay() + 1)
        val events = HashMap<String, EventEntity>()
        // SQLite limits the number of variables of one query: read the events in chunks.
        suspend fun load(ids: Collection<String>) = ids.distinct().filter { it !in events }.chunked(500).forEach { chunk -> db.eventDao().getAll(chunk).forEach { events[it.id] = it } }
        load(instances.map { it.eventId })
        load(instances.mapNotNull { events[it.eventId]?.seriesMasterId })
        val calendars = db.calendarDao().observeAll().first().associateBy { it.id }
        val rows = instances.mapNotNull { i ->
            val event = events[i.eventId] ?: return@mapNotNull null
            val master = event.seriesMasterId?.let { events[it] }
            val calendar = calendars[event.calendarId] ?: return@mapNotNull null
            ExportRow(event, master ?: event, calendar, i)
        }
        return ExportOccurrences(rows)
    }

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
