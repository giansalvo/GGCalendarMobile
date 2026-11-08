package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.database.AccountEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.OutboxEntity
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The push of the outbox against a simulated Google, with the same cases as the desktop sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutboxPusherTest {
    private val me = "me@x.com"
    private val calendarId = "$me|$me"
    private val server = MockWebServer()
    private lateinit var db: GGCalendarDatabase
    private lateinit var pusher: OutboxPusher
    private val recomputed = mutableListOf<String>()
    private val reports = ImportReports()

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        server.start()
        pusher = OutboxPusher(context, db, GoogleCalendarApi(OkHttpClient(), server.url("/calendar/v3/")), reports)
        db.accountDao().insert(AccountEntity(me))
        db.calendarDao().upsert(CalendarEntity(calendarId, me, me, "Me", "#1A73E8", "owner"))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun event(title: String, remoteId: String? = null, etag: String? = null) = EventEntity(
        id = "e1", calendarId = calendarId, remoteId = remoteId, etag = etag, title = title,
        startUtc = 1_791_806_400_000, endUtc = 1_791_810_000_000, timeZone = "Europe/Rome",
    )

    private fun googleJson(id: String, etag: String, title: String, extra: String = "") =
        """{"id":"$id","etag":"${etag.replace("\"", "\\\"")}","summary":"$title","start":{"dateTime":"2026-10-12T14:00:00+02:00"},
           "end":{"dateTime":"2026-10-12T15:00:00+02:00"}$extra}"""

    private suspend fun queue(e: EventEntity, op: String, base: EventEntity? = null) {
        db.outboxDao().insert(
            OutboxEntity(
                eventId = e.id, calendarId = e.calendarId, operation = op,
                payload = OutboxPusher.JSON.encodeToString(e), base = base?.let { OutboxPusher.JSON.encodeToString(it) },
            ),
        )
    }

    private suspend fun push() = pusher.push(me, "token") { recomputed += it }

    @Test
    fun createdEventGetsItsGoogleId() = runTest {
        val e = event("A")
        db.eventDao().upsert(listOf(e))
        queue(e, OutboxEntity.CREATE)
        server.enqueue(MockResponse.Builder().body(googleJson("g1", "\"1\"", "A")).build())

        assertFalse(push())

        assertEquals("POST", server.takeRequest().method)
        val saved = db.eventDao().get("e1")!!
        assertEquals("g1", saved.remoteId)
        assertEquals("\"1\"", saved.etag)
        assertTrue(db.outboxDao().getAll().isEmpty())
    }

    @Test
    fun precondition412WithUnchangedContentIsRetriedWithTheNewEtag() = runTest {
        val base = event("A", "g1", "\"1\"")
        val edited = base.copy(title = "B")
        db.eventDao().upsert(listOf(edited))
        queue(edited, OutboxEntity.UPDATE, base)
        server.enqueue(MockResponse.Builder().code(412).build())
        // Google changed only what it changes on its own: the owner added as organizer guest.
        server.enqueue(MockResponse.Builder().body(googleJson("g1", "\"2\"", "A", ""","attendees":[{"email":"$me","organizer":true}]""")).build())
        server.enqueue(MockResponse.Builder().body(googleJson("g1", "\"3\"", "B")).build())

        assertFalse(push())

        assertEquals("\"1\"", server.takeRequest().headers["If-Match"])
        assertEquals("GET", server.takeRequest().method)
        assertEquals("\"2\"", server.takeRequest().headers["If-Match"])
        assertEquals("\"3\"", db.eventDao().get("e1")!!.etag)
        assertTrue(db.outboxDao().getAll().isEmpty())
    }

    @Test
    fun precondition412WithChangedContentKeepsGoogleAndCopiesTheLocalEdit() = runTest {
        val base = event("A", "g1", "\"1\"")
        val edited = base.copy(title = "B")
        db.eventDao().upsert(listOf(edited))
        queue(edited, OutboxEntity.UPDATE, base)
        server.enqueue(MockResponse.Builder().code(412).build())
        server.enqueue(MockResponse.Builder().body(googleJson("g1", "\"2\"", "C")).build())

        assertTrue(push())

        assertEquals("C", db.eventDao().get("e1")!!.title)
        val copy = db.eventDao().getForCalendar(calendarId).single { it.id != "e1" }
        assertEquals("B (conflict)", copy.title)
        val queued = db.outboxDao().getAll().single()
        assertEquals(copy.id, queued.eventId)
        assertEquals(OutboxEntity.CREATE, queued.operation)
        assertFalse(queued.sendUpdates)
        assertEquals("conflict", db.syncMessageDao().observeUnread().first().single().kind)
        assertTrue(copy.id in recomputed)
    }

    @Test
    fun permanentRejectionDoesNotBlockTheQueue() = runTest {
        val first = event("A")
        val second = first.copy(id = "e2", title = "B")
        db.eventDao().upsert(listOf(first, second))
        queue(first, OutboxEntity.CREATE)
        queue(second, OutboxEntity.CREATE)
        server.enqueue(MockResponse.Builder().code(403).body("forbidden").build())
        server.enqueue(MockResponse.Builder().body(googleJson("g2", "\"1\"", "B")).build())

        push()

        assertTrue(db.outboxDao().getAll().isEmpty())
        assertEquals("g2", db.eventDao().get("e2")!!.remoteId)
        assertEquals("error", db.syncMessageDao().observeUnread().first().single().kind)
    }

    @Test
    fun rateLimitKeepsTheQueueAndTheLocalEvent() = runTest {
        val first = event("A")
        val second = first.copy(id = "e2", title = "B")
        db.eventDao().upsert(listOf(first, second))
        queue(first, OutboxEntity.CREATE)
        queue(second, OutboxEntity.CREATE)
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":{"errors":[{"reason":"rateLimitExceeded"}],"message":"Calendar usage limits exceeded."}}""").build())

        push()

        // nothing was discarded, the order is kept and no error message was raised: the next sync tries again
        assertEquals(2, db.outboxDao().getAll().size)
        assertEquals(1, db.outboxDao().getAll().first().attempts)
        assertTrue(db.syncMessageDao().observeUnread().first().isEmpty())
        assertNotNull(db.eventDao().get("e1"))
    }

    @Test
    fun networkFailureKeepsTheQueue() = runTest {
        val e = event("A")
        db.eventDao().upsert(listOf(e))
        queue(e, OutboxEntity.CREATE)
        server.close() // connection refused

        push()

        val item = db.outboxDao().getAll().single()
        assertEquals(1, item.attempts)
        assertNotNull(item.lastError)
    }

    @Test
    fun deleteSendsTheGoogleId() = runTest {
        db.outboxDao().insert(OutboxEntity(eventId = "e1", calendarId = calendarId, operation = OutboxEntity.DELETE, payload = "g1"))
        server.enqueue(MockResponse.Builder().code(204).build())

        push()

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertTrue(request.url.encodedPath.endsWith("/events/g1"))
        assertTrue(db.outboxDao().getAll().isEmpty())
    }

    // U211
    @Test
    fun anExceptionWhoseSeriesWillNeverGoLeavesTheQueueInsteadOfWaitingForever() = runTest {
        val master = event("Serie") // no id on Google and nothing queued for it: its own entry was rejected and is gone
        val exception = event("Spostata").copy(id = "e2", seriesMasterId = master.id, originalStartUtc = master.startUtc)
        db.eventDao().upsert(listOf(master, exception))
        queue(exception, OutboxEntity.UPDATE)

        push()

        assertTrue(db.outboxDao().getAll().isEmpty())
        assertEquals(0, server.requestCount)
    }

    // U212
    @Test
    fun anExceptionWaitsWhileItsSeriesIsStillInTheQueue() = runTest {
        val master = event("Serie")
        val exception = event("Spostata").copy(id = "e2", seriesMasterId = master.id, originalStartUtc = master.startUtc)
        db.eventDao().upsert(listOf(master, exception))
        queue(master, OutboxEntity.IMPORT)
        queue(exception, OutboxEntity.UPDATE)
        server.enqueue(MockResponse.Builder().code(500).build()) // the series does not go now: the exception stays behind it

        push()

        assertEquals(2, db.outboxDao().getAll().size)
    }

    // U213
    @Test
    fun theEndOfAnImportIsToldOnceWithHowManyEventsGoogleTookAndHowManyNot() = runTest {
        val a = event("A")
        val b = event("B").copy(id = "e2")
        db.eventDao().upsert(listOf(a, b))
        queue(a, OutboxEntity.IMPORT)
        queue(b, OutboxEntity.IMPORT)
        reports.begin("Prova", db.outboxDao().getAll().map { it.seq })
        server.enqueue(MockResponse.Builder().body(googleJson("g1", "\"1\"", "A")).build())
        server.enqueue(MockResponse.Builder().code(400).body("{}").build()) // Google does not accept the second

        push()

        val info = db.syncMessageDao().observeUnread().first().filter { it.kind == "info" }
        assertEquals(1, info.size)
        assertTrue(info.single().text, info.single().text.contains("Prova") && info.single().text.contains("1") && info.single().text.contains("not accepted"))
        assertTrue(db.outboxDao().getAll().isEmpty())
    }

    // U214
    @Test
    fun anImportThatGoogleTookWholeSaysSoAndOnlyOnce() = runTest {
        val a = event("A")
        db.eventDao().upsert(listOf(a))
        queue(a, OutboxEntity.IMPORT)
        reports.begin("Prova", db.outboxDao().getAll().map { it.seq })
        server.enqueue(MockResponse.Builder().body(googleJson("g1", "\"1\"", "A")).build())

        push()
        push()

        val info = db.syncMessageDao().observeUnread().first().filter { it.kind == "info" }
        assertEquals(1, info.size)
        assertFalse(info.single().text.contains("not accepted"))
    }
}
