package io.github.giansalvo.ggcalendar.feature.widget

import io.github.giansalvo.ggcalendar.core.model.EventInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class MonthModelTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val november = YearMonth.of(2026, 11)

    private fun allDay(title: String, from: LocalDate, to: LocalDate) = EventInstance(
        eventId = title, calendarId = "c", title = title, location = "", colorHex = "#039BE5", allDay = true,
        begin = from.atStartOfDay(rome).toInstant(), end = to.atStartOfDay(rome).toInstant(), startDay = from, endDay = to,
        hasMeet = false, hasAlarm = false, isRecurring = false,
    )

    private fun timed(title: String, day: LocalDate, hour: Int) = EventInstance(
        eventId = title, calendarId = "c", title = title, location = "", colorHex = "#039BE5", allDay = false,
        begin = day.atTime(hour, 0).atZone(rome).toInstant(), end = day.atTime(hour + 1, 0).atZone(rome).toInstant(),
        startDay = day, endDay = day.plusDays(1), hasMeet = false, hasAlarm = false, isRecurring = false,
    )

    @Test fun theGridHasSixWeeksFromTheWeekOfThe1st() {
        val grid = MonthModel.build(november, emptyList(), DayOfWeek.MONDAY, 3)
        assertEquals(6, grid.weeks.size)
        // 1 November 2026 is a Sunday: with Monday first the grid starts on 26 October
        assertEquals(LocalDate.of(2026, 10, 26), grid.first)
        assertEquals(LocalDate.of(2026, 12, 7), grid.after)
        assertTrue(grid.weeks.all { it.days.size == 7 })
    }

    @Test fun theGridFollowsTheFirstDayOfTheWeek() {
        assertEquals(LocalDate.of(2026, 11, 1), MonthModel.build(november, emptyList(), DayOfWeek.SUNDAY, 3).first)
    }

    @Test fun aBarCrossesTheDaysOfItsWeek() {
        val bar = allDay("Fiera", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 13))
        val piece = MonthModel.build(november, listOf(bar), DayOfWeek.MONDAY, 3).weeks[2].pieces.single()
        assertEquals(0, piece.span.startCol)
        assertEquals(4, piece.span.endCol)
    }

    @Test fun theFirstAndLastWeekAlsoHaveTheDaysOfTheNeighbouringMonths() {
        // 26 October to 1 November is the first week of the grid: only the 1st of November is of the month
        val events = listOf(allDay("Cambia l'ora", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)), timed("Cena", LocalDate.of(2026, 10, 30), 20))
        val week = MonthModel.build(november, events, DayOfWeek.MONDAY, 3).weeks[0]
        assertEquals(LocalDate.of(2026, 10, 26), week.days.first())
        assertEquals(setOf(0 to 6, 4 to 5), week.pieces.map { it.span.startCol to it.span.endCol }.toSet())
        // and the last one, 30 November to 6 December
        val last = MonthModel.build(november, listOf(timed("Cena", LocalDate.of(2026, 12, 2), 20)), DayOfWeek.MONDAY, 3).weeks.last()
        assertEquals(LocalDate.of(2026, 12, 6), last.days.last())
        assertEquals(1, last.pieces.size)
    }

    @Test fun aMonthHasOnlyTheWeeksItNeeds() {
        assertEquals(6, MonthModel.weeksFor(november, DayOfWeek.MONDAY)) // starts on a Sunday
        assertEquals(5, MonthModel.weeksFor(november, DayOfWeek.SUNDAY))
        assertEquals(4, MonthModel.weeksFor(YearMonth.of(2027, 2), DayOfWeek.MONDAY)) // 28 days from a Monday
        val window = MonthModel.window(YearMonth.of(2027, 2), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2027, 2, 1) to LocalDate.of(2027, 3, 1), window)
    }

    @Test fun aBarContinuesIntoTheNextWeek() {
        val bar = allDay("Viaggio", LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 11))
        val weeks = MonthModel.build(november, listOf(bar), DayOfWeek.MONDAY, 3).weeks
        assertEquals(listOf(4, 7), weeks[1].pieces.single().span.let { listOf(it.startCol, it.endCol) })
        assertEquals(listOf(0, 2), weeks[2].pieces.single().span.let { listOf(it.startCol, it.endCol) })
    }

    @Test fun whenThereIsNoRoomTheLastRowSaysHowManyAreLeftOut() {
        val day = LocalDate.of(2026, 11, 4)
        val events = (8..12).map { timed("E$it", day, it) }
        val week = MonthModel.build(november, events, DayOfWeek.MONDAY, 3).weeks[1]
        val wednesday = 2
        assertEquals(3, week.hidden[wednesday]) // 5 events in 3 rows: two shown and "+3"
        assertEquals(2, week.pieces.size)
    }

    @Test fun withNoSlotsNothingIsDrawn() {
        val events = listOf(timed("E", LocalDate.of(2026, 11, 4), 9))
        assertTrue(MonthModel.build(november, events, DayOfWeek.MONDAY, 0).weeks[1].pieces.isEmpty())
    }

    @Test fun smallBarsOfTheEventsOfTheDayLeaveRoomForMoreRows() {
        val timedEvents = (0 until 6).map { timed("t$it", LocalDate.of(2026, 11, 3), 8 + it) }
        val bars = (0 until 6).map { allDay("b$it", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4)) }
        // 60dp under the day number: rows of 11dp (events of the day) are 5, rows of 13dp (bars) are 4
        val heights = LaneHeights(availableDp = 60f, barDp = 13f, smallDp = 11f)
        val week = { events: List<EventInstance> -> MonthModel.build(november, events, DayOfWeek.MONDAY, 8, heights).weeks[1].rows }
        assertEquals(5, week(timedEvents))
        assertEquals(4, week(bars))
    }

    @Test fun slotsFor() {
        assertEquals(3, MonthModel.slotsFor(cellHeightDp = 66f, dayNumberDp = 20f, rowDp = 15f))
        assertEquals(0, MonthModel.slotsFor(cellHeightDp = 10f, dayNumberDp = 20f, rowDp = 15f))
    }
}
