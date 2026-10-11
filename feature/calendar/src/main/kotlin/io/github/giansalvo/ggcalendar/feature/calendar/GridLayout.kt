package io.github.giansalvo.ggcalendar.feature.calendar

import io.github.giansalvo.ggcalendar.core.model.EventInstance
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Pure layout logic of the calendar grids, kept free of Compose so that it runs in JVM unit tests. */

data class PlacedItem<T>(val item: T, val column: Int, val columnCount: Int)

object OverlapLayout {
    /**
     * Assigns each interval a column inside its cluster of mutually overlapping items, so that overlapping events are
     * drawn side by side (as in Google Calendar). Port of the desktop `OverlapLayout.Place`.
     */
    fun <T> place(items: Iterable<T>, start: (T) -> Long, end: (T) -> Long): List<PlacedItem<T>> {
        val sorted = items.sortedWith(compareBy<T> { start(it) }.thenByDescending { end(it) - start(it) })
        val result = ArrayList<PlacedItem<T>>(sorted.size)
        val cluster = mutableListOf<Pair<T, Int>>()
        val columnEnds = mutableListOf<Long>()
        var clusterEnd = Long.MIN_VALUE

        fun flush() {
            val count = columnEnds.size
            cluster.forEach { (item, column) -> result += PlacedItem(item, column, count) }
            cluster.clear()
            columnEnds.clear()
        }

        for (item in sorted) {
            val s = start(item)
            val e = end(item)
            if (cluster.isNotEmpty() && s >= clusterEnd) flush()

            var col = columnEnds.indexOfFirst { it <= s }
            if (col < 0) {
                columnEnds += e
                col = columnEnds.lastIndex
            } else {
                columnEnds[col] = e
            }

            cluster += item to col
            if (cluster.size == 1 || e > clusterEnd) clusterEnd = e
        }

        flush()
        return result
    }
}

/** A bar over the columns [startCol, endCol) of a row of days (the all-day strip, or a week of the month). */
data class Span<T>(val item: T, val startCol: Int, val endCol: Int)

data class Lane<T>(val span: Span<T>, val lane: Int)

object Lanes {
    /** Stacks bars into lanes: earliest and longest first, each into the first lane free at its start. */
    fun <T> assign(spans: List<Span<T>>): List<Lane<T>> {
        val sorted = spans.sortedWith(compareBy<Span<T>> { it.startCol }.thenByDescending { it.endCol - it.startCol })
        val laneEnds = mutableListOf<Int>()
        return sorted.map { span ->
            var lane = laneEnds.indexOfFirst { it <= span.startCol }
            if (lane < 0) {
                laneEnds += span.endCol
                lane = laneEnds.lastIndex
            } else {
                laneEnds[lane] = span.endCol
            }
            Lane(span, lane)
        }
    }

    /**
     * The visible part of [lanes] when each column has room for [rows] rows: if a column needs more, its last row
     * shows "+N" instead. Bars are cut where they cross such a column. Returns the visible pieces and, per column, N.
     */
    fun <T> fit(lanes: List<Lane<T>>, columns: Int, rows: Int): Pair<List<Lane<T>>, IntArray> {
        val needed = IntArray(columns)
        val count = IntArray(columns)
        lanes.forEach { l ->
            for (c in l.span.startCol until l.span.endCol) {
                needed[c] = maxOf(needed[c], l.lane + 1)
                count[c]++
            }
        }
        val limit = IntArray(columns) { if (needed[it] > rows) maxOf(rows - 1, 0) else rows }
        val shown = IntArray(columns)
        val visible = mutableListOf<Lane<T>>()
        lanes.forEach { l ->
            var c = l.span.startCol
            while (c < l.span.endCol) {
                if (l.lane >= limit[c]) {
                    c++
                    continue
                }
                val from = c
                while (c < l.span.endCol && l.lane < limit[c]) shown[c++]++
                visible += Lane(Span(l.span.item, from, c), l.lane)
            }
        }
        return visible to IntArray(columns) { count[it] - shown[it] }
    }
}

/** First day of the week containing [day]. */
fun weekStart(day: LocalDate, firstDay: DayOfWeek): LocalDate =
    day.minusDays(((day.dayOfWeek.value - firstDay.value + 7) % 7).toLong())

/** All-day events and events of 24 hours or more go in the strip above the hours (or as bars in the month view). */
val EventInstance.isBar: Boolean
    get() = allDay || Duration.between(begin, end) >= Duration.ofDays(1)

/** Bars of [instances] over the [days] days from [first]. */
fun barSpans(instances: List<EventInstance>, first: LocalDate, days: Int): List<Span<EventInstance>> {
    val last = first.plusDays(days.toLong())
    return instances.filter { it.isBar && it.startDay < last && it.endDay > first }.map {
        Span(
            it,
            ChronoUnit.DAYS.between(first, maxOf(it.startDay, first)).toInt(),
            ChronoUnit.DAYS.between(first, minOf(it.endDay, last)).toInt(),
        )
    }
}

/** The minutes [start, end) of [day] covered by a timed event; events crossing midnight are cut at the day's edges. */
fun EventInstance.minutesOn(day: LocalDate, zone: ZoneId): Pair<Int, Int>? {
    val dayStart = day.atStartOfDay(zone).toInstant()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
    val empty = begin == end
    if (begin >= dayEnd || end < dayStart || (end == dayStart && !empty)) return null
    // Minutes are counted on the wall clock, so 13:00 is at 13 * 60 also on the days when DST changes.
    val from = if (begin <= dayStart) 0 else begin.atZone(zone).toLocalTime().toSecondOfDay() / 60
    val to = if (end >= dayEnd) 24 * 60 else end.atZone(zone).toLocalTime().toSecondOfDay() / 60
    return from to maxOf(from, to)
}
