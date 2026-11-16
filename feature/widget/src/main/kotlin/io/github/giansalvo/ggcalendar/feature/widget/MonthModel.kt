package io.github.giansalvo.ggcalendar.feature.widget

import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.core.model.Lane
import io.github.giansalvo.ggcalendar.core.model.Lanes
import io.github.giansalvo.ggcalendar.core.model.Span
import io.github.giansalvo.ggcalendar.core.model.isBar
import io.github.giansalvo.ggcalendar.core.model.weekStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** A week of the Month widget: its seven days, the pieces of events that fit and, per column, how many are left out ("+N"). */
/** [rows]: how many rows of events the week has (the last one also has the "+N"). */
data class MonthWeek(val days: List<LocalDate>, val pieces: List<Lane<EventInstance>>, val hidden: List<Int>, val rows: Int = 0)

/**
 * The room of a week for its rows of events: [availableDp] under the number of the days, with rows of [barDp] when they hold an
 * all-day or multi-day bar and of [smallDp], the smaller bars of the events of the day, when they do not.
 */
class LaneHeights(val availableDp: Float, val barDp: Float, val smallDp: Float)

data class MonthGrid(val month: YearMonth, val weeks: List<MonthWeek>) {
    val first: LocalDate get() = weeks.first().days.first()
    val after: LocalDate get() = weeks.last().days.last().plusDays(1)
}

/** What the Month widget draws. The layout of the bars is the one of the app (`Lanes` and `weekStart` of :core:model). */
object MonthModel {
    /** The most weeks a month has in the grid. */
    const val WEEKS = 6

    /**
     * How many weeks the grid of [month] has: those with at least one day of it (4 to 6). The first and the last one also have days
     * of the month before and after, which are drawn with their events.
     */
    fun weeksFor(month: YearMonth, firstDay: DayOfWeek): Int {
        val first = month.atDay(1)
        val lead = ChronoUnit.DAYS.between(weekStart(first, firstDay), first).toInt()
        return (lead + month.lengthOfMonth() + 6) / 7
    }

    /** First day of the grid of [month], and the day after its last one: the window of days to load. */
    fun window(month: YearMonth, firstDay: DayOfWeek): Pair<LocalDate, LocalDate> {
        val start = weekStart(month.atDay(1), firstDay)
        return start to start.plusDays(weeksFor(month, firstDay) * 7L)
    }

    /** Height of the [rows] first rows of [pieces]: a row with a bar of all-day or multi-day events is taller than one with events of the day. */
    private fun stackDp(pieces: List<Lane<EventInstance>>, rows: Int, heights: LaneHeights): Float =
        (0 until rows).sumOf { lane -> (if (pieces.any { it.lane == lane && it.span.item.isBar }) heights.barDp else heights.smallDp).toDouble() }.toFloat()

    /**
     * [slots]: how many rows of events each day has room for at most (0 when the widget is too small for any). With [heights] a week
     * keeps only the rows that fit in its height, which are more when they are rows of small bars.
     */
    fun build(month: YearMonth, instances: List<EventInstance>, firstDay: DayOfWeek, slots: Int, heights: LaneHeights? = null): MonthGrid {
        val (start, _) = window(month, firstDay)
        val weeks = (0 until weeksFor(month, firstDay)).map { w ->
            val first = start.plusDays(w * 7L)
            val end = first.plusDays(7)
            val days = (0 until 7).map { first.plusDays(it.toLong()) }
            val spans = instances
                .filter { it.startDay < end && it.endDay > first }
                .sortedWith(compareByDescending<EventInstance> { it.isBar }.thenBy { it.begin })
                .map {
                    Span(
                        it,
                        ChronoUnit.DAYS.between(first, maxOf(it.startDay, first)).toInt(),
                        ChronoUnit.DAYS.between(first, minOf(it.endDay, end)).toInt(),
                    )
                }
            val assigned = Lanes.assign(spans)
            var rows = slots
            var fitted = Lanes.fit(assigned, 7, rows)
            if (heights != null) {
                while (rows > 0 && stackDp(fitted.component1(), rows, heights) > heights.availableDp) fitted = Lanes.fit(assigned, 7, --rows)
            }
            MonthWeek(days, fitted.component1(), fitted.component2().toList(), rows)
        }
        return MonthGrid(month, weeks)
    }

    /** What a row (lane) of a week holds: a piece of an event, or the "+N" of a day, from column [start] up to (not including) [end]. */
    class LaneItem(val start: Int, val end: Int, val event: EventInstance?, val more: Int)

    /** The items of row [lane] of [week] from left to right; the last row of the day also has the "+N" of the days with events left out. */
    fun laneItems(week: MonthWeek, lane: Int): List<LaneItem> =
        (week.pieces.filter { it.lane == lane }.map { LaneItem(it.span.startCol, it.span.endCol, it.span.item, 0) } +
            if (lane == week.rows - 1) week.hidden.mapIndexedNotNull { col, n -> if (n > 0) LaneItem(col, col + 1, null, n) else null } else emptyList())
            .sortedBy { it.start }

    /** How many rows of events fit in a day cell of [cellHeightDp], after the number of the day and given each row's height. */
    fun slotsFor(cellHeightDp: Float, dayNumberDp: Float, rowDp: Float): Int =
        if (rowDp <= 0f) 0 else ((cellHeightDp - dayNumberDp) / rowDp).toInt().coerceAtLeast(0)
}
