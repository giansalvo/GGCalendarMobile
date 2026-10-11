package io.github.giansalvo.ggcalendar.feature.calendar

import io.github.giansalvo.ggcalendar.core.model.EventInstance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class GridLayoutTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 10, 9)

    // Intervals in minutes of the same day, as in the desktop tests (hours there).
    private fun run(vararg hours: Pair<Double, Double>) =
        OverlapLayout.place(hours.toList(), { (it.first * 60).toLong() }, { (it.second * 60).toLong() })

    @Test
    fun disjointEventsUseASingleColumn() {
        val r = run(9.0 to 10.0, 10.0 to 11.0)
        r.forEach { assertEquals(0 to 1, it.column to it.columnCount) }
    }

    @Test
    fun overlappingEventsAreSideBySide() {
        val r = run(16.75 to 18.25, 16.75 to 18.25)
        assertEquals(listOf(0, 1), r.map { it.column }.sorted())
        r.forEach { assertEquals(2, it.columnCount) }
    }

    @Test
    fun columnIsReusedInsideClusterAndClustersAreIndependent() {
        val r = run(9.0 to 12.0, 9.0 to 10.0, 10.0 to 11.0, 14.0 to 15.0)
        assertEquals(2, r.single { it.item == 9.0 to 10.0 }.columnCount)
        assertEquals(r.single { it.item == 9.0 to 10.0 }.column, r.single { it.item == 10.0 to 11.0 }.column)
        assertEquals(1, r.single { it.item == 14.0 to 15.0 }.columnCount)
    }

    @Test
    fun longerEventsTakeTheFirstLane() {
        val lanes = Lanes.assign(listOf(Span("short", 1, 2), Span("long", 0, 5), Span("other", 1, 3)))
        assertEquals(0, lanes.single { it.span.item == "long" }.lane)
        assertEquals(setOf(1, 2), lanes.filter { it.span.item != "long" }.map { it.lane }.toSet())
        // A lane is reused once free.
        assertEquals(1, Lanes.assign(listOf(Span("a", 0, 2), Span("b", 2, 4), Span("c", 0, 4))).single { it.span.item == "a" }.lane)
    }

    @Test
    fun fitCutsBarsWhereAColumnOverflows() {
        // Column 1 needs 3 rows but has 2: it shows lane 0 and "+2".
        val lanes = Lanes.assign(listOf(Span("bar", 0, 3), Span("x", 1, 2), Span("y", 1, 2), Span("z", 0, 1)))
        val (visible, more) = Lanes.fit(lanes, columns = 3, rows = 2)
        assertArrayEquals(intArrayOf(0, 2, 0), more)
        val bar = visible.filter { it.span.item == "bar" }
        assertEquals(listOf(0 to 3), bar.map { it.span.startCol to it.span.endCol })
        assertTrue(visible.any { it.span.item == "z" && it.span.startCol == 0 })
        assertTrue(visible.none { it.span.item == "x" || it.span.item == "y" })
    }

    @Test
    fun fitSplitsABarInAHiddenRow() {
        val lanes = listOf(
            Lane(Span("a", 0, 3), 0),
            Lane(Span("b", 0, 3), 1),
            Lane(Span("c", 1, 2), 2),
        )
        val (visible, more) = Lanes.fit(lanes, columns = 3, rows = 2)
        assertEquals(listOf(0 to 1, 2 to 3), visible.filter { it.span.item == "b" }.map { it.span.startCol to it.span.endCol })
        assertArrayEquals(intArrayOf(0, 2, 0), more)
    }

    @Test
    fun weekStartsOnTheLocaleFirstDay() {
        assertEquals(LocalDate.of(2026, 10, 5), weekStart(day, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 10, 4), weekStart(day, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 10, 5), weekStart(LocalDate.of(2026, 10, 5), DayOfWeek.MONDAY))
    }

    private fun timed(start: String, end: String): EventInstance {
        val b = LocalDateTime.parse(start).atZone(rome).toInstant()
        val e = LocalDateTime.parse(end).atZone(rome).toInstant()
        val startDay = b.atZone(rome).toLocalDate()
        return EventInstance(
            eventId = "e", calendarId = "c", title = "t", location = "", colorHex = "#000000", allDay = false,
            begin = b, end = e, startDay = startDay, endDay = e.minusMillis(1).atZone(rome).toLocalDate().plusDays(1),
            hasMeet = false, hasAlarm = false, isRecurring = false,
        )
    }

    @Test
    fun eventsCrossingMidnightAreSplitBetweenDays() {
        val e = timed("2026-10-09T22:00", "2026-10-10T01:30")
        assertEquals(22 * 60 to 24 * 60, e.minutesOn(day, rome))
        assertEquals(0 to 90, e.minutesOn(day.plusDays(1), rome))
        assertNull(e.minutesOn(day.plusDays(2), rome))
        assertTrue(!e.isBar)
    }

    @Test
    fun eventEndingAtMidnightStaysOnItsDay() {
        val e = timed("2026-10-09T23:00", "2026-10-10T00:00")
        assertEquals(23 * 60 to 24 * 60, e.minutesOn(day, rome))
        assertNull(e.minutesOn(day.plusDays(1), rome))
    }

    @Test
    fun wallClockMinutesOnDstDay() {
        // 25 October 2026: clocks go back at 03:00 in Rome.
        val e = timed("2026-10-25T13:00", "2026-10-25T14:00")
        assertEquals(13 * 60 to 14 * 60, e.minutesOn(LocalDate.of(2026, 10, 25), rome))
    }

    @Test
    fun longTimedEventsAndAllDayEventsAreBars() {
        assertTrue(timed("2026-10-09T10:00", "2026-10-10T10:00").isBar)
        val spans = barSpans(listOf(timed("2026-10-08T10:00", "2026-10-12T10:00")), day, 2)
        assertEquals(listOf(0 to 2), spans.map { it.startCol to it.endCol })
    }
}
