package io.github.giansalvo.ggcalendar.feature.widget

import io.github.giansalvo.ggcalendar.core.model.EventInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AgendaModelTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 13)
    private val now = today.atTime(10, 0).atZone(rome).toInstant()

    private fun timed(title: String, day: LocalDate, from: Int, to: Int): EventInstance {
        val begin = day.atTime(from, 0).atZone(rome).toInstant()
        val end = day.atTime(to, 0).atZone(rome).toInstant()
        return event(title, false, begin, end, day, day.plusDays(1))
    }

    private fun allDay(title: String, from: LocalDate, to: LocalDate): EventInstance =
        event(title, true, from.atStartOfDay(rome).toInstant(), to.atStartOfDay(rome).toInstant(), from, to)

    private fun event(title: String, allDay: Boolean, begin: Instant, end: Instant, startDay: LocalDate, endDay: LocalDate) = EventInstance(
        eventId = title, calendarId = "c", title = title, location = "", colorHex = "#039BE5", allDay = allDay, begin = begin, end = end,
        startDay = startDay, endDay = endDay, hasMeet = false, hasAlarm = false, isRecurring = false,
    )

    @Test fun todayIsThereWithoutEvents() {
        val days = AgendaModel.build(listOf(timed("Corso", today.plusDays(5), 16, 20)), today, now)
        assertEquals(2, days.size)
        assertTrue(days[0].isToday)
        assertTrue(days[0].events.isEmpty())
        assertEquals(today.plusDays(5), days[1].day)
    }

    @Test fun anEventOfTodayGroupsUnderToday() {
        val days = AgendaModel.build(listOf(timed("Tardi", today, 17, 18), timed("Presto", today, 11, 12)), today, now)
        assertEquals(1, days.size)
        assertEquals(listOf("Presto", "Tardi"), days[0].events.map { it.title })
    }

    @Test fun anEventAlreadyOverIsLeftOut() {
        val days = AgendaModel.build(listOf(timed("Finito", today, 8, 9)), today, now)
        assertTrue(days.single().events.isEmpty())
    }

    @Test fun aMultiDayEventThatBeganEarlierShowsOnToday() {
        val days = AgendaModel.build(listOf(allDay("Viaggio", today.minusDays(2), today.plusDays(2))), today, now)
        assertEquals(today, days.single().day)
        assertEquals(listOf("Viaggio"), days.single().events.map { it.title })
    }

    @Test fun anAllDayEventEndingYesterdayIsLeftOut() {
        // the end day is exclusive: from the 11th to the 13th means the 11th and the 12th
        val days = AgendaModel.build(listOf(allDay("Passato", today.minusDays(2), today)), today, now)
        assertTrue(days.single().events.isEmpty())
    }

    @Test fun allDayFirstThenByTime() {
        val days = AgendaModel.build(listOf(timed("Ore", today, 12, 13), allDay("Giorno", today, today.plusDays(1))), today, now)
        assertEquals(listOf("Giorno", "Ore"), days.single().events.map { it.title })
    }

    @Test fun nothingBeyondTheLookahead() {
        val days = AgendaModel.build(listOf(timed("Lontano", today.plusDays(AgendaModel.LOOKAHEAD_DAYS + 1), 9, 10)), today, now)
        assertEquals(1, days.size)
    }

    @Test fun timeRange() {
        val f = DateTimeFormatter.ofPattern("HH:mm")
        assertEquals("16:00–20:00", AgendaModel.timeRange(timed("a", today, 16, 20), rome, f))
        assertNull(AgendaModel.timeRange(allDay("b", today, today.plusDays(1)), rome, f))
    }
}
