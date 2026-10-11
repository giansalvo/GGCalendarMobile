package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceExpanderTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val window = RecurrenceExpander.Window(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"))

    private fun at(text: String) = LocalDateTime.parse(text).atZone(rome).toInstant()

    private fun timed(start: String, end: String, rrule: String? = null, exdates: List<String> = emptyList()) = EventEntity(
        id = "m", calendarId = "c", startUtc = at(start).toEpochMilli(), endUtc = at(end).toEpochMilli(),
        timeZone = "Europe/Rome", rrule = rrule, exdates = exdates,
    )

    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(rome).toLocalDateTime().toString()

    @Test
    fun singleEventHasOneInstanceWithExclusiveEndDay() {
        val list = RecurrenceExpander.expand(timed("2026-10-12T23:00", "2026-10-13T00:00"), emptyList(), window, rome)
        assertEquals(1, list.size)
        assertEquals(LocalDate.of(2026, 10, 12).toEpochDay(), list[0].startDay)
        assertEquals(LocalDate.of(2026, 10, 13).toEpochDay(), list[0].endDay)
    }

    @Test
    fun weeklySeriesSkipsExdatesAndExceptions() {
        val master = timed(
            "2026-10-05T10:00", "2026-10-05T11:00", "FREQ=WEEKLY;BYDAY=MO;COUNT=5",
            exdates = listOf(at("2026-10-12T10:00").toString()),
        )
        val moved = EventEntity(id = "x", calendarId = "c", seriesMasterId = "m", originalStartUtc = at("2026-10-19T10:00").toEpochMilli())
        val starts = RecurrenceExpander.expand(master, listOf(moved), window, rome).map { local(it.beginUtc) }
        assertEquals(listOf("2026-10-05T10:00", "2026-10-26T10:00", "2026-11-02T10:00"), starts)
    }

    @Test
    fun occurrencesKeepLocalTimeAcrossDaylightSavingChange() {
        // Italy leaves summer time on 25 October 2026.
        val master = timed("2026-10-22T09:00", "2026-10-22T10:00", "FREQ=DAILY;COUNT=6")
        val starts = RecurrenceExpander.expand(master, emptyList(), window, rome).map { local(it.beginUtc) }
        assertEquals((22..27).map { "2026-10-${it}T09:00" }, starts)
    }

    @Test
    fun allDayYearlySeriesStaysInsideTheWindow() {
        val birthday = EventEntity(
            id = "b", calendarId = "c", allDay = true, rrule = "FREQ=YEARLY",
            startDay = LocalDate.of(1990, 3, 15).toEpochDay(), endDay = LocalDate.of(1990, 3, 16).toEpochDay(),
        )
        val list = RecurrenceExpander.expand(birthday, emptyList(), window, rome)
        assertEquals(listOf(LocalDate.of(2026, 3, 15).toEpochDay()), list.map { it.startDay })
        assertTrue(list.all { it.allDay && it.endDay == it.startDay + 1 })
    }

    @Test
    fun cancelledEventHasNoInstances() {
        val list = RecurrenceExpander.expand(timed("2026-10-12T10:00", "2026-10-12T11:00").copy(isCancelled = true), emptyList(), window, rome)
        assertTrue(list.isEmpty())
    }
}
