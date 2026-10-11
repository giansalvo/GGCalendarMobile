package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The desktop's series cases for the three scopes. */
class SeriesOpsTest {
    private val rome = ZoneId.of("Europe/Rome")

    private fun ms(text: String) = LocalDateTime.parse(text).atZone(rome).toInstant().toEpochMilli()

    // Every Monday 10:00–11:00 from 5 October 2026.
    private val master = EventEntity(
        id = "m", calendarId = "c", remoteId = "g", title = "Lezione", rrule = "FREQ=WEEKLY;BYDAY=MO",
        startUtc = ms("2026-10-05T10:00"), endUtc = ms("2026-10-05T11:00"), timeZone = "Europe/Rome",
        exdates = listOf("2026-10-26T09:00:00Z"),
    )

    private fun occ(day: String) = Occurrence(ms("${day}T10:00"), ms("${day}T11:00"), LocalDate.parse(day).toEpochDay(), allDay = false)

    @Test
    fun onlyThisEventIsAnExceptionOfTheSeries() {
        val o = occ("2026-10-19")
        val e = SeriesOps.exception(master, o, master.copy(title = "Spostata", startUtc = ms("2026-10-19T15:00"), endUtc = ms("2026-10-19T16:00")))
        assertEquals("m", e.seriesMasterId)
        assertEquals(o.beginUtc, e.originalStartUtc)
        assertNull(e.rrule)
        assertNull(e.remoteId)
        assertEquals("Spostata", e.title)
    }

    @Test
    fun allEventsMovedToTuesdayShiftDaysExclusionsAndExceptions() {
        val o = occ("2026-10-19")
        val edited = master.copy(startUtc = ms("2026-10-20T12:00"), endUtc = ms("2026-10-20T13:00"))
        val child = EventEntity(id = "x", calendarId = "c", seriesMasterId = "m", originalStartUtc = ms("2026-10-12T10:00"))
        val r = SeriesOps.all(master, o, edited, listOf(child), rome)
        assertEquals(ms("2026-10-06T12:00"), r.master.startUtc)
        assertEquals(ms("2026-10-06T13:00"), r.master.endUtc)
        assertEquals("FREQ=WEEKLY;BYDAY=TU", r.master.rrule)
        assertEquals("g", r.master.remoteId)
        // One day and two hours later; 26 October is after the DST change, so its UTC time is 09:00.
        assertEquals(listOf("2026-10-27T11:00:00Z"), r.master.exdates)
        assertEquals(ms("2026-10-13T12:00"), r.children.single().originalStartUtc)
    }

    @Test
    fun allEventsWithOnlyTheTitleChangedKeepTheTimes() {
        val o = occ("2026-10-19")
        val r = SeriesOps.all(master, o, master.copy(title = "Nuovo", startUtc = o.beginUtc, endUtc = o.endUtc), emptyList(), rome)
        assertEquals(master.startUtc, r.master.startUtc)
        assertEquals(master.rrule, r.master.rrule)
        assertEquals(master.exdates, r.master.exdates)
        assertTrue(r.children.isEmpty())
    }

    @Test
    fun followingSplitsTheSeriesTheDayBefore() {
        val counted = master.copy(rrule = "FREQ=WEEKLY;BYDAY=MO;COUNT=10")
        val o = occ("2026-10-19")
        val edited = counted.copy(title = "Nuovo orario", startUtc = ms("2026-10-19T14:00"), endUtc = ms("2026-10-19T15:00"))
        val r = SeriesOps.following(counted, o, edited, before = 2, zone = rome)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261018T215959Z", r.master.rrule)
        assertTrue(r.master.exdates.isEmpty())
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=8", r.following.rrule)
        assertEquals(listOf("2026-10-26T09:00:00Z"), r.following.exdates)
        assertNull(r.following.remoteId)
        assertEquals(ms("2026-10-19T14:00"), r.following.startUtc)
    }

    @Test
    fun deletions() {
        assertEquals(master.exdates + "2026-10-12T08:00:00Z", SeriesOps.deleteThis(master, occ("2026-10-12")).exdates)
        assertNull(SeriesOps.deleteFollowing(master, occ("2026-10-05"), rome))
        assertEquals("FREQ=WEEKLY;BYDAY=MO;UNTIL=20261011T215959Z", SeriesOps.deleteFollowing(master, occ("2026-10-12"), rome)!!.rrule)
    }

    @Test
    fun allDaySeriesUseDates() {
        val birthday = EventEntity(id = "b", calendarId = "c", allDay = true, startDay = LocalDate.of(2026, 10, 19).toEpochDay(), endDay = LocalDate.of(2026, 10, 20).toEpochDay(), rrule = "FREQ=YEARLY")
        val o = Occurrence(0, 0, LocalDate.of(2027, 10, 19).toEpochDay(), allDay = true)
        assertEquals(listOf("2027-10-19"), SeriesOps.deleteThis(birthday, o).exdates)
        assertEquals("FREQ=YEARLY;UNTIL=20271018", SeriesOps.deleteFollowing(birthday, o, rome)!!.rrule)
        assertEquals(o.startDay, SeriesOps.exception(birthday, o, birthday).originalStartDay)
    }
}
