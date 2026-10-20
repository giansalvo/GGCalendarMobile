package io.github.giansalvo.ggcalendar.feature.event

import io.github.giansalvo.ggcalendar.core.data.EventDetail
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import io.github.giansalvo.ggcalendar.core.model.IcsWriter
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

    @Test
    fun theExportOfMyCalendarsHasEveryEventInFullWithItsVisibilityAndStatus() {
        val calendar = calendar
        fun export(e: EventEntity, exceptions: List<EventEntity> = emptyList()) =
            lines(IcsWriter.write(EventShare.seriesEvents(e, calendar, exceptions, forExport = true), now))
        // Private, busy or free: the same details as any other event, marked private, with its status.
        val privateBusy = export(event("p").copy(visibility = "PRIVATE", availability = "BUSY", title = "Medico", location = "Studio", description = "dettagli"))
        assertTrue("CLASS:PRIVATE" in privateBusy)
        assertTrue("TRANSP:OPAQUE" in privateBusy)
        assertTrue("SUMMARY:Medico" in privateBusy)
        assertTrue("LOCATION:Studio" in privateBusy)
        assertTrue("DESCRIPTION:dettagli" in privateBusy)
        val privateFree = export(event("q").copy(visibility = "PRIVATE", availability = "FREE", title = "Segreto"))
        assertTrue("SUMMARY:Segreto" in privateFree)
        assertTrue("CLASS:PRIVATE" in privateFree)
        assertTrue("TRANSP:TRANSPARENT" in privateFree)
        val publicFree = export(event("r").copy(visibility = "PUBLIC", availability = "FREE", title = "Festa"))
        assertTrue("CLASS:PUBLIC" in publicFree)
        assertTrue("TRANSP:TRANSPARENT" in publicFree)
        // An event that follows its calendar writes no class.
        assertFalse(export(event("s").copy(visibility = "DEFAULT")).any { it.startsWith("CLASS") })
        // A private series keeps its modified occurrences.
        val series = event("t", rrule = "FREQ=DAILY;COUNT=3").copy(visibility = "PRIVATE", title = "Terapia")
        val modified = event("t|x").copy(title = "Terapia spostata", seriesMasterId = "t", originalStartUtc = start.plusSeconds(86_400).toEpochMilli(), visibility = "PRIVATE")
        val withException = export(series, listOf(modified))
        assertTrue("SUMMARY:Terapia" in withException)
        assertTrue("SUMMARY:Terapia spostata" in withException)
        // The copy of one event sent to somebody else has no class.
        val shared = lines(IcsWriter.write(EventShare.seriesEvents(event("u").copy(visibility = "PRIVATE", title = "Medico"), calendar, emptyList()), now))
        assertFalse(shared.any { it.startsWith("CLASS") })
        assertTrue("SUMMARY:Medico" in shared)
    }

    private fun lines(ics: String) = ics.replace("\r\n ", "").split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun anEventThatDoesNotRepeatIsWrittenAsItIsWithoutRemindersOrPrivacyClass() {
        val l = lines(EventShare.ics(EventDetail(event(), calendar, null), instance(), wholeSeries = true, exceptions = emptyList(), now = now))
        assertEquals(1, l.count { it == "BEGIN:VEVENT" })
        assertTrue("UID:uid1@google.com" in l)
        assertTrue("DTSTART:20261008T083000Z" in l)
        assertFalse(l.any { it.startsWith("TRIGGER") || it.startsWith("CLASS") })
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
    fun theFileHasNoOrganizerNorGuestsSoOtherAccountsCanImportIt() {
        val guests = listOf(Attendee("a@b.it"))
        val detail = EventDetail(event().copy(attendees = guests, organizerEmail = "me@x.com"), calendar, null)
        val ics = EventShare.ics(detail, instance(), wholeSeries = true, exceptions = emptyList(), now = now)
        assertFalse(ics.contains("ORGANIZER") || ics.contains("ATTENDEE"))
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
