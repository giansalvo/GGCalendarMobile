package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import io.github.giansalvo.ggcalendar.core.auth.GoogleAuthorizer
import io.github.giansalvo.ggcalendar.core.database.AccountEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarKind
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.OutboxEntity
import io.github.giansalvo.ggcalendar.core.database.ReminderSnoozedEntity
import io.github.giansalvo.ggcalendar.core.network.GoogleApiException
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

/** Making, changing and deleting the calendars of a Google account (issue #14). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoogleCalendarsTest {
    private lateinit var context: Context
    private lateinit var db: GGCalendarDatabase
    private lateinit var service: GoogleCalendars
    private lateinit var engine: SyncEngine
    private val server = MockWebServer()
    private var changedReminders = 0
    private val email = "me@x.com"
    private val tokens = GoogleCalendars.Tokens({ "tok" }, {})

    private fun idOf(remote: String) = "$email|$remote"

    @Before
    fun setUp() = runTest {
        server.start()
        context = ApplicationProvider.getApplicationContext()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        val api = GoogleCalendarApi(OkHttpClient(), server.url("/calendar/v3/"))
        engine = SyncEngine(db, api, GoogleAuthorizer(context), AppPreferences(context), OutboxPusher(context, db, api))
        val hooks = ReminderHooks(setOf(object : ReminderHook {
            override fun remindersChanged() { changedReminders++ }
        }))
        service = GoogleCalendars(db, api, GoogleAuthorizer(context), engine, hooks)
        db.accountDao().insert(AccountEntity(email))
        db.calendarDao().upsert(CalendarEntity(idOf(email), email, email, "Me", "#1A73E8", "owner", isPrimary = true))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun ok(body: String = "{}") = MockResponse.Builder().body(body).build()

    private fun status(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()

    private suspend fun addOwned(remote: String, name: String = "Palestra", role: String = "owner", color: String = "#F4511E") {
        db.calendarDao().upsert(CalendarEntity(idOf(remote), email, remote, name, color, role))
    }

    private fun body(i: Int = 0): kotlinx.serialization.json.JsonObject {
        val requests = (0..i).map { server.takeRequest() }
        return Json.parseToJsonElement(requests.last().body!!.utf8()).jsonObject
    }

    // ---- names ----

    @Test
    fun theNameIsTrimmedOnOneLineAndCutAt255() {
        assertEquals("Casa", GoogleCalendars.cleanName("  Casa \n"))
        assertEquals("a b", GoogleCalendars.cleanName("a\nb"))
        assertEquals(255, GoogleCalendars.cleanName("x".repeat(300)).length)
        assertEquals("", GoogleCalendars.cleanName(" \t\n "))
    }

    @Test
    fun aCutNeverSplitsAPairOfSurrogates() {
        // 254 letters and then an emoji (two chars): the limit falls in the middle of it.
        val cut = GoogleCalendars.cleanName("x".repeat(254) + "🎾" + "tail")
        assertEquals(254, cut.length)
        assertFalse(Character.isHighSurrogate(cut.last()))
    }

    // ---- create ----

    @Test
    fun anEmptyNameAsksNothingOfGoogle() = runTest {
        val r = service.create(email, "   ", "#039BE5", tokens, "Europe/Rome")
        assertEquals(GoogleCalendarProblem.EMPTY_NAME, r.problem)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anUnknownAccountAsksNothingOfGoogle() = runTest {
        val r = service.create("other@x.com", "Palestra", "#039BE5", tokens, "Europe/Rome")
        assertEquals(GoogleCalendarProblem.NO_ACCOUNT, r.problem)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aNewCalendarIsWrittenAtOnceOwnedAndVisible() = runTest {
        server.enqueue(ok("""{"id":"new1@group.calendar.google.com"}"""))
        server.enqueue(ok())
        val r = service.create(email, "  Corso di cucina ", "#8E24AA", tokens, "Europe/Rome")
        assertTrue(r.ok)
        assertEquals(idOf("new1@group.calendar.google.com"), r.calendarId)
        val c = db.calendarDao().get(r.calendarId!!)!!
        assertEquals("Corso di cucina", c.name)
        assertEquals("#8E24AA", c.color)
        assertEquals("owner", c.accessRole)
        assertEquals("new1@group.calendar.google.com", c.remoteId)
        assertEquals(email, c.accountEmail)
        assertEquals(CalendarKind.GOOGLE, c.kind)
        assertTrue(c.isVisible)
        assertFalse(c.isPrimary)
        assertEquals("Europe/Rome", c.timeZone)
        // the name and the zone to calendars.insert, then the color to the list entry
        val insert = server.takeRequest()
        assertEquals("POST", insert.method)
        val sent = Json.parseToJsonElement(insert.body!!.utf8()).jsonObject
        assertEquals("Corso di cucina", sent["summary"]!!.jsonPrimitive.content)
        assertEquals("Europe/Rome", sent["timeZone"]!!.jsonPrimitive.content)
        val color = server.takeRequest()
        assertEquals("PATCH", color.method)
        assertEquals("#8E24AA", Json.parseToJsonElement(color.body!!.utf8()).jsonObject["backgroundColor"]!!.jsonPrimitive.content)
    }

    @Test
    fun aLongNameGoesToGoogleCutAt255() = runTest {
        server.enqueue(ok("""{"id":"n"}"""))
        server.enqueue(ok())
        service.create(email, "y".repeat(300), "#039BE5", tokens, "UTC")
        assertEquals(255, body()["summary"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun aColorGoogleRefusesDoesNotUndoTheCalendar() = runTest {
        server.enqueue(ok("""{"id":"n"}"""))
        server.enqueue(status(500))
        val r = service.create(email, "Palestra", "#039BE5", tokens, "UTC")
        assertTrue(r.ok)
        assertEquals("#039BE5", db.calendarDao().get(idOf("n"))!!.color)
    }

    @Test
    fun whenGoogleRefusesNothingIsWritten() = runTest {
        val cases = listOf(
            status(403, """{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}""") to GoogleCalendarProblem.RATE_LIMITED,
            status(429) to GoogleCalendarProblem.RATE_LIMITED,
            status(403, """{"error":{"errors":[{"reason":"forbidden"}]}}""") to GoogleCalendarProblem.NOT_ALLOWED,
            status(500) to GoogleCalendarProblem.SERVER,
            status(503) to GoogleCalendarProblem.SERVER,
            status(400) to GoogleCalendarProblem.OTHER,
        )
        cases.forEach { (response, problem) ->
            server.enqueue(response)
            assertEquals(problem, service.create(email, "Palestra", "#039BE5", tokens, "UTC").problem)
        }
        assertEquals(1, db.calendarDao().getForAccount(email).size) // only the primary
    }

    @Test
    fun withoutANetworkNothingIsDoneAndItIsSaid() = runTest {
        server.close()
        val r = service.create(email, "Palestra", "#039BE5", tokens, "UTC")
        assertEquals(GoogleCalendarProblem.NO_NETWORK, r.problem)
        assertEquals(1, db.calendarDao().getForAccount(email).size)
    }

    @Test
    fun theKindOfNetworkFailureTellsWhetherGoogleMayHaveDoneIt() {
        assertEquals(GoogleCalendarProblem.NO_NETWORK, GoogleCalendars.problemOf(UnknownHostException("x")))
        assertEquals(GoogleCalendarProblem.NO_NETWORK, GoogleCalendars.problemOf(ConnectException("x")))
        assertEquals(GoogleCalendarProblem.MAYBE_DONE, GoogleCalendars.problemOf(SocketTimeoutException("timeout")))
        assertEquals(GoogleCalendarProblem.MAYBE_DONE, GoogleCalendars.problemOf(java.io.IOException("unexpected end of stream")))
        assertEquals(GoogleCalendarProblem.AUTH_EXPIRED, GoogleCalendars.problemOf(GoogleApiException(401, "x")))
        assertEquals(GoogleCalendarProblem.OTHER, GoogleCalendars.problemOf(IllegalStateException()))
    }

    @Test
    fun aRejectedTokenIsReplacedOnce() = runTest {
        server.enqueue(status(401))
        server.enqueue(ok("""{"id":"n"}"""))
        server.enqueue(ok())
        val given = AtomicInteger()
        val invalidated = mutableListOf<String>()
        val r = service.create(email, "Palestra", "#039BE5", GoogleCalendars.Tokens({ "t" + given.incrementAndGet() }, { invalidated += it }), "UTC")
        assertTrue(r.ok)
        assertEquals(listOf("t1"), invalidated)
        assertEquals("Bearer t1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer t2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun withoutConsentNothingIsAskedAndTheSnackbarOffersToAuthorizeAgain() = runTest {
        val denied = GoogleCalendars.Tokens({ throw ConsentRequiredException() }, {})
        assertEquals(GoogleCalendarProblem.AUTH_EXPIRED, service.create(email, "Palestra", "#039BE5", denied, "UTC").problem)
        assertEquals(0, server.requestCount)
        assertTrue(db.syncMessageDao().hasUnread(email, "auth"))
    }

    @Test
    fun aSyncThatWroteTheCalendarFirstKeepsGooglesValues() = runTest {
        server.enqueue(ok("""{"id":"n"}"""))
        server.enqueue(ok())
        addOwned("n", name = "From Google", color = "#33B679")
        db.calendarDao().setVisible(idOf("n"), false)
        service.create(email, "Palestra", "#039BE5", tokens, "UTC")
        val c = db.calendarDao().get(idOf("n"))!!
        assertEquals("From Google", c.name)
        assertEquals("#33B679", c.color)
        assertFalse(c.isVisible)
    }

    // The sync reads Google's list, then deletes the calendars that are not in it: the new calendar must not be written in between.
    @Test
    fun theNewCalendarIsWrittenOnlyAfterTheRunningSyncIsDone() = runTest {
        server.enqueue(ok("""{"id":"n"}"""))
        server.enqueue(ok())
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        launch(Dispatchers.Default) { engine.exclusive { held.complete(Unit); release.await() } }
        held.await()
        val created = async { service.create(email, "Palestra", "#039BE5", tokens, "UTC") }
        withContext(Dispatchers.Default) { while (server.requestCount < 2) delay(10) }
        withContext(Dispatchers.Default) { delay(200) }
        assertNull(db.calendarDao().get(idOf("n")))
        release.complete(Unit)
        assertTrue(created.await().ok)
        assertNotNull(db.calendarDao().get(idOf("n")))
    }

    @Test
    fun aCalendarMadeForOneAccountIsNotInTheOther() = runTest {
        db.accountDao().insert(AccountEntity("b@x.com"))
        server.enqueue(ok("""{"id":"n"}"""))
        server.enqueue(ok())
        service.create("b@x.com", "Palestra", "#039BE5", tokens, "UTC")
        assertEquals(listOf("b@x.com|n"), db.calendarDao().getForAccount("b@x.com").map { it.id })
        assertEquals(listOf(idOf(email)), db.calendarDao().getForAccount(email).map { it.id })
    }

    // ---- update ----

    @Test
    fun renamingAndRecoloringGoToTheCalendarAndToTheListEntry() = runTest {
        addOwned("g1", name = "Famiglia", color = "#33B679")
        server.enqueue(ok())
        server.enqueue(ok())
        val r = service.update(idOf("g1"), " Famiglia e amici ", "#E67C73", tokens)
        assertTrue(r.ok)
        assertNull(r.partial)
        val rename = server.takeRequest()
        assertEquals("/calendar/v3/calendars/g1", rename.url.encodedPath)
        assertEquals("Famiglia e amici", Json.parseToJsonElement(rename.body!!.utf8()).jsonObject["summary"]!!.jsonPrimitive.content)
        val entry = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("#E67C73", entry["backgroundColor"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, entry["summaryOverride"]) // the personal name would hide the new name
        val c = db.calendarDao().get(idOf("g1"))!!
        assertEquals("Famiglia e amici", c.name)
        assertEquals("#E67C73", c.color)
    }

    @Test
    fun aColorAloneTouchesOnlyTheListEntry() = runTest {
        addOwned("g1", name = "Famiglia", color = "#33B679")
        server.enqueue(ok())
        assertTrue(service.update(idOf("g1"), "Famiglia", "#E67C73", tokens).ok)
        assertEquals(1, server.requestCount)
        val entry = body()
        assertFalse(entry.containsKey("summaryOverride"))
        assertEquals("Famiglia", db.calendarDao().get(idOf("g1"))!!.name)
        assertEquals("#E67C73", db.calendarDao().get(idOf("g1"))!!.color)
    }

    @Test
    fun aNameAloneKeepsTheColor() = runTest {
        addOwned("g1", name = "Famiglia", color = "#33B679")
        server.enqueue(ok())
        server.enqueue(ok())
        assertTrue(service.update(idOf("g1"), "Casa", null, tokens).ok)
        server.takeRequest()
        assertFalse(body().containsKey("backgroundColor"))
        assertEquals("#33B679", db.calendarDao().get(idOf("g1"))!!.color)
    }

    @Test
    fun nothingChangedAsksNothingOfGoogle() = runTest {
        addOwned("g1", name = "Famiglia", color = "#33B679")
        assertTrue(service.update(idOf("g1"), " Famiglia ", "#33b679", tokens).ok)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theNameOfACalendarOfSomeoneElseCannotChangeButItsColorCan() = runTest {
        addOwned("g1", name = "Calcio", role = "writer", color = "#33B679")
        assertEquals(GoogleCalendarProblem.NOT_OWNER, service.update(idOf("g1"), "Altro", null, tokens).problem)
        assertEquals(0, server.requestCount)
        server.enqueue(ok())
        assertTrue(service.update(idOf("g1"), null, "#7986CB", tokens).ok)
        assertEquals("#7986CB", db.calendarDao().get(idOf("g1"))!!.color)
        assertEquals("Calcio", db.calendarDao().get(idOf("g1"))!!.name)
    }

    @Test
    fun thePrimaryCalendarIsNotRenamedAndAnEmptyNameIsRefused() = runTest {
        assertEquals(GoogleCalendarProblem.PRIMARY, service.update(idOf(email), "Altro", null, tokens).problem)
        addOwned("g1")
        assertEquals(GoogleCalendarProblem.EMPTY_NAME, service.update(idOf("g1"), "  ", null, tokens).problem)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRenameThatGoogleAcceptsStaysEvenIfTheColorIsRefused() = runTest {
        addOwned("g1", name = "Famiglia", color = "#33B679")
        server.enqueue(ok())
        server.enqueue(status(500))
        val r = service.update(idOf("g1"), "Casa", "#E67C73", tokens)
        assertTrue(r.ok)
        assertEquals(GoogleCalendarProblem.SERVER, r.partial)
        val c = db.calendarDao().get(idOf("g1"))!!
        assertEquals("Casa", c.name)
        assertEquals("#33B679", c.color)
    }

    @Test
    fun aRefusedChangeLeavesTheCalendarAsItWas() = runTest {
        addOwned("g1", name = "Famiglia", color = "#33B679")
        server.enqueue(status(403, """{"error":{"errors":[{"reason":"forbidden"}]}}"""))
        assertEquals(GoogleCalendarProblem.NOT_ALLOWED, service.update(idOf("g1"), "Casa", "#E67C73", tokens).problem)
        val c = db.calendarDao().get(idOf("g1"))!!
        assertEquals("Famiglia", c.name)
        assertEquals("#33B679", c.color)
    }

    // ---- delete ----

    private fun event(id: String, calendarId: String) =
        EventEntity(id = id, calendarId = calendarId, title = "Event", startUtc = 1_800_000_000_000, endUtc = 1_800_003_600_000)

    @Test
    fun deletingTakesTheEventsTheQueueAndTheSnoozesOfThatCalendarOnly() = runTest {
        addOwned("g1")
        addOwned("g2", name = "Altro")
        db.eventDao().upsert(listOf(event("e1", idOf("g1"))))
        db.eventDao().upsert(listOf(event("e2", idOf("g2"))))
        db.outboxDao().insert(OutboxEntity(eventId = "e1", calendarId = idOf("g1"), operation = OutboxEntity.CREATE, payload = "{}"))
        db.outboxDao().insert(OutboxEntity(eventId = "e2", calendarId = idOf("g2"), operation = OutboxEntity.UPDATE, payload = "{}"))
        // an event of another calendar that was being moved into the deleted one
        db.outboxDao().insert(OutboxEntity(eventId = "e2", calendarId = idOf("g2"), operation = OutboxEntity.MOVE, payload = idOf("g1")))
        db.reminderDao().insertSnoozed(ReminderSnoozedEntity(fireAt = 5, eventId = "e1", beginUtc = 1_800_000_000_000, method = "POPUP", minutes = 10))
        server.enqueue(status(204))
        val r = service.delete(idOf("g1"), tokens)
        assertTrue(r.ok)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/calendar/v3/calendars/g1", request.url.encodedPath)
        assertNull(db.calendarDao().get(idOf("g1")))
        assertNull(db.eventDao().get("e1"))
        assertNotNull(db.calendarDao().get(idOf("g2")))
        assertNotNull(db.eventDao().get("e2"))
        assertEquals(listOf(OutboxEntity.UPDATE), db.outboxDao().getAll().map { it.operation })
        assertTrue(db.reminderDao().snoozed().isEmpty())
        assertEquals(1, changedReminders)
    }

    @Test
    fun thePrimaryCalendarAndTheOnesOfOthersAreRefusedWithoutAskingGoogle() = runTest {
        addOwned("g1", role = "writer")
        addOwned("g2", role = "reader")
        assertEquals(GoogleCalendarProblem.PRIMARY, service.delete(idOf(email), tokens).problem)
        assertEquals(GoogleCalendarProblem.NOT_OWNER, service.delete(idOf("g1"), tokens).problem)
        assertEquals(GoogleCalendarProblem.NOT_OWNER, service.delete(idOf("g2"), tokens).problem)
        assertEquals(0, server.requestCount)
        assertEquals(3, db.calendarDao().getForAccount(email).size)
    }

    @Test
    fun aLocalCalendarOrAnUnknownOneIsNotDeletedFromHere() = runTest {
        db.calendarDao().upsert(
            CalendarEntity("local:1", null, null, "Casa", "#000000", "owner", kind = CalendarKind.LOCAL),
        )
        assertEquals(GoogleCalendarProblem.OTHER, service.delete("local:1", tokens).problem)
        assertEquals(GoogleCalendarProblem.OTHER, service.delete("nothing", tokens).problem)
        assertEquals(0, server.requestCount)
        assertNotNull(db.calendarDao().get("local:1"))
    }

    @Test
    fun aCalendarAlreadyDeletedOnGoogleDisappearsHereToo() = runTest {
        addOwned("g1")
        server.enqueue(status(404))
        assertTrue(service.delete(idOf("g1"), tokens).ok)
        assertNull(db.calendarDao().get(idOf("g1")))
    }

    @Test
    fun aRefusedOrFailedDeleteLeavesEverythingInPlace() = runTest {
        addOwned("g1")
        db.eventDao().upsert(listOf(event("e1", idOf("g1"))))
        db.outboxDao().insert(OutboxEntity(eventId = "e1", calendarId = idOf("g1"), operation = OutboxEntity.CREATE, payload = "{}"))
        server.enqueue(status(403, """{"error":{"errors":[{"reason":"forbidden"}]}}"""))
        assertEquals(GoogleCalendarProblem.NOT_ALLOWED, service.delete(idOf("g1"), tokens).problem)
        server.close()
        assertEquals(GoogleCalendarProblem.NO_NETWORK, service.delete(idOf("g1"), tokens).problem)
        assertNotNull(db.calendarDao().get(idOf("g1")))
        assertNotNull(db.eventDao().get("e1"))
        assertEquals(1, db.outboxDao().getAll().size)
        assertEquals(0, changedReminders)
    }

    @Test
    fun theQueueOfADeletedCalendarIsNeverSentAfterwards() = runTest {
        addOwned("g1")
        db.outboxDao().insert(OutboxEntity(eventId = "e1", calendarId = idOf("g1"), operation = OutboxEntity.CREATE, payload = "{}"))
        server.enqueue(status(204))
        service.delete(idOf("g1"), tokens)
        assertTrue(db.outboxDao().pendingEventIds().isEmpty())
    }
}
