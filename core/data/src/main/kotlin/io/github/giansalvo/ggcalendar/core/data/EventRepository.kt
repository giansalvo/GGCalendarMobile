package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.model.Attendee
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** An event with its calendar and, for a modified occurrence, the series it belongs to. */
data class EventDetail(val event: EventEntity, val calendar: CalendarEntity, val series: EventEntity?) {
    val canWrite: Boolean get() = calendar.canWrite && event.eventType == "default"
}

/** Events for the detail sheet and the editor; changes go through [EventWriter]. */
@Singleton
class EventRepository @Inject constructor(
    private val db: GGCalendarDatabase,
    val writer: EventWriter,
) {
    fun observe(id: String): Flow<EventDetail?> =
        combine(db.eventDao().observe(id), db.calendarDao().observeAll()) { event, calendars -> event to calendars }
            .map { (event, calendars) ->
                event ?: return@map null
                val calendar = calendars.firstOrNull { it.id == event.calendarId } ?: return@map null
                EventDetail(event, calendar, event.seriesMasterId?.let { db.eventDao().get(it) })
            }

    /** Calendars where the user can create events. */
    val writableCalendars: Flow<List<CalendarEntity>> = db.calendarDao().observeAll().map { list -> list.filter { it.canWrite } }

    /** Every guest of the stored events, by address, for the editor's suggestions. */
    suspend fun knownAttendees(): List<Attendee> {
        val json = Json { ignoreUnknownKeys = true }
        return db.eventDao().allAttendees()
            .flatMap { runCatching { json.decodeFromString<List<Attendee>>(it) }.getOrDefault(emptyList()) }
            .groupBy { it.email.lowercase() }
            .map { (_, same) -> same.firstOrNull { it.displayName != null } ?: same.first() }
            .sortedBy { (it.displayName ?: it.email).lowercase() }
    }

    fun empty(): Flow<EventDetail?> = flowOf(null)
}
