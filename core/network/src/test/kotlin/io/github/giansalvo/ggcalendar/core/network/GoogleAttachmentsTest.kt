package io.github.giansalvo.ggcalendar.core.network

import io.github.giansalvo.ggcalendar.core.model.EventAttachment
import io.github.giansalvo.ggcalendar.core.model.EventTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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

/** The attachments of the events on the Google side (issue #3): what is read, what is sent, and the rule that an unread list is never sent. */
class GoogleAttachmentsTest {
    private val server = MockWebServer()
    private lateinit var api: GoogleCalendarApi

    @Before
    fun setUp() {
        server.start()
        api = GoogleCalendarApi(OkHttpClient(), server.url("/calendar/v3/"))
    }

    @After
    fun tearDown() = server.close()

    private val head = """"id":"e1","summary":"x","start":{"dateTime":"2026-10-08T05:00:00Z"},"end":{"dateTime":"2026-10-08T06:00:00Z"}"""
    private fun parse(extra: String = "") = GoogleEventMapper.fromGoogle(Json.parseToJsonElement("{$head$extra}").jsonObject)!!

    private fun event(attachments: List<EventAttachment>?) = GoogleEvent(
        id = "e1", etag = null, iCalUid = null, cancelled = false, recurringEventId = null, originalStart = null,
        time = EventTime.Timed(Instant.parse("2026-10-08T05:00:00Z"), Instant.parse("2026-10-08T06:00:00Z"), "Europe/Rome"),
        title = "x", attachments = attachments,
    )

    private fun body(ev: GoogleEvent, patch: Boolean) = GoogleEventMapper.toGoogle(ev, includeMeetRequest = false, patch = patch)

    // ---- reading

    @Test
    fun theAttachmentsAreRead() {
        val ev = parse(
            ""","attachments":[{"fileUrl":"https://example.com/a.pdf","title":"Verbale","mimeType":"application/pdf","iconLink":"https://i/x.png"},
               {"fileUrl":"https://drive.google.com/open?id=abc","title":"Bilancio","fileId":"abc"}]""",
        )
        assertEquals(
            listOf(
                EventAttachment("https://example.com/a.pdf", "Verbale", "application/pdf", null, "https://i/x.png"),
                EventAttachment("https://drive.google.com/open?id=abc", "Bilancio", null, "abc", null),
            ),
            ev.attachments,
        )
    }

    @Test
    fun anEventWithoutTheFieldHasAKnownEmptyList() = assertEquals(emptyList<EventAttachment>(), parse().attachments)

    @Test
    fun anAttachmentWithoutAddressIsSkipped() {
        val ev = parse(""","attachments":[{"title":"senza indirizzo"},{"fileUrl":"","title":"vuoto"},{"fileUrl":"https://example.com/a.pdf"}]""")
        assertEquals(listOf("https://example.com/a.pdf"), ev.attachments?.map { it.url })
    }

    @Test
    fun aCancelledEventHasNoList() {
        val ev = GoogleEventMapper.fromGoogle(Json.parseToJsonElement("""{"id":"e1","status":"cancelled"}""").jsonObject)!!
        assertNull(ev.attachments)
    }

    // ---- writing

    @Test
    fun anUnreadListIsNotSentInAPatch() = assertFalse(body(event(null), patch = true).containsKey("attachments"))

    @Test
    fun anUnreadListIsNotSentInAnInsert() = assertFalse(body(event(null), patch = false).containsKey("attachments"))

    @Test
    fun anEmptyListInAPatchRemovesTheAttachmentsOnGoogle() {
        val sent = body(event(emptyList()), patch = true)["attachments"]!!.jsonArray
        assertEquals(0, sent.size)
    }

    @Test
    fun anEmptyListInAnInsertSendsNothing() = assertFalse(body(event(emptyList()), patch = false).containsKey("attachments"))

    @Test
    fun theLinksAreSentWithAddressTitleAndType() {
        val sent = body(event(listOf(EventAttachment("https://example.com/a.pdf", "Verbale", "application/pdf", "ignored-id", "ignored-icon"))), patch = true)["attachments"]!!.jsonArray
        assertEquals(1, sent.size)
        val a = sent[0].jsonObject
        assertEquals("https://example.com/a.pdf", a["fileUrl"]!!.jsonPrimitive.content)
        assertEquals("Verbale", a["title"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", a["mimeType"]!!.jsonPrimitive.content)
        assertFalse("the file id and the icon are Google's own", a.containsKey("fileId") || a.containsKey("iconLink"))
    }

    @Test
    fun aLinkWithoutTitleIsSentWithItsFileName() {
        val sent = body(event(listOf(EventAttachment("https://example.com/files/verbale.pdf"))), patch = false)["attachments"]!!.jsonArray
        assertEquals("verbale.pdf", sent[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertFalse(sent[0].jsonObject.containsKey("mimeType"))
    }

    @Test
    fun atMost25LinksAreSent() {
        val many = (1..40).map { EventAttachment("https://example.com/$it.pdf") }
        assertEquals(25, body(event(many), patch = true)["attachments"]!!.jsonArray.size)
    }

    // ---- the requests

    private fun ok() = server.enqueue(MockResponse.Builder().body("""{$head}""").build())

    @Test
    fun anInsertAsksForSupportOfAttachments() = runTest {
        ok()
        api.insertEvent("cal", buildJsonObject { }, true, "token")
        assertEquals("true", server.takeRequest().url.queryParameter("supportsAttachments"))
    }

    @Test
    fun aPatchAsksForSupportOfAttachments() = runTest {
        ok()
        api.patchEvent("cal", "e1", buildJsonObject { }, true, "token")
        val url = server.takeRequest().url
        assertEquals("true", url.queryParameter("supportsAttachments"))
        assertEquals("1", url.queryParameter("conferenceDataVersion"))
    }

    @Test
    fun theAnswerOfAPatchCarriesTheAttachmentsBack() = runTest {
        server.enqueue(MockResponse.Builder().body("""{$head,"attachments":[{"fileUrl":"https://example.com/a.pdf","title":"A"}]}""").build())
        val saved = api.patchEvent("cal", "e1", buildJsonObject { }, true, "token")
        assertTrue(saved.attachments!!.single().title == "A")
    }
}
