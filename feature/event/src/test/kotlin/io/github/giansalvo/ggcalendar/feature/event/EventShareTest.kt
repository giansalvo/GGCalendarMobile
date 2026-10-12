package io.github.giansalvo.ggcalendar.feature.event

import io.github.giansalvo.ggcalendar.core.data.EventDetail
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class EventShareTest {
    private val now = Instant.parse("2026-10-12T10:00:00Z")
    private val start = Instant.parse("2026-10-08T08:30:00Z") // 10:30 in Rome
    private val end = Instant.parse("2026-10-08T09:30:00Z")
    private val calendar = CalendarEntity(
        "me@x.com|me@x.com", "me@x.com", "me@x.com", "Me", "#1A73E8", "owner",
        defaultReminders = listOf(Reminder(ReminderMethod.POPUP, 30)),
    )

    private fun event(id: String = "e1", title: String = "Riunione", rrule: String? = null, uid: String? = "uid1@google.com", reminders: List<Reminder>? = null) = EventEntity(
        id = id, calendarId = calendar.id, iCalUid = uid, title = title, location = "Sala 1", startUtc = start.toEpochMilli(), endUtc = end.toEpochMilli(),
        timeZone = "Europe/Rome", rrule = rrule, reminders = reminders,
    )

    private fun instance(at: Instant = start) = EventInstance(
        "e1", calendar.id, "Riunione", "Sala 1", "#1A73E8", false, at, at.plusSeconds(3600),
        LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), hasMeet = false, hasAlarm = false, isRecurring = false,
    )

    private fun lines(ics: String) = ics.replace("\r\n ", "").split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun anEventThatDoesNotRepeatIsWrittenAsItIsWithTheCalendarsReminders() {
        val l = lines(EventShare.ics(EventDetail(event(), calendar, null), instance(), wholeSeries = true, exceptions = emptyList(), now = now))
        assertEquals(1, l.count { it == "BEGIN:VEVENT" })
        assertTrue("UID:uid1@google.com" in l)
        assertTrue("DTSTART:20261008T083000Z" in l)
        assertTrue("TRIGGER:-PT30M" in l)
        assertFalse(l.any { it.startsWith("RRULE") })
    }

    @Test
    fun theWholeSeriesHasItsRuleExcludedDatesAndModifiedOccurrences() {
        val master = event(rrule = "RRULE:FREQ=WEEKLY;BYDAY=TH").copy(exdates = listOf("2026-10-22T08:30:00Z"))
        val moved = event(id = "e2", title = "Riunione spostata", uid = null).copy(
            seriesMasterId = master.id, originalStartUtc = Instant.parse("2026-10-15T08:30:00Z").toEpochMilli(),
            startUtc = Instant.parse("2026-10-15T10:30:00Z").toEpochMilli(), endUtc = Instant.parse("2026-10-15T11:30:00Z").toEpochMilli(),
        )
        val cancelled = event(id = "e3", uid = null).copy(
            seriesMasterId = master.id, originalStartUtc = Instant.parse("2026-10-29T08:30:00Z").toEpochMilli(), isCancelled = true,
        )
        val l = lines(EventShare.ics(EventDetail(master, calendar, null), instance(), wholeSeries = true, exceptions = listOf(moved, cancelled), now = now))
        assertEquals(2, l.count { it == "BEGIN:VEVENT" })
        assertEquals(2, l.count { it == "UID:uid1@google.com" })
        assertTrue("DTSTART;TZID=Europe/Rome:20261008T103000" in l)
        assertTrue("RRULE:FREQ=WEEKLY;BYDAY=TH" in l)
        assertTrue("EXDATE;TZID=Europe/Rome:20261022T103000" in l)
        // After the end of daylight saving time (25 October) the same UTC instant is one hour earlier on the clock.
        assertTrue("EXDATE;TZID=Europe/Rome:20261029T093000" in l)
        assertTrue("RECURRENCE-ID;TZID=Europe/Rome:20261015T103000" in l)
        assertTrue("SUMMARY:Riunione spostata" in l)
    }

    @Test
    fun oneOccurrenceIsASeparateEventWithoutTheRule() {
        val master = event(rrule = "RRULE:FREQ=WEEKLY;BYDAY=TH").copy(exdates = listOf("2026-10-22T08:30:00Z"))
        val next = Instant.parse("2026-10-15T08:30:00Z")
        val l = lines(EventShare.ics(EventDetail(master, calendar, null), instance(next), wholeSeries = false, exceptions = emptyList(), now = now))
        assertEquals(1, l.count { it == "BEGIN:VEVENT" })
        assertTrue("UID:uid1@google.com-${next.toEpochMilli()}" in l)
        assertTrue("DTSTART:20261015T083000Z" in l)
        assertFalse(l.any { it.startsWith("RRULE") || it.startsWith("EXDATE") || it.startsWith("RECURRENCE-ID") })
    }

    @Test
    fun aModifiedOccurrenceSharedWithTheSeriesWritesTheSeries() {
        val master = event(rrule = "FREQ=DAILY;COUNT=3")
        val moved = event(id = "e2", uid = null).copy(seriesMasterId = master.id, originalStartUtc = start.toEpochMilli())
        val detail = EventDetail(moved, calendar, master)
        assertTrue(EventShare.isRecurring(detail))
        val l = lines(EventShare.ics(detail, instance(), wholeSeries = true, exceptions = listOf(moved), now = now))
        assertTrue("RRULE:FREQ=DAILY;COUNT=3" in l)
        assertTrue("RECURRENCE-ID;TZID=Europe/Rome:20261008T103000" in l)
    }

    @Test
    fun anAllDayEventKeepsItsDates() {
        val allDay = EventEntity(id = "a", calendarId = calendar.id, title = "Ferie", allDay = true, startDay = LocalDate.of(2026, 10, 8).toEpochDay(), endDay = LocalDate.of(2026, 10, 10).toEpochDay())
        val inst = EventInstance("a", calendar.id, "Ferie", "", "#000", true, start, end, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 10), hasMeet = false, hasAlarm = false, isRecurring = false)
        val l = lines(EventShare.ics(EventDetail(allDay, calendar, null), inst, wholeSeries = true, exceptions = emptyList(), now = now))
        assertTrue("DTSTART;VALUE=DATE:20261008" in l)
        assertTrue("DTEND;VALUE=DATE:20261010" in l)
        assertTrue("UID:a@ggcalendar" in l)
    }

    @Test
    fun theMailGoesToTheGuestsExceptMeAndRooms() {
        val guests = listOf(
            Attendee("me@x.com"), Attendee("Anna@b.it"), Attendee("anna@B.it"), Attendee("sala@resource.calendar.google.com"),
            Attendee("luca@c.it", optional = true), Attendee("not-an-address"),
        )
        val detail = EventDetail(event().copy(attendees = guests), calendar, null)
        assertEquals(listOf("Anna@b.it", "luca@c.it"), EventShare.mailRecipients(detail))
        assertTrue(EventShare.mailRecipients(EventDetail(event(), calendar, null)).isEmpty())
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("Riunione_di_lunedi.ics", EventShare.fileName("Riunione di lunedi"))
        assertEquals("ab.ics", EventShare.fileName("a/b"))
        assertEquals("evento.ics", EventShare.fileName("  //  "))
        assertTrue(EventShare.fileName("x".repeat(200)).length <= 64)
    }
}
