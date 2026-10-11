package io.github.giansalvo.ggcalendar.core.network

import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.EventTime
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GoogleWriteTest {
    private val server = MockWebServer()
    private lateinit var api: GoogleCalendarApi
    private val rome = ZoneId.of("Europe/Rome")

    @Before
    fun setUp() {
        server.start()
        api = GoogleCalendarApi(OkHttpClient(), server.url("/calendar/v3/"))
    }

    @After
    fun tearDown() = server.close()

    private val timed = GoogleEvent(
        id = "", etag = null, iCalUid = null, cancelled = false, recurringEventId = null, originalStart = null,
        time = EventTime.Timed(Instant.parse("2026-10-12T12:00:00Z"), Instant.parse("2026-10-12T14:00:00Z"), "Europe/Rome"),
        title = "Eu code week",
        attendees = listOf(Attendee("a@x.com"), Attendee("b@x.com", optional = true)),
        reminders = listOf(Reminder(ReminderMethod.POPUP, 10), Reminder(ReminderMethod.EMAIL, 1440)),
        alarmMinutes = setOf(10),
    )

    private fun JsonObject.str(vararg path: String): String? {
        var o: JsonObject = this
        path.dropLast(1).forEach { o = o[it]!!.jsonObject }
        return (o[path.last()] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }

    @Test
    fun timedEventBodyHasZoneRemindersAndAlarm() {
        val body = GoogleEventMapper.toGoogle(timed, includeMeetRequest = true, patch = false, defaultZone = rome)
        assertEquals("2026-10-12T14:00:00+02:00", body.str("start", "dateTime"))
        assertEquals("Europe/Rome", body.str("start", "timeZone"))
        assertEquals(JsonNull, body["start"]!!.jsonObject["date"])
        assertEquals("false", body["reminders"]!!.jsonObject["useDefault"]!!.jsonPrimitive.content)
        assertEquals(2, body["reminders"]!!.jsonObject["overrides"]!!.jsonArray.size)
        assertEquals("10", body.str("extendedProperties", "private", "ggAlarm"))
        assertEquals("true", body["attendees"]!!.jsonArray[1].jsonObject["optional"]!!.jsonPrimitive.content)
        assertEquals("hangoutsMeet", body.str("conferenceData", "createRequest", "conferenceSolutionKey", "type"))
        // An insert does not clear anything.
        assertFalse(body.containsKey("colorId"))
        assertFalse(body.containsKey("recurrence"))
    }

    @Test
    fun patchClearsTheOtherDateFormColorAlarmAndRecurrence() {
        val allDay = timed.copy(
            time = EventTime.AllDay(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13)),
            reminders = null,
            alarmMinutes = emptySet(),
        )
        val body = GoogleEventMapper.toGoogle(allDay, includeMeetRequest = false, patch = true, defaultZone = rome)
        assertEquals("2026-10-12", body.str("start", "date"))
        assertEquals(JsonNull, body["start"]!!.jsonObject["dateTime"])
        assertEquals("true", body["reminders"]!!.jsonObject["useDefault"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["colorId"])
        assertEquals("", body.str("extendedProperties", "private", "ggAlarm"))
        assertTrue(body["recurrence"]!!.jsonArray.isEmpty())
        // An occurrence of a series never carries a recurrence.
        assertFalse(GoogleEventMapper.toGoogle(allDay.copy(recurringEventId = "m"), false, patch = true).containsKey("recurrence"))
    }

    @Test
    fun seriesSendsRruleAndExdates() {
        val series = timed.copy(
            rrule = "FREQ=WEEKLY;BYDAY=MO",
            exdates = listOf(OriginalStart.Timed(Instant.parse("2026-10-19T12:00:00Z"))),
        )
        val lines = GoogleEventMapper.toGoogle(series, false, patch = false)["recurrence"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("RRULE:FREQ=WEEKLY;BYDAY=MO", "EXDATE:20261019T120000Z"), lines)
    }

    @Test
    fun instanceIdsUseUtcOrTheDate() {
        assertEquals("abc_20261019T120000Z", GoogleEventMapper.instanceId("abc", OriginalStart.Timed(Instant.parse("2026-10-19T12:00:00Z"))))
        assertEquals("abc_20261019", GoogleEventMapper.instanceId("abc", OriginalStart.AllDay(LocalDate.of(2026, 10, 19))))
        assertEquals("5", GoogleEventMapper.colorIdOf("#f6bf26"))
        assertNull(GoogleEventMapper.colorIdOf("#123456"))
    }

    @Test
    fun insertPostsTheBodyWithConferenceAndInvites() = runTest {
        server.enqueue(
            MockResponse.Builder().body(
                """{"id":"new1","etag":"\"e1\"","start":{"dateTime":"2026-10-12T14:00:00+02:00"},"end":{"dateTime":"2026-10-12T16:00:00+02:00"}}""",
            ).build(),
        )
        val created = api.insertEvent("me@x.com", GoogleEventMapper.toGoogle(timed, false, false), sendUpdates = true, accessToken = "t")
        assertEquals("new1", created.id)
        assertEquals("\"e1\"", created.etag)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("1", request.url.queryParameter("conferenceDataVersion"))
        assertEquals("all", request.url.queryParameter("sendUpdates"))
        val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("Eu code week", sent.str("summary"))
    }

    @Test
    fun patchSendsIfMatchAndReports412() = runTest {
        server.enqueue(MockResponse.Builder().code(412).body("""{"error":{"code":412}}""").build())
        val error = runCatching {
            api.patchEvent("me@x.com", "ev1", GoogleEventMapper.toGoogle(timed, false, true), false, "t", etag = "\"e1\"")
        }.exceptionOrNull()
        assertEquals(412, (error as GoogleApiException).status)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("\"e1\"", request.headers["If-Match"])
        assertEquals("none", request.url.queryParameter("sendUpdates"))
    }

    @Test
    fun deletingAnEventAlreadyGoneSucceedsAndGetReturnsNull() = runTest {
        server.enqueue(MockResponse.Builder().code(410).build())
        api.deleteEvent("me@x.com", "ev1", sendUpdates = true, accessToken = "t")
        assertEquals("DELETE", server.takeRequest().method)
        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(api.getEvent("me@x.com", "ev1", "t"))
    }
}
