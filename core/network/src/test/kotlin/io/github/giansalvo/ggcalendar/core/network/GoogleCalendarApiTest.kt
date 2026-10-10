package io.github.giansalvo.ggcalendar.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleCalendarApiTest {
    private val server = MockWebServer()
    private lateinit var api: GoogleCalendarApi

    @Before
    fun setUp() {
        server.start()
        api = GoogleCalendarApi(OkHttpClient(), server.url("/calendar/v3/"))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun listCalendarsFollowsPagesAndMapsFields() = runTest {
        server.enqueue(
            MockResponse.Builder().body(
                """{"items":[{"id":"me@x.com","summary":"Me","primary":true,"accessRole":"owner","backgroundColor":"#ff0000"}],
                   "nextPageToken":"p2"}""",
            ).build(),
        )
        server.enqueue(
            MockResponse.Builder().body(
                """{"items":[{"id":"hol","summary":"Holidays","summaryOverride":"Festività","accessRole":"reader"}]}""",
            ).build(),
        )

        val list = api.listCalendars("token")

        assertEquals(2, list.size)
        assertEquals(GoogleCalendarEntry("me@x.com", "Me", "#ff0000", isPrimary = true, canWrite = true), list[0])
        assertEquals("Festività", list[1].name)
        assertEquals("#1A73E8", list[1].colorHex)
        assertFalse(list[1].canWrite)
        val first = server.takeRequest()
        assertEquals("Bearer token", first.headers["Authorization"])
        assertEquals("reader", first.url.queryParameter("minAccessRole"))
        assertEquals("p2", server.takeRequest().url.queryParameter("pageToken"))
    }

    @Test
    fun errorsCarryTheHttpStatus() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        val error = runCatching { api.primaryCalendarId("bad") }.exceptionOrNull()
        assertTrue(error is GoogleApiException && error.status == 401)
    }
}
