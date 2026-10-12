package io.github.giansalvo.ggcalendar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class IcsWriterTest {
    private val now = Instant.parse("2026-10-12T10:00:00Z")

    // 2026-10-08 10:30 in Rome (UTC+2).
    private val start = Instant.parse("2026-10-08T08:30:00Z")
    private val end = Instant.parse("2026-10-08T09:30:00Z")

    private fun write(vararg events: IcsEvent) = IcsWriter.write(events.toList(), now)

    /** The logical lines: folded lines are joined again. */
    private fun lines(ics: String) = ics.replace("\r\n ", "").split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun aSingleTimedEventIsWrittenInUtc() {
        val ics = write(IcsEvent("u1@x", "Riunione, test; bis", EventTime.Timed(start, end, "Europe/Rome"), location = "Sala 1"))
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))
        assertTrue(ics.endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n"))
        val l = lines(ics)
        assertTrue("UID:u1@x" in l)
        assertTrue("DTSTAMP:20261012T100000Z" in l)
        assertTrue("DTSTART:20261008T083000Z" in l)
        assertTrue("DTEND:20261008T093000Z" in l)
        assertTrue("SUMMARY:Riunione\\, test\\; bis" in l)
        assertTrue("LOCATION:Sala 1" in l)
        assertTrue("TRANSP:OPAQUE" in l)
    }

    @Test
    fun anAllDayEventUsesDatesWithAnExclusiveEnd() {
        val l = lines(write(IcsEvent("u2", "Ferie", EventTime.AllDay(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 10)))))
        assertTrue("DTSTART;VALUE=DATE:20261008" in l)
        assertTrue("DTEND;VALUE=DATE:20261010" in l)
    }

    @Test
    fun lineBreaksInTextAreEscaped() {
        val l = lines(write(IcsEvent("u3", "T", EventTime.Timed(start, end, null), description = "riga uno\nriga due\\fine")))
        assertTrue("DESCRIPTION:riga uno\\nriga due\\\\fine" in l)
    }

    @Test
    fun theMeetLinkIsAddedToTheDescriptionAndAsUrlOnce() {
        val meet = "https://meet.google.com/abc-defg-hij"
        val l = lines(write(IcsEvent("u4", "T", EventTime.Timed(start, end, null), description = "Ordine del giorno", meetLink = meet)))
        assertTrue("URL:$meet" in l)
        assertTrue(l.any { it.startsWith("DESCRIPTION:Ordine del giorno\\nGoogle Meet: https") })
        // A description that already has the link does not repeat it.
        val again = write(IcsEvent("u4", "T", EventTime.Timed(start, end, null), description = "Entra: $meet", meetLink = meet))
        assertEquals(1, Regex("meet.google.com").findAll(again.replace("\r\n ", "").substringAfter("DESCRIPTION:").substringBefore("\r\n")).count())
    }

    @Test
    fun longLinesAreFoldedAt75BytesWithoutCuttingACharacter() {
        val title = "È".repeat(120) + " fine"
        val ics = write(IcsEvent("u5", title, EventTime.Timed(start, end, null)))
        ics.split("\r\n").forEach { assertTrue("line too long: ${it.length}", it.toByteArray(Charsets.UTF_8).size <= 75) }
        // Unfolding gives the original text back.
        val unfolded = ics.replace("\r\n ", "")
        assertTrue("SUMMARY:$title" in unfolded.split("\r\n"))
        assertTrue(ics.contains("\r\n "))
    }

    @Test
    fun aSeriesKeepsItsZoneAndExcludedDatesAreLocal() {
        val series = IcsEvent(
            "u6", "Lezione", EventTime.Timed(start, end, "Europe/Rome"),
            rrule = "RRULE:FREQ=WEEKLY;BYDAY=TH", exdates = listOf("2026-10-15T08:30:00Z"),
        )
        val l = lines(write(series))
        assertTrue("DTSTART;TZID=Europe/Rome:20261008T103000" in l)
        assertTrue("DTEND;TZID=Europe/Rome:20261008T113000" in l)
        assertTrue("RRULE:FREQ=WEEKLY;BYDAY=TH" in l)
        assertTrue("EXDATE;TZID=Europe/Rome:20261015T103000" in l)
        // The same rule without the prefix is written the same way.
        assertTrue("RRULE:FREQ=WEEKLY;BYDAY=TH" in lines(write(series.copy(rrule = "FREQ=WEEKLY;BYDAY=TH"))))
    }

    @Test
    fun aModifiedOccurrenceKeepsTheUidOfItsSeries() {
        val exception = IcsEvent("u6", "Lezione spostata", EventTime.Timed(start.plusSeconds(3600), end.plusSeconds(3600), "Europe/Rome"), recurrenceId = "2026-10-08T08:30:00Z")
        val l = lines(write(exception))
        assertTrue("UID:u6" in l)
        assertTrue("RECURRENCE-ID;TZID=Europe/Rome:20261008T103000" in l)
        assertTrue("DTSTART;TZID=Europe/Rome:20261008T113000" in l)
        assertFalse(l.any { it.startsWith("RRULE") })
    }

    @Test
    fun anAllDaySeriesUsesDateValues() {
        val l = lines(write(IcsEvent("u7", "Compleanno", EventTime.AllDay(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9)), rrule = "FREQ=YEARLY", exdates = listOf("2027-10-08"))))
        assertTrue("EXDATE;VALUE=DATE:20271008" in l)
    }

    @Test
    fun remindersBecomeAlarmsExceptEmail() {
        val ics = write(
            IcsEvent(
                "u8", "T", EventTime.Timed(start, end, null),
                reminders = listOf(Reminder(ReminderMethod.POPUP, 30), Reminder(ReminderMethod.ALARM, 10), Reminder(ReminderMethod.EMAIL, 60), Reminder(ReminderMethod.POPUP, 1440)),
            ),
        )
        val l = lines(ics)
        assertEquals(3, l.count { it == "BEGIN:VALARM" })
        assertTrue("TRIGGER:-PT30M" in l)
        assertTrue("TRIGGER:-PT10M" in l)
        assertTrue("TRIGGER:-P1D" in l)
        assertFalse("TRIGGER:-PT60M" in l)
        assertEquals(listOf("DISPLAY", "AUDIO", "DISPLAY"), l.filter { it.startsWith("ACTION:") }.map { it.removePrefix("ACTION:") })
    }

    @Test
    fun attendeesAndTheOrganizerAreWritten() {
        val l = lines(
            write(
                IcsEvent(
                    "u9", "T", EventTime.Timed(start, end, null), organizerEmail = "me@x.com",
                    attendees = listOf(
                        Attendee("a@b.it", displayName = "Rossi, Mario", response = "accepted"),
                        Attendee("c@d.it", optional = true, response = "declined"),
                        Attendee("e@f.it"),
                    ),
                ),
            ),
        )
        assertTrue("ORGANIZER:mailto:me@x.com" in l)
        assertTrue("ATTENDEE;CN=\"Rossi, Mario\";ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:a@b.it" in l)
        assertTrue("ATTENDEE;ROLE=OPT-PARTICIPANT;PARTSTAT=DECLINED:mailto:c@d.it" in l)
        assertTrue("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:e@f.it" in l)
    }

    @Test
    fun availabilityAndVisibilityAreKept() {
        val l = lines(write(IcsEvent("u10", "T", EventTime.Timed(start, end, null), busy = false, visibility = Visibility.PRIVATE)))
        assertTrue("TRANSP:TRANSPARENT" in l)
        assertTrue("CLASS:PRIVATE" in l)
    }

    @Test
    fun severalEventsShareOneCalendar() {
        val ics = write(IcsEvent("a", "A", EventTime.Timed(start, end, null)), IcsEvent("b", "B", EventTime.Timed(start, end, null)))
        assertEquals(1, Regex("BEGIN:VCALENDAR").findAll(ics).count())
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
    }
}
