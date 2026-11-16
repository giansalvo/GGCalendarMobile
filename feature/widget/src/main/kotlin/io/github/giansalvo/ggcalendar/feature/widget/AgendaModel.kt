package io.github.giansalvo.ggcalendar.feature.widget

import io.github.giansalvo.ggcalendar.core.model.EventInstance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A day of the Agenda widget with what happens on it (today is always there, even without events). */
data class AgendaDay(val day: LocalDate, val isToday: Boolean, val events: List<EventInstance>)

/** What the Agenda widget lists: the next events grouped by day. Pure code, so that it is tested without Android. */
object AgendaModel {
    /** Days after today the widget looks at, and the most events it keeps (a widget is small; the full list is in the app). */
    const val LOOKAHEAD_DAYS = 60L
    const val MAX_EVENTS = 40

    /**
     * Events from [today]: one that began earlier and goes on shows on today; one already over today is left out.
     * [now] decides which events of today are over.
     */
    fun build(instances: List<EventInstance>, today: LocalDate, now: Instant): List<AgendaDay> {
        val last = today.plusDays(LOOKAHEAD_DAYS)
        val byTime = compareByDescending<EventInstance> { it.allDay }.thenBy { it.begin }.thenBy { it.title }
        val upcoming = instances
            .filter { it.endDay > today && it.startDay <= last && (it.allDay || it.end >= now) }
            .sortedWith(byTime)
            .take(MAX_EVENTS)
        val byDay = upcoming.groupBy { maxOf(it.startDay, today) }
            .toSortedMap()
            .map { (day, events) -> AgendaDay(day, day == today, events.sortedWith(byTime)) }
        return if (byDay.firstOrNull()?.isToday == true) byDay else listOf(AgendaDay(today, true, emptyList())) + byDay
    }

    /** "16:00–20:00" for a timed event, null for an all-day one. */
    fun timeRange(event: EventInstance, zone: ZoneId, format: DateTimeFormatter): String? {
        if (event.allDay) return null
        val start = format.format(event.begin.atZone(zone))
        if (event.end == event.begin) return start
        return start + "–" + format.format(event.end.atZone(zone))
    }
}
