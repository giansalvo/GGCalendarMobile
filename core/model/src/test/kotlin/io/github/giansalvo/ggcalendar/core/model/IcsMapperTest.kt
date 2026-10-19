package io.github.giansalvo.ggcalendar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class IcsMapperTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val options = IcsImportOptions(calendarId = "cal", defaultZone = rome)

    private fun ics(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN") + lines + "END:VCALENDAR").joinToString("\r\n") + "\r\n"

    private fun event(vararg lines: String) = listOf("BEGIN:VEVENT") + lines + "END:VEVENT"

    private fun read(vararg lines: String, o: IcsImportOptions = options) = IcsMapper.parse(ics(*lines), o)

    private fun one(vararg body: String, o: IcsImportOptions = options): ImportedEvent {
        val r = read(*event("UID:u1", *body).toTypedArray(), o = o)
        return r.events.single()
    }

    private fun timed(e: ImportedEvent) = e.time as EventTime.Timed

    private fun codes(r: IcsImport) = r.report.issues.map { it.code }

    // U109
    @Test
    fun startInUtcWithOlsonZoneFloatingAndDate() {
        val utc = one("DTSTART:20261008T080000Z", "DTEND:20261008T090000Z", "SUMMARY:a")
        assertEquals(Instant.parse("2026-10-08T08:00:00Z"), timed(utc).start)
        assertNull(timed(utc).zoneId)

        val tz = one("DTSTART;TZID=America/New_York:20261008T100000", "DTEND;TZID=America/New_York:20261008T110000")
        assertEquals(Instant.parse("2026-10-08T14:00:00Z"), timed(tz).start)
        assertEquals("America/New_York", timed(tz).zoneId)

        val floating = one("DTSTART:20261008T100000", "DTEND:20261008T110000")
        assertEquals(Instant.parse("2026-10-08T08:00:00Z"), timed(floating).start)
        assertEquals("Europe/Rome", timed(floating).zoneId)

        val day = one("DTSTART;VALUE=DATE:20261008", "DTEND;VALUE=DATE:20261010")
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 10)), day.time)
    }

    // U110
    @Test
    fun endDurationAndStartOnly() {
        assertEquals(Instant.parse("2026-10-08T09:30:00Z"), timed(one("DTSTART:20261008T080000Z", "DURATION:PT1H30M")).end)
        assertEquals(Instant.parse("2026-10-09T08:00:00Z"), timed(one("DTSTART:20261008T080000Z", "DURATION:P1D")).end)
        val zero = timed(one("DTSTART:20261008T080000Z"))
        assertEquals(zero.start, zero.end)
        assertEquals(LocalDate.of(2026, 10, 9), (one("DTSTART;VALUE=DATE:20261008").time as EventTime.AllDay).endDay)
        assertEquals(LocalDate.of(2026, 10, 15), (one("DTSTART;VALUE=DATE:20261008", "DURATION:P1W").time as EventTime.AllDay).endDay)
        val before = read(*event("UID:x", "DTSTART:20261008T080000Z", "DTEND:20261008T070000Z").toTypedArray())
        assertEquals(timed(before.events.single()).start, timed(before.events.single()).end)
        assertTrue(IcsIssueCode.END_BEFORE_START in codes(before))
    }

    // U111
    @Test
    fun windowsZoneNamesBecomeOlson() {
        val e = one("DTSTART;TZID=W. Europe Standard Time:20261008T100000", "DTEND;TZID=W. Europe Standard Time:20261008T110000")
        assertEquals(Instant.parse("2026-10-08T08:00:00Z"), timed(e).start)
        assertEquals("Europe/Berlin", timed(e).zoneId)
        val pacific = one("DTSTART;TZID=Pacific Standard Time:20261008T100000")
        assertEquals(Instant.parse("2026-10-08T17:00:00Z"), timed(pacific).start)
    }

    // U112
    @Test
    fun anUnknownZoneFallsBackToThePhoneZoneAndIsReported() {
        val without = read(*event("UID:a", "DTSTART;TZID=Mars/Olympus:20261008T100000").toTypedArray())
        assertEquals(Instant.parse("2026-10-08T08:00:00Z"), timed(without.events.single()).start)
        assertEquals("Mars/Olympus", without.report.issues.single { it.code == IcsIssueCode.UNKNOWN_TZID }.sample)

        val with = read(
            "BEGIN:VTIMEZONE", "TZID:Custom/Zone", "BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:+0100", "TZOFFSETTO:+0100", "END:STANDARD", "END:VTIMEZONE",
            *event("UID:b", "DTSTART;TZID=Custom/Zone:20261008T100000").toTypedArray(),
        )
        assertEquals(1, with.events.size)
        assertTrue(IcsIssueCode.UNKNOWN_TZID in codes(with))
        assertTrue(with.report.ignoredComponents.isEmpty())

        // Thunderbird writes the zone with a path in front.
        val tb = one("DTSTART;TZID=/mozilla.org/20050126_1/Europe/Rome:20261008T100000")
        assertEquals(Instant.parse("2026-10-08T08:00:00Z"), timed(tb).start)
    }

    // U113
    @Test
    fun recurrenceRulesAreCheckedAndUntilIsMadeUnambiguous() {
        val ok = one("DTSTART;TZID=Europe/Rome:20261008T100000", "RRULE:FREQ=WEEKLY;BYDAY=TH,MO;COUNT=10;INTERVAL=2")
        assertEquals("FREQ=WEEKLY;BYDAY=TH,MO;COUNT=10;INTERVAL=2", ok.rrule)

        val utcUntil = one("DTSTART;TZID=Europe/Rome:20261008T100000", "RRULE:FREQ=DAILY;UNTIL=20261031T080000Z")
        assertEquals("FREQ=DAILY;UNTIL=20261031T080000Z", utcUntil.rrule)
        // Local until: 22:59:59 Rome on 31 Oct (UTC+1 after the DST change) is 21:59:59Z.
        val localUntil = one("DTSTART;TZID=Europe/Rome:20261008T100000", "RRULE:FREQ=DAILY;UNTIL=20261031T225959")
        assertEquals("FREQ=DAILY;UNTIL=20261031T215959Z", localUntil.rrule)
        val dateUntil = one("DTSTART;TZID=Europe/Rome:20261008T100000", "RRULE:FREQ=DAILY;UNTIL=20261031")
        assertEquals("FREQ=DAILY;UNTIL=20261031T225959Z", dateUntil.rrule)
        val allDayUntil = one("DTSTART;VALUE=DATE:20261008", "RRULE:FREQ=YEARLY;UNTIL=20301008T000000Z")
        assertEquals("FREQ=YEARLY;UNTIL=20301008", allDayUntil.rrule)

        for (bad in listOf("FREQ=SECONDLY", "FREQ=DAILY;COUNT=0", "FREQ=DAILY;COUNT=3;UNTIL=20270101", "FREQ=WEEKLY;BYDAY=XX", "INTERVAL=2", "FREQ=DAILY;BYHOUR=3", "FREQ=DAILY;BYMONTH=13", "nonsense")) {
            val r = read(*event("UID:b", "DTSTART:20261008T080000Z", "RRULE:$bad").toTypedArray())
            val e = r.events.single()
            assertNull(bad, e.rrule)
            assertTrue(bad, IcsIssueCode.INVALID_RRULE in codes(r))
        }
    }

    // U114
    @Test
    fun excludedDatesSingleMultipleDateAndDateTime() {
        val timedSeries = one(
            "DTSTART;TZID=Europe/Rome:20261008T100000", "RRULE:FREQ=DAILY",
            "EXDATE;TZID=Europe/Rome:20261009T100000", "EXDATE;TZID=Europe/Rome:20261010T100000,20261011T100000",
            "EXDATE;VALUE=DATE:20261012", "EXDATE:20261013T080000Z",
        )
        assertEquals(
            listOf("2026-10-09T08:00:00Z", "2026-10-10T08:00:00Z", "2026-10-11T08:00:00Z", "2026-10-12T08:00:00Z", "2026-10-13T08:00:00Z"),
            timedSeries.exdates,
        )
        val allDay = one("DTSTART;VALUE=DATE:20261008", "RRULE:FREQ=DAILY", "EXDATE;VALUE=DATE:20261009,20261010", "EXDATE:20261011T000000Z")
        assertEquals(listOf("2026-10-09", "2026-10-10", "2026-10-11"), allDay.exdates)
        // Without a rule there is nothing to exclude.
        assertTrue(one("DTSTART:20261008T080000Z", "EXDATE:20261009T080000Z").exdates.isEmpty())
    }

    // U115
    @Test
    fun aModifiedOccurrenceWithItsSeriesIsAnException() {
        val r = read(
            *event("UID:s", "DTSTART;TZID=Europe/Rome:20261008T100000", "DTEND;TZID=Europe/Rome:20261008T110000", "RRULE:FREQ=DAILY;COUNT=5", "SUMMARY:serie").toTypedArray(),
            *event("UID:s", "RECURRENCE-ID;TZID=Europe/Rome:20261009T100000", "DTSTART;TZID=Europe/Rome:20261009T150000", "DTEND;TZID=Europe/Rome:20261009T160000", "SUMMARY:spostato").toTypedArray(),
        )
        assertEquals(2, r.events.size)
        val master = r.events[0]
        val exception = r.events[1]
        assertEquals("cal|s", master.id)
        assertNull(master.masterId)
        assertEquals("cal|s|2026-10-09T08:00:00Z", exception.id)
        assertEquals("cal|s", exception.masterId)
        assertEquals("2026-10-09T08:00:00Z", exception.recurrenceId)
        assertEquals(0, r.report.orphanOccurrences)

        val range = read(
            *event("UID:s", "DTSTART:20261008T080000Z", "RRULE:FREQ=DAILY").toTypedArray(),
            *event("UID:s", "RECURRENCE-ID;RANGE=THISANDFUTURE:20261009T080000Z", "DTSTART:20261009T090000Z").toTypedArray(),
        )
        assertTrue(IcsIssueCode.THIS_AND_FUTURE in codes(range))
        assertEquals(2, range.events.size)
    }

    // U116
    @Test
    fun anOccurrenceWithoutItsSeriesIsASingleEventWithADistinctId() {
        val r = read(*event("UID:feed", "RECURRENCE-ID:20261008T080000Z", "DTSTART:20261008T080000Z", "DTEND:20261008T090000Z", "SUMMARY:Busy").toTypedArray())
        val e = r.events.single()
        assertEquals("cal|feed|2026-10-08T08:00:00Z", e.id)
        assertNull(e.masterId)
        assertNull(e.recurrenceId)
        assertEquals(1, r.report.orphanOccurrences)
    }

    // U117
    @Test
    fun manyOccurrencesOfTheSameUidAreManyRows() {
        val lines = ArrayList<String>()
        for (i in 0 until 109) {
            val day = LocalDate.of(2026, 10, 1).plusDays(i.toLong())
            val d = "%04d%02d%02d".format(day.year, day.monthValue, day.dayOfMonth)
            lines += event("UID:busy@google.com", "RECURRENCE-ID:${d}T080000Z", "DTSTART:${d}T080000Z", "DTEND:${d}T090000Z", "SUMMARY:Busy", "STATUS:CONFIRMED")
        }
        val r = read(*lines.toTypedArray())
        assertEquals(109, r.events.size)
        assertEquals(109, r.events.map { it.id }.toSet().size)
        assertEquals(109, r.report.orphanOccurrences)
        assertFalse(IcsIssueCode.DUPLICATE_UID in codes(r))
    }

    // U118
    @Test
    fun cancelledEventsAreSkippedAndCancelledOccurrencesBecomeExclusions() {
        val r = read(
            *event("UID:gone", "DTSTART:20261008T080000Z", "STATUS:CANCELLED").toTypedArray(),
            *event("UID:s", "DTSTART:20261008T080000Z", "RRULE:FREQ=DAILY").toTypedArray(),
            *event("UID:s", "RECURRENCE-ID:20261010T080000Z", "DTSTART:20261010T080000Z", "STATUS:CANCELLED").toTypedArray(),
            *event("UID:orphan", "RECURRENCE-ID:20261010T080000Z", "DTSTART:20261010T080000Z", "STATUS:CANCELLED").toTypedArray(),
        )
        assertEquals(listOf("cal|s"), r.events.map { it.id })
        assertEquals(listOf("2026-10-10T08:00:00Z"), r.events.single().exdates)
        assertEquals(2, r.report.cancelledEvents)
        assertEquals(1, r.report.cancelledOccurrences)
    }

    // U119
    @Test
    fun privateClassAndTransparencyAreKept() {
        assertEquals(Visibility.PRIVATE, one("DTSTART:20261008T080000Z", "CLASS:PRIVATE").visibility)
        assertEquals(Visibility.PRIVATE, one("DTSTART:20261008T080000Z", "CLASS:CONFIDENTIAL").visibility)
        assertEquals(Visibility.PUBLIC, one("DTSTART:20261008T080000Z", "CLASS:PUBLIC").visibility)
        assertEquals(Visibility.DEFAULT, one("DTSTART:20261008T080000Z").visibility)
        assertFalse(one("DTSTART:20261008T080000Z", "TRANSP:TRANSPARENT").busy)
        assertTrue(one("DTSTART:20261008T080000Z", "TRANSP:OPAQUE").busy)
        assertTrue(one("DTSTART:20261008T080000Z").busy)
    }

    private fun alarm(action: String, trigger: String, vararg extra: String) =
        listOf("BEGIN:VALARM", "ACTION:$action", trigger) + extra + "END:VALARM"

    // U120
    @Test
    fun relativeAlarmsAreKeptAndTheOthersAreDeclared() {
        val r = read(
            *event(
                "UID:a", "DTSTART:20261008T080000Z", "DTEND:20261008T090000Z",
                *alarm("DISPLAY", "TRIGGER:-PT15M", "DESCRIPTION:x").toTypedArray(),
                *alarm("AUDIO", "TRIGGER:-P1D").toTypedArray(),
                *alarm("EMAIL", "TRIGGER:-PT1H", "SUMMARY:x").toTypedArray(),
                *alarm("DISPLAY", "TRIGGER:+PT0S").toTypedArray(),
                *alarm("DISPLAY", "TRIGGER:-PT15M").toTypedArray(),
                *alarm("DISPLAY", "TRIGGER;VALUE=DATE-TIME:20261008T070000Z").toTypedArray(),
                *alarm("DISPLAY", "TRIGGER:PT30M").toTypedArray(),
                *alarm("DISPLAY", "TRIGGER:-P60D").toTypedArray(),
                *alarm("DISPLAY", "TRIGGER:-banana").toTypedArray(),
                *alarm("DISPLAY", "TRIGGER;RELATED=END:-PT90M").toTypedArray(),
            ).toTypedArray(),
        )
        val e = r.events.single()
        assertEquals(
            listOf(
                Reminder(ReminderMethod.POPUP, 15), Reminder(ReminderMethod.ALARM, 1440), Reminder(ReminderMethod.EMAIL, 60),
                Reminder(ReminderMethod.POPUP, 0),
            ),
            e.reminders.take(4),
        )
        // Related to the end of a one-hour event: 90 minutes before the end are 30 before the start.
        assertEquals(Reminder(ReminderMethod.POPUP, 30), e.reminders.last())
        assertEquals(5, e.reminders.size)
        val c = codes(r)
        assertTrue(IcsIssueCode.ABSOLUTE_ALARM in c)
        assertTrue(IcsIssueCode.ALARM_AFTER_START in c)
        assertTrue(IcsIssueCode.ALARM_TOO_EARLY in c)
        assertTrue(IcsIssueCode.INVALID_ALARM in c)
        assertTrue(e.hasAlarm)
        assertTrue(r.report.fileHasAlarms)
    }

    @Test
    fun withTheSwitchOffAlarmsAreOnlyCounted() {
        val body = event("UID:a", "DTSTART:20261008T080000Z", *alarm("DISPLAY", "TRIGGER:-PT15M").toTypedArray()).toTypedArray()
        val off = read(*body, o = options.copy(importAlarms = false))
        assertTrue(off.events.single().reminders.isEmpty())
        assertTrue(off.report.fileHasAlarms)
        assertFalse(read(*event("UID:b", "DTSTART:20261008T080000Z").toTypedArray()).report.fileHasAlarms)
    }

    @Test
    fun atMostFiveAlarmsAreKept() {
        val alarms = (1..8).flatMap { alarm("DISPLAY", "TRIGGER:-PT${it * 5}M") }
        val r = read(*event("UID:a", "DTSTART:20261008T080000Z", *alarms.toTypedArray()).toTypedArray())
        assertEquals(5, r.events.single().reminders.size)
        assertEquals(3, r.report.issues.single { it.code == IcsIssueCode.TOO_MANY_ALARMS }.count)
    }

    // U121
    @Test
    fun guestsNeverBecomeInviteesAndTheTextIsOptIn() {
        val body = arrayOf(
            "DTSTART:20261008T080000Z", "DESCRIPTION:note", "ORGANIZER;CN=Anna:mailto:anna@x.it",
            "ATTENDEE;CN=\"Bo, B\":mailto:bo@x.it", "ATTENDEE:mailto:cy@x.it",
        )
        val plain = one(*body)
        assertEquals("note", plain.description)
        val withText = one(*body, o = options.copy(guestsInDescription = true))
        assertEquals("note\nOrganizer: Anna\nAttendees: Bo, B, cy@x.it", withText.description)
    }

    // U122
    @Test
    fun longFieldsAreCutAndControlCharactersDropped() {
        val r = read(*event("UID:a", "DTSTART:20261008T080000Z", "SUMMARY:" + "t".repeat(3000), "LOCATION:l\u0007o\u001Bc", "DESCRIPTION:" + "d".repeat(9000)).toTypedArray())
        val e = r.events.single()
        assertEquals(IcsMapper.MAX_TITLE, e.title.length)
        assertEquals("loc", e.location)
        assertEquals(IcsMapper.MAX_DESCRIPTION, e.description.length)
        assertEquals(2, r.report.issues.single { it.code == IcsIssueCode.FIELD_TRUNCATED }.count)
        // A pair of surrogates is not cut in half.
        val emoji = one("DTSTART:20261008T080000Z", "SUMMARY:" + "a".repeat(IcsMapper.MAX_TITLE - 1) + "🎉")
        assertEquals(IcsMapper.MAX_TITLE - 1, emoji.title.length)
    }

    @Test
    fun escapesAreUndoneInTheTexts() {
        val e = one("DTSTART:20261008T080000Z", "SUMMARY:a\\, b\\; c\\\\ d", "DESCRIPTION:riga1\\nriga2\\Nriga3")
        assertEquals("a, b; c\\ d", e.title)
        assertEquals("riga1\nriga2\nriga3", e.description)
    }

    // U123
    @Test
    fun methodPublishRequestAndCancel() {
        for (m in listOf("PUBLISH", "REQUEST")) {
            val r = read("METHOD:$m", *event("UID:a", "DTSTART:20261008T080000Z").toTypedArray())
            assertEquals(m, r.report.method)
            assertEquals(1, r.events.size)
        }
        val cancel = read("METHOD:CANCEL", *event("UID:a", "DTSTART:20261008T080000Z").toTypedArray())
        assertEquals("CANCEL", cancel.report.method)
        assertTrue(cancel.events.isEmpty())
        assertEquals(1, cancel.report.cancelledEvents)
    }

    // U124
    @Test
    fun otherComponentsAreCountedAndRdateIsDeclared() {
        val r = read(
            "BEGIN:VTODO", "UID:t", "END:VTODO", "BEGIN:VTODO", "UID:t2", "END:VTODO", "BEGIN:VJOURNAL", "UID:j", "END:VJOURNAL", "BEGIN:VFREEBUSY", "END:VFREEBUSY",
            *event("UID:a", "DTSTART:20261008T080000Z", "RRULE:FREQ=DAILY", "RDATE:20261101T080000Z", "ATTACH:http://x/y", "CATEGORIES:a,b").toTypedArray(),
        )
        assertEquals(1, r.events.size)
        assertEquals(mapOf("VFREEBUSY" to 1, "VJOURNAL" to 1, "VTODO" to 2), r.report.ignoredComponents)
        val c = codes(r)
        assertTrue(IcsIssueCode.RDATE_IGNORED in c)
        assertTrue(IcsIssueCode.ATTACH_IGNORED in c)
        assertTrue(IcsIssueCode.CATEGORIES_IGNORED in c)
    }

    // U125
    @Test
    fun idsAreDeterministic() {
        val text = ics(*event("UID:same", "DTSTART:20261008T080000Z", "SUMMARY:x").toTypedArray(), *event("DTSTART:20261009T080000Z", "SUMMARY:senza uid").toTypedArray())
        val a = IcsMapper.parse(text, options)
        val b = IcsMapper.parse(text, options)
        assertEquals(a.events.map { it.id }, b.events.map { it.id })
        assertEquals("cal|same", a.events[0].id)
        assertTrue(a.events[1].id.startsWith("cal|noid-"))
        assertTrue(IcsIssueCode.MISSING_UID in codes(a))
        // Another calendar gives other ids for the same file.
        assertEquals("other|same", IcsMapper.parse(text, options.copy(calendarId = "other")).events[0].id)
    }

    @Test
    fun aRepeatedUidKeepsTheLastAndSaysSo() {
        val r = read(*event("UID:d", "DTSTART:20261008T080000Z", "SUMMARY:uno").toTypedArray(), *event("UID:d", "DTSTART:20261009T080000Z", "SUMMARY:due").toTypedArray())
        assertEquals(listOf("due"), r.events.map { it.title })
        assertTrue(IcsIssueCode.DUPLICATE_UID in codes(r))
    }

    @Test
    fun wrongEventsAreSkippedWithoutStoppingTheFile() {
        val r = read(
            *event("UID:nostart", "SUMMARY:x").toTypedArray(),
            *event("UID:baddate", "DTSTART:20261340T080000Z").toTypedArray(),
            *event("UID:ok", "DTSTART:20261008T080000Z").toTypedArray(),
        )
        assertEquals(listOf("cal|ok"), r.events.map { it.id })
        assertTrue(IcsIssueCode.MISSING_START in codes(r))
        assertTrue(IcsIssueCode.INVALID_DATE in codes(r))
    }

    @Test
    fun theCalendarNameIsRead() {
        assertEquals("Lavoro, sede", read("X-WR-CALNAME:Lavoro\\, sede").report.calendarName)
    }

    // U126
    @Test
    fun roundTripWithTheWriter() {
        val start = Instant.parse("2026-10-08T08:30:00Z")
        val written = listOf(
            IcsEvent("single@x", "Riunione, test; bis", EventTime.Timed(start, start.plusSeconds(3600), "Europe/Rome"), location = "Sala 1", description = "riga1\nriga2", busy = false),
            IcsEvent("day@x", "Ferie", EventTime.AllDay(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 10)), visibility = Visibility.PRIVATE),
            IcsEvent(
                "series@x", "Serie", EventTime.Timed(start, start.plusSeconds(1800), "Europe/Rome"),
                rrule = "FREQ=WEEKLY;COUNT=4", exdates = listOf("2026-10-15T08:30:00Z"),
                reminders = listOf(Reminder(ReminderMethod.POPUP, 10), Reminder(ReminderMethod.ALARM, 1440), Reminder(ReminderMethod.EMAIL, 30)),
            ),
            IcsEvent(
                "series@x", "Serie spostata", EventTime.Timed(start.plusSeconds(7 * 86_400 + 3600), start.plusSeconds(7 * 86_400 + 5400), "Europe/Rome"),
                recurrenceId = "2026-10-15T08:30:00Z",
            ),
        )
        val r = IcsMapper.parse(IcsWriter.write(written, Instant.parse("2026-10-12T10:00:00Z")), options)
        assertEquals(4, r.events.size)
        val single = r.events[0]
        assertEquals("Riunione, test; bis", single.title)
        assertEquals("Sala 1", single.location)
        assertEquals("riga1\nriga2", single.description)
        assertEquals(start, timed(single).start)
        assertEquals(start.plusSeconds(3600), timed(single).end)
        assertFalse(single.busy)
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 10)), r.events[1].time)
        assertEquals(Visibility.PRIVATE, r.events[1].visibility)
        val series = r.events[2]
        assertEquals("FREQ=WEEKLY;COUNT=4", series.rrule)
        assertEquals(listOf("2026-10-15T08:30:00Z"), series.exdates)
        assertEquals("Europe/Rome", timed(series).zoneId)
        assertEquals(listOf(Reminder(ReminderMethod.POPUP, 10), Reminder(ReminderMethod.ALARM, 1440)), series.reminders)
        val exception = r.events[3]
        assertEquals("cal|series@x", exception.masterId)
        assertEquals("2026-10-15T08:30:00Z", exception.recurrenceId)
        assertEquals("Serie spostata", exception.title)
        assertNull(r.report.fatal)
    }

    // U127
    @Test
    fun realWorldStyleFiles() {
        fun load(name: String) = String(javaClass.getResourceAsStream("/ics/$name")!!.readBytes(), Charsets.UTF_8)
        val google = IcsMapper.parse(load("google.ics"), options)
        assertEquals(3, google.events.size)
        assertEquals("Lavoro", google.report.calendarName)
        assertEquals(1, google.events.count { it.rrule != null })
        assertEquals(1, google.events.count { it.masterId != null })
        assertNull(google.report.fatal)

        val outlook = IcsMapper.parse(load("outlook.ics"), options)
        assertEquals(2, outlook.events.size)
        assertEquals("REQUEST", outlook.report.method)
        assertEquals(Instant.parse("2026-10-08T08:00:00Z"), timed(outlook.events[0]).start)
        assertEquals("Europe/Berlin", timed(outlook.events[0]).zoneId)
        assertTrue(outlook.events[0].reminders.isNotEmpty())
        assertTrue(outlook.events[1].time is EventTime.AllDay)

        val apple = IcsMapper.parse(load("apple.ics"), options)
        assertEquals(2, apple.events.size)
        assertEquals(Instant.parse("2026-10-08T07:00:00Z"), timed(apple.events[0]).start)
        assertEquals(listOf(Reminder(ReminderMethod.ALARM, 15)), apple.events[0].reminders)

        val thunderbird = IcsMapper.parse(load("thunderbird.ics"), options)
        assertEquals(2, thunderbird.events.size)
        assertEquals(Instant.parse("2026-10-08T08:00:00Z"), timed(thunderbird.events[0]).start)
        assertEquals("Europe/Rome", timed(thunderbird.events[0]).zoneId)
        assertEquals(1, thunderbird.report.ignoredComponents["VTODO"])
        assertNotNull(thunderbird.events[0].rrule)
    }
}
