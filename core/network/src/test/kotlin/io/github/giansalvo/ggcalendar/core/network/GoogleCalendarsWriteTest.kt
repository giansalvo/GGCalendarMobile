package io.github.giansalvo.ggcalendar.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Creating, coloring, renaming and deleting the calendars of an account (issue #14). */
class GoogleCalendarsWriteTest {
    private val server = MockWebServer()
    private lateinit var api: GoogleCalendarApi

    @Before
    fun setUp() {
        server.start()
        api = GoogleCalendarApi(OkHttpClient(), server.url("/calendar/v3/"))
    }

    @After
    fun tearDown() = server.close()

    private fun ok(body: String = "{}") = MockResponse.Builder().body(body).build()

    private fun status(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()

    @Test
    fun createPostsTheNameAndTheZoneAndReturnsTheId() = runTest {
        server.enqueue(ok("""{"id":"abc123@group.calendar.google.com","summary":"Palestra"}"""))
        val id = api.createCalendar("Palestra", "Europe/Rome", "tok")
        assertEquals("abc123@group.calendar.google.com", id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/calendar/v3/calendars", request.url.encodedPath)
        assertEquals("Bearer tok", request.headers["Authorization"])
        val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("Palestra", sent["summary"]!!.jsonPrimitive.content)
        assertEquals("Europe/Rome", sent["timeZone"]!!.jsonPrimitive.content)
    }

    @Test
    fun theNameIsEscapedInTheJson() = runTest {
        val name = "L'«agenda» \"di\" casa \\ è — 🎾 é"
        server.enqueue(ok("""{"id":"x"}"""))
        api.createCalendar(name, "UTC", "tok")
        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(name, sent["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun aCreatedCalendarWithoutIdIsAnError() = runTest {
        server.enqueue(ok("""{"summary":"Palestra"}"""))
        val error = runCatching { api.createCalendar("Palestra", "UTC", "tok") }.exceptionOrNull()
        assertTrue(error is GoogleApiException)
    }

    @Test
    fun theColorGoesToTheListEntryWithATextColorOnIt() = runTest {
        server.enqueue(ok())
        api.setCalendarListEntry("abc@group.calendar.google.com", "#8E24AA", clearPersonalName = false, accessToken = "tok")
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/calendar/v3/users/me/calendarList/abc@group.calendar.google.com", request.url.encodedPath.replace("%40", "@"))
        assertEquals("true", request.url.queryParameter("colorRgbFormat"))
        val sent = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("#8E24AA", sent["backgroundColor"]!!.jsonPrimitive.content)
        assertEquals("#FFFFFF", sent["foregroundColor"]!!.jsonPrimitive.content)
        assertFalse(sent.containsKey("summaryOverride"))
    }

    @Test
    fun aLightColorGetsBlackText() = runTest {
        server.enqueue(ok())
        api.setCalendarListEntry("c", "#F6BF26", clearPersonalName = false, accessToken = "tok")
        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("#000000", sent["foregroundColor"]!!.jsonPrimitive.content)
    }

    @Test
    fun theIdOfACalendarWithSeparatorsIsEncodedInThePath() = runTest {
        server.enqueue(ok())
        api.setCalendarListEntry("a#b c/d@x.com", "#039BE5", clearPersonalName = false, accessToken = "tok")
        val request = server.takeRequest()
        assertEquals("/calendar/v3/users/me/calendarList/a%23b%20c%2Fd@x.com", request.url.encodedPath.replace("%40", "@"))
    }

    @Test
    fun clearingThePersonalNameSendsANullAndNoColor() = runTest {
        server.enqueue(ok())
        api.setCalendarListEntry("c", null, clearPersonalName = true, accessToken = "tok")
        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(JsonNull, sent["summaryOverride"])
        assertFalse(sent.containsKey("backgroundColor"))
        assertFalse(sent.containsKey("foregroundColor"))
    }

    @Test
    fun renamePatchesTheCalendarItself() = runTest {
        server.enqueue(ok())
        api.renameCalendar("abc@group.calendar.google.com", "Famiglia e amici", "tok")
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/calendar/v3/calendars/abc@group.calendar.google.com", request.url.encodedPath.replace("%40", "@"))
        assertEquals("Famiglia e amici", Json.parseToJsonElement(request.body!!.utf8()).jsonObject["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun deleteSendsDeleteAndAcceptsNoContent() = runTest {
        server.enqueue(status(204))
        api.deleteCalendar("abc@group.calendar.google.com", "tok")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/calendar/v3/calendars/abc@group.calendar.google.com", request.url.encodedPath.replace("%40", "@"))
    }

    @Test
    fun aCalendarAlreadyGoneCountsAsDeleted() = runTest {
        server.enqueue(status(404))
        api.deleteCalendar("a", "tok")
        server.enqueue(status(410))
        api.deleteCalendar("b", "tok")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun otherErrorsOfADeleteGoToTheCaller() = runTest {
        server.enqueue(status(403, """{"error":{"errors":[{"reason":"forbiddenForNonOwners"}]}}"""))
        val forbidden = runCatching { api.deleteCalendar("a", "tok") }.exceptionOrNull() as GoogleApiException
        assertEquals(403, forbidden.status)
        server.enqueue(status(500))
        assertEquals(500, (runCatching { api.deleteCalendar("a", "tok") }.exceptionOrNull() as GoogleApiException).status)
    }

    @Test
    fun onlyTheLimitOfRequestsIsARateLimit() {
        assertTrue(GoogleApiException(429, "x").isRateLimit)
        assertTrue(GoogleApiException(403, "Google Calendar: 403 {\"reason\":\"rateLimitExceeded\"}").isRateLimit)
        assertFalse(GoogleApiException(403, "Google Calendar: 403 {\"reason\":\"forbiddenForNonOwners\"}").isRateLimit)
        assertFalse(GoogleApiException(401, "x").isRateLimit)
    }
}
