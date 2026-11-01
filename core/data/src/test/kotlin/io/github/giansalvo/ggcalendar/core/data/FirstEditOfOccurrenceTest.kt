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
import io.github.giansalvo.ggcalendar.core.network.GoogleEventMapper
import io.github.giansalvo.ggcalendar.core.network.OriginalStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The first edit of one occurrence of a series (issue 13). The occurrence has no row, id or etag of its own on Google yet: the push reads the
 * instance first, checks that it is still what the edit started from, and only then patches it with the etag it has just read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirstEditOfOccurrenceTest {
    private val me = "me@x.com"
    private val calendarId = "$me|$me"
    private val rome = ZoneId.of("Europe/Rome")
    private val server = MockWebServer()
    private lateinit var db: GGCalendarDatabase
    private lateinit var pusher: OutboxPusher
    private val recomputed = mutableListOf<String>()

    private fun ms(text: String) = LocalDateTime.parse(text).atZone(rome).toInstant().toEpochMilli()

    // Every Monday 10:00-11:00 from 5 October 2026; the occurrence of the 19th is edited.
    private val master = EventEntity(
        id = "m", calendarId = calendarId, remoteId = "g1", etag = "\"m1\"", title = "Lezione", rrule = "FREQ=WEEKLY;BYDAY=MO",
        startUtc = ms("2026-10-05T10:00"), endUtc = ms("2026-10-05T11:00"), timeZone = "Europe/Rome",
    )
    private val occ = Occurrence(ms("2026-10-19T10:00"), ms("2026-10-19T11:00"), LocalDate.parse("2026-10-19").toEpochDay(), allDay = false)
    private val instanceId = GoogleEventMapper.instanceId("g1", OriginalStart.Timed(Instant.ofEpochMilli(occ.beginUtc)))

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        server.start()
        pusher = OutboxPusher(context, db, GoogleCalendarApi(OkHttpClient(), server.url("/calendar/v3/")))
        db.accountDao().insert(AccountEntity(me))
        db.calendarDao().upsert(CalendarEntity(calendarId, me, me, "Me", "#1A73E8", "owner"))
        db.eventDao().upsert(listOf(master))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    /** The instance as Google answers for the 19th: the series' content on that day, with [title] and [extra] fields. */
    private fun instance(etag: String, title: String = "Lezione", extra: String = "") =
        """{"id":"$instanceId","etag":"${etag.replace("\"", "\\\"")}","summary":"$title","recurringEventId":"g1",
           "originalStartTime":{"dateTime":"2026-10-19T10:00:00+02:00","timeZone":"Europe/Rome"},
           "start":{"dateTime":"2026-10-19T10:00:00+02:00","timeZone":"Europe/Rome"},
           "end":{"dateTime":"2026-10-19T11:00:00+02:00","timeZone":"Europe/Rome"}$extra}"""

    private fun ok(body: String) = MockResponse.Builder().body(body).build()

    private fun status(code: Int) = MockResponse.Builder().code(code).build()

    /** Queues the first edit as SeriesEditor does: an exception row, and the occurrence as the series gave it as base. */
    private suspend fun queueEdit(newTitle: String = "Spostata", withBase: Boolean = true, cancelled: Boolean = false): EventEntity {
        val exception = SeriesOps.exception(master, occ, master.copy(title = newTitle)).copy(isCancelled = cancelled)
        db.eventDao().upsert(listOf(exception))
        db.outboxDao().insert(
            OutboxEntity(
                eventId = exception.id, calendarId = calendarId, operation = OutboxEntity.UPDATE,
                payload = OutboxPusher.JSON.encodeToString(exception),
                base = if (withBase) OutboxPusher.JSON.encodeToString(SeriesOps.occurrenceOf(master, occ)) else null,
            ),
        )
        return exception
    }

    private suspend fun push() = pusher.push(me, "token") { recomputed += it }

    @Test
    fun anUntouchedInstanceIsReadAndThenPatchedWithItsEtag() = runTest {
        val e = queueEdit()
        server.enqueue(ok(instance("\"i1\"")))
        server.enqueue(ok(instance("\"i2\"", title = "Spostata")))

        assertFalse(push())

        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertTrue(get.url.encodedPath.endsWith("/events/$instanceId"))
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("\"i1\"", patch.headers["If-Match"])
        assertEquals(2, server.requestCount)
        val saved = db.eventDao().get(e.id)!!
        assertEquals(instanceId, saved.remoteId)
        assertEquals("\"i2\"", saved.etag)
        assertEquals("Spostata", saved.title)
        assertTrue(db.outboxDao().getAll().isEmpty())
    }

    @Test
    fun anOccurrenceChangedOnGoogleIsAConflictAndNothingIsSent() = runTest {
        val e = queueEdit()
        server.enqueue(ok(instance("\"i1\"", title = "Modificata altrove")))

        assertTrue(push())

        assertEquals(1, server.requestCount) // only the read
        assertNull(db.eventDao().get(e.id)) // the modified occurrence is gone from here: the pull brings Google's
        val copy = db.eventDao().getForCalendar(calendarId).single { it.id != "m" }
        assertEquals("Spostata (conflict)", copy.title)
        assertNull(copy.seriesMasterId)
        val queued = db.outboxDao().getAll().single()
        assertEquals(copy.id, queued.eventId)
        assertEquals(OutboxEntity.CREATE, queued.operation)
        assertEquals("conflict", db.syncMessageDao().observeUnread().first().single().kind)
        assertTrue("m" in recomputed) // the series draws that day again
        assertTrue(copy.id in recomputed)
    }

    @Test
    fun aSeriesRenamedOnGoogleIsAConflictToo() = runTest {
        // The whole series was renamed: the instance carries the new title, which is not what the edit started from.
        queueEdit()
        server.enqueue(ok(instance("\"i1\"", title = "Lezione di prova")))

        assertTrue(push())

        assertEquals(1, server.requestCount)
        assertTrue(db.eventDao().getForCalendar(calendarId).any { it.title.endsWith("(conflict)") })
    }

    @Test
    fun anOccurrenceOrSeriesGoneFromGoogleIsAConflict() = runTest {
        for (code in listOf(404, 410)) {
            db.outboxDao().getAll().forEach { db.outboxDao().complete(it.seq) }
            queueEdit("Spostata $code")
            server.enqueue(status(code))
            assertTrue(push())
        }
        assertEquals(2, db.eventDao().getForCalendar(calendarId).count { it.title.endsWith("(conflict)") })
    }

    @Test
    fun whatGoogleAddsOnItsOwnIsNotAConflict() = runTest {
        // The owner added as organizer guest: the same exception that a normal event allows.
        val e = queueEdit()
        server.enqueue(ok(instance("\"i1\"", extra = ""","attendees":[{"email":"$me","organizer":true}]""")))
        server.enqueue(ok(instance("\"i2\"", title = "Spostata")))

        assertFalse(push())

        assertEquals(2, server.requestCount)
        assertEquals(instanceId, db.eventDao().get(e.id)!!.remoteId)
    }

    @Test
    fun anInstanceChangedBetweenTheReadAndThePatchIsRetriedWhenTheContentIsStillTheSame() = runTest {
        val e = queueEdit()
        server.enqueue(ok(instance("\"i1\"")))
        server.enqueue(status(412))
        server.enqueue(ok(instance("\"i2\"")))
        server.enqueue(ok(instance("\"i3\"", title = "Spostata")))

        assertFalse(push())

        assertEquals("GET", server.takeRequest().method)
        assertEquals("\"i1\"", server.takeRequest().headers["If-Match"])
        assertEquals("GET", server.takeRequest().method)
        assertEquals("\"i2\"", server.takeRequest().headers["If-Match"])
        assertEquals("\"i3\"", db.eventDao().get(e.id)!!.etag)
    }

    @Test
    fun anInstanceChangedBetweenTheReadAndThePatchWithOtherContentIsAConflict() = runTest {
        val e = queueEdit()
        server.enqueue(ok(instance("\"i1\"")))
        server.enqueue(status(412))
        server.enqueue(ok(instance("\"i2\"", title = "Cambiata nel frattempo")))

        assertTrue(push())

        assertEquals(3, server.requestCount)
        assertNull(db.eventDao().get(e.id))
        assertTrue(db.eventDao().getForCalendar(calendarId).any { it.title == "Spostata (conflict)" })
        // Google's version of the virtual instance is never written as an exception row
        assertTrue(db.eventDao().getForCalendar(calendarId).none { it.remoteId == instanceId })
    }

    @Test
    fun anItemQueuedByTheVersionBeforeHasNoBaseAndIsNeverAConflict() = runTest {
        val e = queueEdit(withBase = false)
        server.enqueue(ok(instance("\"i1\"", title = "Qualunque cosa")))
        server.enqueue(ok(instance("\"i2\"", title = "Spostata")))

        assertFalse(push())

        assertEquals("\"i1\"", server.takeRequest().let { server.takeRequest() }.headers["If-Match"])
        assertEquals(instanceId, db.eventDao().get(e.id)!!.remoteId)
    }

    @Test
    fun aCancelledOccurrenceIsNotCheckedAndGoesAsBefore() = runTest {
        queueEdit(cancelled = true)
        server.enqueue(ok(instance("\"i1\"", title = "Spostata")))

        assertFalse(push())

        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertNull(patch.headers["If-Match"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aLimitOfRequestsOnTheReadKeepsTheQueueAndTheEdit() = runTest {
        val e = queueEdit()
        server.enqueue(status(429))

        assertFalse(push())

        assertEquals(1, db.outboxDao().getAll().size)
        assertEquals(1, db.outboxDao().getAll().single().attempts)
        assertNotNull(db.eventDao().get(e.id))
        assertTrue(db.syncMessageDao().observeUnread().first().isEmpty())
    }

    @Test
    fun theSecondEditOfAnAlreadySentOccurrenceKeepsUsingItsOwnEtag() = runTest {
        val sent = SeriesOps.exception(master, occ, master.copy(title = "Spostata")).copy(remoteId = instanceId, etag = "\"x1\"")
        db.eventDao().upsert(listOf(sent))
        val again = sent.copy(title = "Ancora")
        db.eventDao().upsert(listOf(again))
        db.outboxDao().insert(
            OutboxEntity(
                eventId = again.id, calendarId = calendarId, operation = OutboxEntity.UPDATE,
                payload = OutboxPusher.JSON.encodeToString(again), base = OutboxPusher.JSON.encodeToString(sent),
            ),
        )
        server.enqueue(ok(instance("\"x2\"", title = "Ancora")))

        assertFalse(push())

        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method) // no read: the row has its id and etag
        assertEquals("\"x1\"", patch.headers["If-Match"])
        assertEquals(1, server.requestCount)
    }
}
