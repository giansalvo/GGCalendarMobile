package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.database.AccountEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.InstanceEntity
import io.github.giansalvo.ggcalendar.core.database.OutboxEntity
import io.github.giansalvo.ggcalendar.core.model.AttachmentLink
import io.github.giansalvo.ggcalendar.core.model.EventAttachment
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import io.github.giansalvo.ggcalendar.core.network.GoogleEvent
import io.github.giansalvo.ggcalendar.core.network.GoogleEventMapper
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The attachments of the events through the local database and Google (issue #3): the rule that a list not read is never sent, what a
 * save sends, how two versions are compared, and the search over the title and the file name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentsSyncTest {
    private val me = "me@x.com"
    private val calendarId = "$me|$me"
    private val server = MockWebServer()
    private lateinit var db: GGCalendarDatabase
    private lateinit var pusher: OutboxPusher

    private val a = EventAttachment("https://example.com/files/verbale.pdf", "Verbale", "application/pdf")
    private val b = EventAttachment("https://example.com/img/schema.png", "Schema", "image/png")

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        server.start()
        pusher = OutboxPusher(context, db, GoogleCalendarApi(OkHttpClient(), server.url("/calendar/v3/")))
        db.accountDao().insert(AccountEntity(me))
        db.calendarDao().upsert(CalendarEntity(calendarId, me, me, "Me", "#1A73E8", "owner"))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun event(title: String, attachments: List<EventAttachment>?, id: String = "e1") = EventEntity(
        id = id, calendarId = calendarId, remoteId = "g1", etag = "\"1\"", title = title, attachments = attachments,
        startUtc = 1_791_806_400_000, endUtc = 1_791_810_000_000, timeZone = "Europe/Rome",
    )

    private fun googleJson(title: String, extra: String = "") =
        """{"id":"g1","etag":"\"2\"","summary":"$title","start":{"dateTime":"2026-10-12T14:00:00+02:00"},"end":{"dateTime":"2026-10-12T15:00:00+02:00"}$extra}"""

    private suspend fun queueUpdate(edited: EventEntity, base: EventEntity) {
        db.eventDao().upsert(listOf(edited))
        db.outboxDao().insert(
            OutboxEntity(
                eventId = edited.id, calendarId = edited.calendarId, operation = OutboxEntity.UPDATE,
                payload = OutboxPusher.JSON.encodeToString(edited), base = OutboxPusher.JSON.encodeToString(base),
            ),
        )
    }

    private suspend fun saveAndPush(edited: EventEntity, base: EventEntity) {
        queueUpdate(edited, base)
        server.enqueue(MockResponse.Builder().body(googleJson(edited.title)).build())
        pusher.push(me, "token") { }
    }

    private fun bodyOf(request: mockwebserver3.RecordedRequest) = Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    private fun urls(body: kotlinx.serialization.json.JsonObject) =
        body["attachments"]!!.jsonArray.map { it.jsonObject["fileUrl"]!!.jsonPrimitive.content }

    // ---- what is sent to Google

    @Test
    fun anEventNotReadIsSavedWithoutSendingAttachmentsSoGoogleKeepsThem() = runTest {
        val base = event("Vecchio", null)
        saveAndPush(base.copy(title = "Nuovo"), base)
        val body = bodyOf(server.takeRequest())
        assertEquals("Nuovo", body["summary"]!!.jsonPrimitive.content)
        assertFalse("an empty list would remove the attachments Google has", body.containsKey("attachments"))
    }

    @Test
    fun anEventNotReadStaysNotReadAfterTheSave() = runTest {
        val base = event("Vecchio", null)
        saveAndPush(base.copy(title = "Nuovo"), base)
        assertNull(db.eventDao().get("e1")!!.attachments)
    }

    @Test
    fun aLinkAddedIsSent() = runTest {
        val base = event("A", emptyList())
        saveAndPush(base.copy(attachments = listOf(a, b)), base)
        val body = bodyOf(server.takeRequest())
        assertEquals(listOf(a.url, b.url), urls(body))
        assertEquals("Verbale", body["attachments"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun aLinkRemovedLeavesTheOther() = runTest {
        val base = event("A", listOf(a, b))
        saveAndPush(base.copy(attachments = listOf(b)), base)
        assertEquals(listOf(b.url), urls(bodyOf(server.takeRequest())))
    }

    @Test
    fun allLinksRemovedSendAnEmptyList() = runTest {
        val base = event("A", listOf(a))
        saveAndPush(base.copy(attachments = emptyList()), base)
        assertEquals(0, bodyOf(server.takeRequest())["attachments"]!!.jsonArray.size)
    }

    @Test
    fun savingOnlyTheTitleOfAnEventThatWasReadSendsTheListUnchanged() = runTest {
        val base = event("A", listOf(a))
        saveAndPush(base.copy(title = "B"), base)
        assertEquals(listOf(a.url), urls(bodyOf(server.takeRequest())))
    }

    @Test
    fun theRequestAsksForSupportOfAttachments() = runTest {
        val base = event("A", listOf(a))
        saveAndPush(base.copy(attachments = listOf(a, b)), base)
        assertEquals("true", server.takeRequest().url.queryParameter("supportsAttachments"))
    }

    // ---- conflicts

    @Test
    fun anUnreadBaseIsNotADifferenceAndTheSaveIsRetriedWithoutAttachments() = runTest {
        val base = event("A", null)
        queueUpdate(base.copy(title = "B"), base)
        server.enqueue(MockResponse.Builder().code(412).build())
        // Google has attachments the phone never read, and the same text: not a conflict
        server.enqueue(MockResponse.Builder().body(googleJson("A", ""","attachments":[{"fileUrl":"https://example.com/x.pdf","title":"X"}]""")).build())
        server.enqueue(MockResponse.Builder().body(googleJson("B")).build())

        assertFalse("no conflict copy", pusher.push(me, "token") { })

        assertEquals(1, db.eventDao().getForCalendar(calendarId).size)
        server.takeRequest() // the first patch (412)
        server.takeRequest() // the read of Google's version
        assertFalse("still not sent", bodyOf(server.takeRequest()).containsKey("attachments"))
    }

    @Test
    fun attachmentsChangedOnGoogleAreARealConflict() = runTest {
        val base = event("A", listOf(a))
        queueUpdate(base.copy(title = "B"), base)
        server.enqueue(MockResponse.Builder().code(412).build())
        server.enqueue(MockResponse.Builder().body(googleJson("A", ""","attachments":[{"fileUrl":"https://example.com/other.pdf","title":"Altro"}]""")).build())

        assertTrue("the other attachment on Google is a change: conflict copy", pusher.push(me, "token") { })

        assertEquals(2, db.eventDao().getForCalendar(calendarId).size)
        assertEquals(listOf("Altro"), db.eventDao().get("e1")!!.attachments!!.map { it.title })
    }

    // ---- comparing two versions

    @Test
    fun sameAttachmentsAreTheSame() = assertTrue(EventContent.same(event("A", listOf(a, b)), event("A", listOf(b, a))))

    @Test
    fun aDifferentTitleIsADifference() = assertFalse(EventContent.same(event("A", listOf(a)), event("A", listOf(a.copy(title = "Altro")))))

    @Test
    fun aDifferentAddressIsADifference() = assertFalse(EventContent.same(event("A", listOf(a)), event("A", listOf(b))))

    @Test
    fun anUnreadListIsNotADifference() {
        assertTrue(EventContent.same(event("A", null), event("A", listOf(a))))
        assertTrue(EventContent.same(event("A", listOf(a)), event("A", null)))
    }

    @Test
    fun theIconAndTheFileIdAreNotADifference() = assertTrue(EventContent.same(event("A", listOf(a)), event("A", listOf(a.copy(fileId = "f", iconUrl = "i")))))

    // ---- from Google to the local row and back

    @Test
    fun theRowTakesTheAttachmentsOfGoogle() {
        val g = GoogleEventMapper.fromGoogle(
            Json.parseToJsonElement(googleJson("A", ""","attachments":[{"fileUrl":"https://example.com/a.pdf","title":"A","fileId":"f1"}]""")).jsonObject,
        )!!
        val row = g.toEntity("id", calendarId, null, emptyList())
        assertEquals(listOf(EventAttachment("https://example.com/a.pdf", "A", null, "f1", null)), row.attachments)
    }

    @Test
    fun anEventOfGoogleWithoutAttachmentsHasAKnownEmptyListInTheRow() {
        val g = GoogleEventMapper.fromGoogle(Json.parseToJsonElement(googleJson("A")).jsonObject)!!
        assertEquals(emptyList<EventAttachment>(), g.toEntity("id", calendarId, null, emptyList()).attachments)
    }

    @Test
    fun theRowGoesToGoogleWithItsAttachmentsOrWithoutWhenUnread() {
        val sent: GoogleEvent = event("A", listOf(a)).toGoogleEvent(null)
        assertEquals(listOf(a), sent.attachments)
        assertNull(event("A", null).toGoogleEvent(null).attachments)
    }

    // ---- the database keeps the search text and the unread rule

    @Test
    fun upsertKeepsTheSearchTextInStepWithTheList() = runTest {
        db.eventDao().upsert(listOf(event("A", listOf(EventAttachment("https://example.com/files/verbale.pdf", "Riunione Città")))))
        assertEquals("riunione citta verbale.pdf", db.eventDao().get("e1")!!.attachmentText!!.trim())
        db.eventDao().upsert(listOf(event("A", emptyList())))
        assertNull(db.eventDao().get("e1")!!.attachmentText)
    }

    @Test
    fun setAttachmentsIfUnreadFillsOnlyAnUnreadEvent() = runTest {
        val json = OutboxPusher.JSON.encodeToString(listOf(a))
        db.eventDao().upsert(listOf(event("A", null), event("B", listOf(b), id = "e2")))
        db.eventDao().setAttachmentsIfUnread("e1", json, "verbale")
        db.eventDao().setAttachmentsIfUnread("e2", json, "verbale")
        assertEquals(listOf(a), db.eventDao().get("e1")!!.attachments)
        assertEquals(listOf(b), db.eventDao().get("e2")!!.attachments)
    }

    // ---- the search

    private suspend fun seed(e: EventEntity) {
        db.eventDao().upsert(listOf(e))
        db.instanceDao().insert(listOf(InstanceEntity(e.id, e.startUtc!!, e.endUtc!!, 20_000, 20_001, false)))
    }

    private suspend fun search(text: String) = db.instanceDao().search("%$text%", "%${AttachmentLink.normalize(text)}%", 50)

    @Test
    fun theSearchFindsTheTitleOfAnAttachment() = runTest {
        seed(event("Riunione", listOf(EventAttachment("https://example.com/x.pdf", "Bilancio 2026"))))
        val rows = search("bilancio")
        assertEquals(1, rows.size)
        assertFalse("the words are only in the attachment", rows[0].inOther)
    }

    @Test
    fun theSearchFindsTheFileNameAndIgnoresCapitalsAndAccents() = runTest {
        seed(event("Riunione", listOf(EventAttachment("https://example.com/files/Verbale-Città.pdf", ""))))
        assertEquals(1, search("VERBALE-citta").size)
    }

    @Test
    fun theSearchDoesNotLookInTheAddress() = runTest {
        seed(event("Riunione", listOf(EventAttachment("https://example.com/files/x.pdf", "Bilancio"))))
        assertTrue(search("example").isEmpty())
        assertTrue(search("files").isEmpty())
    }

    @Test
    fun wordsAlsoInTheTitleDoNotMakeAnAttachmentLine() = runTest {
        seed(event("Verbale della riunione", listOf(EventAttachment("https://example.com/verbale.pdf", "Verbale"))))
        assertTrue(search("verbale").single().inOther)
    }

    @Test
    fun anEventNotReadIsSearchedWithoutError() = runTest {
        seed(event("Riunione", null))
        assertTrue(search("bilancio").isEmpty())
        assertEquals(1, search("riunione").size)
    }
}
