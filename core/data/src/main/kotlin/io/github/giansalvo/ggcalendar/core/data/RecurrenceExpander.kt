package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.InstanceEntity
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Precomputes the occurrences of an event for the `instances` table. Series are expanded with lib-recur (full
 * RFC 5545 RRULE support), in the event's own time zone so that occurrences keep their local time across DST changes.
 */
object RecurrenceExpander {
    data class Window(val from: Instant, val to: Instant)

    private const val MAX_ITERATIONS = 100_000

    /**
     * Instances of [event]. For a series, [exceptions] are its modified or cancelled occurrences: their original
     * starts are skipped (each exception draws its own instance, see [single]), as are the EXDATEs.
     */
    fun expand(event: EventEntity, exceptions: List<EventEntity>, window: Window, zone: ZoneId): List<InstanceEntity> {
        if (event.isCancelled) return emptyList()
        val rrule = event.rrule ?: return listOfNotNull(single(event, zone))
        val rule = runCatching { RecurrenceRule(rrule, RecurrenceRule.RfcMode.RFC5545_LAX) }.getOrNull()
            ?: return listOfNotNull(single(event, zone))
        return if (event.allDay) expandAllDay(event, rule, exceptions, window, zone) else expandTimed(event, rule, exceptions, window, zone)
    }

    /** The instance of a non-repeating event or of a modified occurrence; null if cancelled or without dates. */
    fun single(event: EventEntity, zone: ZoneId): InstanceEntity? {
        if (event.isCancelled) return null
        return if (event.allDay) {
            val start = event.startDay ?: return null
            allDayInstance(event.id, LocalDate.ofEpochDay(start), (event.endDay ?: (start + 1)) - start, zone)
        } else {
            timedInstance(event.id, event.startUtc ?: return null, event.endUtc ?: return null, zone)
        }
    }

    private fun expandTimed(
        event: EventEntity,
        rule: RecurrenceRule,
        exceptions: List<EventEntity>,
        window: Window,
        zone: ZoneId,
    ): List<InstanceEntity> {
        val start = event.startUtc ?: return emptyList()
        val duration = (event.endUtc ?: start) - start
        val skip = event.exdates.mapNotNull { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }.toMutableSet()
        exceptions.mapNotNullTo(skip) { it.originalStartUtc }

        val tz = TimeZone.getTimeZone(event.timeZone ?: zone.id)
        val iterator = rule.iterator(DateTime(tz, start))
        val earliest = window.from.toEpochMilli() - duration
        if (earliest > start) iterator.fastForward(earliest)
        val out = mutableListOf<InstanceEntity>()
        var first = true
        var n = 0
        while (iterator.hasNext() && n++ < MAX_ITERATIONS) {
            val begin = iterator.nextMillis()
            // RFC 5545: DTSTART is always the first occurrence, even when it does not match the rule.
            if (first && begin > start && earliest <= start && start !in skip) out += timedInstance(event.id, start, start + duration, zone)
            first = false
            if (begin >= window.to.toEpochMilli()) break
            if (begin + duration <= window.from.toEpochMilli() || begin in skip) continue
            out += timedInstance(event.id, begin, begin + duration, zone)
        }
        return out
    }

    private fun expandAllDay(
        event: EventEntity,
        rule: RecurrenceRule,
        exceptions: List<EventEntity>,
        window: Window,
        zone: ZoneId,
    ): List<InstanceEntity> {
        val startDay = event.startDay ?: return emptyList()
        val days = (event.endDay ?: (startDay + 1)) - startDay
        val skip = event.exdates.mapNotNull { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }.toMutableSet()
        exceptions.mapNotNullTo(skip) { it.originalStartDay }

        val fromDay = window.from.atZone(zone).toLocalDate().toEpochDay()
        val toDay = window.to.atZone(zone).toLocalDate().toEpochDay()
        val s = LocalDate.ofEpochDay(startDay)
        val iterator = rule.iterator(DateTime(s.year, s.monthValue - 1, s.dayOfMonth))
        val out = mutableListOf<InstanceEntity>()
        var first = true
        var n = 0
        while (iterator.hasNext() && n++ < MAX_ITERATIONS) {
            val dt = iterator.nextDateTime()
            val day = LocalDate.of(dt.year, dt.month + 1, dt.dayOfMonth)
            if (first && day.toEpochDay() > startDay && startDay !in skip && startDay + days > fromDay) {
                out += allDayInstance(event.id, s, days, zone)
            }
            first = false
            val d = day.toEpochDay()
            if (d >= toDay) break
            if (d + days <= fromDay || d in skip) continue
            out += allDayInstance(event.id, day, days, zone)
        }
        return out
    }

    private fun timedInstance(eventId: String, begin: Long, end: Long, zone: ZoneId): InstanceEntity {
        val startDay = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate().toEpochDay()
        // Exclusive last day: an event ending at midnight does not spill into the next day.
        val endDay = if (end <= begin) startDay + 1 else Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate().toEpochDay() + 1
        return InstanceEntity(eventId, begin, end, startDay, endDay, allDay = false)
    }

    private fun allDayInstance(eventId: String, day: LocalDate, days: Long, zone: ZoneId): InstanceEntity {
        val end = day.plusDays(maxOf(days, 1))
        return InstanceEntity(
            eventId,
            day.atStartOfDay(zone).toInstant().toEpochMilli(),
            end.atStartOfDay(zone).toInstant().toEpochMilli(),
            day.toEpochDay(),
            end.toEpochDay(),
            allDay = true,
        )
    }
}
