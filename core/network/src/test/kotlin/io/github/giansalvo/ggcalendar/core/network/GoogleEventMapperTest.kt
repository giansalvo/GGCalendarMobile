package io.github.giansalvo.ggcalendar.core.network

import io.github.giansalvo.ggcalendar.core.model.EventTime
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class GoogleEventMapperTest {
    private fun parse(json: String) = GoogleEventMapper.fromGoogle(Json.parseToJsonElement(json).jsonObject)!!

    @Test
    fun pendingConferenceKeepsMeetFlag() {
        val ev = parse(
            """{"id":"r1","summary":"ttt","start":{"dateTime":"2026-10-08T05:00:00Z"},"end":{"dateTime":"2026-10-08T06:00:00Z"},
               "conferenceData":{"createRequest":{"requestId":"x","status":{"statusCode":"pending"}}}}""",
        )
        assertTrue(ev.hasConference)
        assertNull(ev.meetLink)
    }

    @Test
    fun meetLinkReadFromConferenceEntryPoints() {
        val ev = parse(
            """{"id":"r1","start":{"dateTime":"2026-10-08T05:00:00Z"},"end":{"dateTime":"2026-10-08T06:00:00Z"},
               "conferenceData":{"entryPoints":[{"entryPointType":"phone","uri":"tel:+39"},
               {"entryPointType":"video","uri":"https://meet.google.com/abc-defg-hij"}]}}""",
        )
        assertEquals("https://meet.google.com/abc-defg-hij", ev.meetLink)
    }

    @Test
    fun eventTypeIsKeptAndDefaultsToDefault() {
        val birthday = parse(
            """{"id":"b","eventType":"birthday","summary":"Anna","start":{"date":"2026-10-12"},"end":{"date":"2026-10-13"},
               "recurrence":["RRULE:FREQ=YEARLY"]}""",
        )
        assertEquals("birthday", birthday.eventType)
        assertEquals("default", parse("""{"id":"a","start":{"date":"2026-10-12"},"end":{"date":"2026-10-13"}}""").eventType)
    }

    @Test
    fun allDayEventHasExclusiveEndDate() {
        val ev = parse("""{"id":"a","start":{"date":"2026-10-12"},"end":{"date":"2026-10-14"}}""")
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)), ev.time)
    }

    @Test
    fun timedEventKeepsItsTimeZone() {
        val ev = parse(
            """{"id":"t","start":{"dateTime":"2026-10-12T09:00:00+02:00","timeZone":"Europe/Rome"},
               "end":{"dateTime":"2026-10-12T10:00:00+02:00","timeZone":"Europe/Rome"}}""",
        )
        assertEquals(EventTime.Timed(Instant.parse("2026-10-12T07:00:00Z"), Instant.parse("2026-10-12T08:00:00Z"), "Europe/Rome"), ev.time)
    }

    @Test
    fun recurrenceExceptionsAndResourcesAreParsed() {
        val ev = parse(
            """{"id":"s","start":{"dateTime":"2026-10-05T08:00:00Z"},"end":{"dateTime":"2026-10-05T09:00:00Z"},
               "recurrence":["RRULE:FREQ=WEEKLY;BYDAY=MO","EXDATE;TZID=Europe/Rome:20261012T100000,20261019T100000"],
               "attendees":[{"email":"a@x.it","responseStatus":"accepted"},{"email":"room@resource.calendar.google.com","resource":true}],
               "reminders":{"useDefault":false,"overrides":[{"method":"email","minutes":1440},{"method":"popup","minutes":10}]},
               "extendedProperties":{"private":{"ggAlarm":"10"}}}""",
        )
        assertEquals("FREQ=WEEKLY;BYDAY=MO", ev.rrule)
        assertEquals(
            listOf(OriginalStart.Timed(Instant.parse("2026-10-12T08:00:00Z")), OriginalStart.Timed(Instant.parse("2026-10-19T08:00:00Z"))),
            ev.exdates,
        )
        assertEquals(listOf("a@x.it"), ev.attendees.map { it.email })
        assertEquals(listOf(Reminder(ReminderMethod.EMAIL, 1440), Reminder(ReminderMethod.POPUP, 10)), ev.reminders)
        assertEquals(setOf(10), ev.alarmMinutes)
    }

    @Test
    fun defaultRemindersAreNull() {
        val ev = parse(
            """{"id":"d","start":{"date":"2026-10-12"},"end":{"date":"2026-10-13"},"reminders":{"useDefault":true}}""",
        )
        assertNull(ev.reminders)
    }

    @Test
    fun cancelledOccurrenceKeepsOnlyIdsAndOriginalStart() {
        val ev = parse(
            """{"id":"s_20261012T080000Z","status":"cancelled","recurringEventId":"s",
               "originalStartTime":{"dateTime":"2026-10-12T08:00:00Z"}}""",
        )
        assertTrue(ev.cancelled)
        assertEquals("s", ev.recurringEventId)
        assertEquals(OriginalStart.Timed(Instant.parse("2026-10-12T08:00:00Z")), ev.originalStart)
        assertNull(ev.time)
    }
}
