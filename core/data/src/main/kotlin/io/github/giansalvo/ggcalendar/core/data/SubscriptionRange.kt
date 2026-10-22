package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.InstanceEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * The days of a calendar read from a file that the app shows: from the first day to the last, both included. A side left empty
 * has no limit: from the first event, or up to the last one. The same rule serves the subscription to a public calendar and, when
 * it comes, the one-time import of a file.
 */
data class SubscriptionRange(val from: LocalDate? = null, val to: LocalDate? = null) {
    /** No day is cut off. */
    val isUnlimited: Boolean get() = from == null && to == null

    /** The last day is not before the first. */
    val isValid: Boolean get() = from == null || to == null || !to.isBefore(from)

    companion object {
        fun ofDays(from: Long?, to: Long?) = SubscriptionRange(from?.let(LocalDate::ofEpochDay), to?.let(LocalDate::ofEpochDay))
    }
}

/**
 * Decides which events of a file are kept for a [SubscriptionRange]. An event is kept if any of its days is inside the range. A
 * series is kept if at least one of its occurrences is inside, even when it began before the first day or goes on after the last;
 * its modified occurrences follow it. An occurrence whose series is not in the file (the public feeds of Google) is an event like
 * any other. [intersects] is also what the instances of a series are cut with, so that no day outside the range is ever drawn.
 */
object DateRangeFilter {
    /** How far a series without an end is looked at when the range has no last day: enough to say it has an occurrence. */
    private const val LOOK_AHEAD_YEARS = 5L
    private val EARLIEST = LocalDate.of(1900, 1, 1)

    fun keep(events: List<EventEntity>, range: SubscriptionRange, zone: ZoneId): List<EventEntity> {
        if (range.isUnlimited) return events
        val byMaster = events.filter { it.seriesMasterId != null }.groupBy { it.seriesMasterId }
        val keptMasters = HashSet<String>()
        for (e in events) {
            if (e.rrule != null && e.seriesMasterId == null && seriesHasDayInside(e, byMaster[e.id].orEmpty(), range, zone)) keptMasters += e.id
        }
        val masterIds = events.filter { it.rrule != null && it.seriesMasterId == null }.mapTo(HashSet()) { it.id }
        return events.filter { e ->
            when {
                e.seriesMasterId != null && e.seriesMasterId in masterIds -> e.seriesMasterId in keptMasters
                e.rrule != null -> e.id in keptMasters
                else -> RecurrenceExpander.single(e, zone)?.let { intersects(it, range, zone) } == true
            }
        }
    }

    private fun seriesHasDayInside(master: EventEntity, exceptions: List<EventEntity>, range: SubscriptionRange, zone: ZoneId): Boolean {
        val from = range.from ?: EARLIEST
        val to = range.to ?: from.plusYears(LOOK_AHEAD_YEARS)
        val window = RecurrenceExpander.Window(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
        if (RecurrenceExpander.expand(master, exceptions, window, zone).any { intersects(it, range, zone) }) return true
        return exceptions.any { ex -> RecurrenceExpander.single(ex, zone)?.let { intersects(it, range, zone) } == true }
    }

    /** One occurrence touches the range: all-day ones by their days, the others by their moments in the zone of the phone. */
    fun intersects(instance: InstanceEntity, range: SubscriptionRange, zone: ZoneId): Boolean {
        if (range.isUnlimited) return true
        if (instance.allDay) {
            val fromDay = range.from?.toEpochDay() ?: Long.MIN_VALUE
            val toDay = range.to?.toEpochDay() ?: Long.MAX_VALUE
            return instance.startDay <= toDay && instance.endDay > fromDay
        }
        val fromMs = range.from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
        val toMs = range.to?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
        return instance.beginUtc < toMs && (instance.endUtc > fromMs || instance.beginUtc >= fromMs)
    }

    fun clip(instances: List<InstanceEntity>, range: SubscriptionRange, zone: ZoneId): List<InstanceEntity> =
        if (range.isUnlimited) instances else instances.filter { intersects(it, range, zone) }
}
