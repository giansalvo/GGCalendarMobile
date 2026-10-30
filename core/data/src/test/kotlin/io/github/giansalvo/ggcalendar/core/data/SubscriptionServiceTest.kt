package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import io.github.giansalvo.ggcalendar.core.auth.GoogleAuthorizer
import io.github.giansalvo.ggcalendar.core.database.CalendarKind
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import io.github.giansalvo.ggcalendar.core.network.IcsFetcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import io.github.giansalvo.ggcalendar.core.model.EventAttachment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.Collections

/** Calendars read from an address: making them, bringing them up to date by difference, and what they never do (U128-U130, U154-U166). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubscriptionServiceTest {
    private val server = MockWebServer()
    private lateinit var db: GGCalendarDatabase
    private lateinit var service: SubscriptionService
    private lateinit var cipher: FakeCipher
    private var now = 1_000_000_000_000L

    /** A cipher that can be told to lose its key. */
    private class FakeCipher : UrlCipher {
        var lost = false
        override fun encrypt(plain: String) = "v1:" + Base64.getEncoder().encodeToString(plain.reversed().toByteArray())
        override fun decrypt(stored: String): String? =
            if (lost || !stored.startsWith("v1:")) null else String(Base64.getDecoder().decode(stored.removePrefix("v1:"))).reversed()
    }

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.start()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
        val context: Context = ApplicationProvider.getApplicationContext()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        val api = GoogleCalendarApi(client, server.url("/calendar/v3/"))
        val engine = SyncEngine(db, api, GoogleAuthorizer(context), AppPreferences(context), OutboxPusher(context, db, api))
        cipher = FakeCipher()
        prefs = AppPreferences(context)
        runBlocking { prefs.setSubscriptionMinutes(AppPreferences.DEFAULT_SUBSCRIPTION_MINUTES) } // the store outlives a test
        service = SubscriptionService(db, IcsFetcher(client), cipher, engine, ReminderHooks(emptySet()), prefs)
        service.clock = { now }
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    // ---- helpers

    private val soon: LocalDate get() = LocalDate.now().plusDays(3)

    private fun stamp(day: LocalDate, hour: Int) = "%04d%02d%02dT%02d0000Z".format(day.year, day.monthValue, day.dayOfMonth, hour)

    private fun vevent(uid: String, title: String, day: LocalDate = soon, hour: Int = 9, extra: String = "") =
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20261021T000000Z\r\nDTSTART:${stamp(day, hour)}\r\nDTEND:${stamp(day, hour + 1)}\r\nSUMMARY:$title\r\n$extra" + "END:VEVENT\r\n"

    private fun ics(vararg events: String, name: String? = "Calendario di prova") = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n")
        name?.let { append("X-WR-CALNAME:$it\r\n") }
        events.forEach(::append)
        append("END:VCALENDAR\r\n")
    }

    private fun serve(body: String, etag: String? = null) {
        val builder = MockResponse.Builder().body(body)
        etag?.let { builder.addHeader("ETag", it) }
        server.enqueue(builder.build())
    }

    private val address get() = server.url("/calendar/ical/someone%40example.org/public/basic.ics").toString()
    private val secretAddress get() = server.url("/calendar/ical/someone%40example.org/private-0123456789abcdef0123456789abcdef/basic.ics").toString()

    private suspend fun add(body: String, range: SubscriptionRange = SubscriptionRange(), url: String = address, name: String = ""): String {
        serve(body)
        val preview = (service.preview(url) as PreviewResult.Ok).preview
        return (service.add(preview, name, "#0B8043", range) as AddResult.Added).calendarId
    }

    private suspend fun events(calendarId: String) = db.eventDao().getForCalendar(calendarId).associateBy { it.id }

    private suspend fun instancesOf(day: LocalDate) =
        db.instanceDao().observeRange(day.toEpochDay(), day.toEpochDay() + 1).first()

    // ---- U154: reading before saving

    @Test
    fun `the preview reads the name, the count and the range and writes nothing`() = runTest {
        val later = soon.plusDays(10)
        serve(ics(vevent("a@x", "Uno"), vevent("b@x", "Due", later)))
        val p = (service.preview(address) as PreviewResult.Ok).preview
        assertEquals("Calendario di prova", p.name)
        assertEquals(2, p.eventCount)
        assertEquals(soon, p.firstDay)
        assertEquals(later, p.lastDay)
        assertFalse(p.isSecretAddress)
        assertTrue(db.calendarDao().getByKind(CalendarKind.SUBSCRIPTION).isEmpty())
        assertFalse(p.toString().contains("someone"))
    }

    @Test
    fun `the preview tells a secret address and refuses what it cannot use`() = runTest {
        serve(ics(vevent("a@x", "Uno")))
        assertTrue((service.preview(secretAddress) as PreviewResult.Ok).preview.isSecretAddress)
        assertEquals(PreviewResult.Problem(SubscriptionProblem.BAD_ADDRESS), service.preview(address.replaceFirst("https:", "http:")))
        server.enqueue(MockResponse.Builder().code(404).body("no").build())
        assertEquals(PreviewResult.Problem(SubscriptionProblem.NOT_FOUND, 404), service.preview(address))
        serve("<html>Sign in</html>")
        assertEquals(PreviewResult.Problem(SubscriptionProblem.NOT_CALENDAR, 200), service.preview(address))
        serve("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n")
        assertTrue(service.preview(address) is PreviewResult.Ok)
    }

    @Test
    fun `more than 5000 events are refused when all of them are kept`() = runTest {
        serve(ics(*Array(SubscriptionService.MAX_EVENTS + 1) { vevent("e$it@x", "E$it") }))
        val preview = (service.preview(address) as PreviewResult.Ok).preview // the days may bring the count under the limit
        assertEquals(SubscriptionService.MAX_EVENTS + 1, preview.eventCount)
        assertEquals(AddResult.Problem(SubscriptionProblem.TOO_MANY_EVENTS), service.add(preview, "", "#0B8043"))
        assertTrue(db.calendarDao().getByKind(CalendarKind.SUBSCRIPTION).isEmpty())
    }

    // ---- U167-U172: the days of a subscription

    private fun days(from: LocalDate?, to: LocalDate?) = SubscriptionRange(from, to)

    @Test
    fun `the count of the dialog follows the days chosen`() = runTest {
        val d = soon
        serve(ics(vevent("a@x", "Uno", d), vevent("b@x", "Due", d.plusDays(5)), vevent("c@x", "Tre", d.plusDays(10))))
        val preview = (service.preview(address) as PreviewResult.Ok).preview
        assertEquals(3, service.countIn(preview, SubscriptionRange()))
        assertEquals(2, service.countIn(preview, days(d.plusDays(5), null)))
        assertEquals(2, service.countIn(preview, days(null, d.plusDays(5))))
        assertEquals(1, service.countIn(preview, days(d.plusDays(5), d.plusDays(5))))
        assertEquals(0, service.countIn(preview, days(d.plusDays(11), null)))
    }

    @Test
    fun `a subscription keeps only the events of its days and remembers the days`() = runTest {
        val d = soon
        serve(ics(vevent("a@x", "Uno", d), vevent("b@x", "Due", d.plusDays(5)), vevent("c@x", "Tre", d.plusDays(10))))
        val preview = (service.preview(address) as PreviewResult.Ok).preview
        val id = (service.add(preview, "", "#0B8043", days(d.plusDays(4), d.plusDays(6))) as AddResult.Added).calendarId
        assertEquals(listOf("Due"), events(id).values.map { it.title })
        assertEquals(days(d.plusDays(4), d.plusDays(6)), service.rangeOf(id))
        assertEquals(1, instancesOf(d.plusDays(5)).size)
        assertTrue(instancesOf(d).isEmpty())
    }

    @Test
    fun `the days still apply when the calendar is read again`() = runTest {
        val d = soon
        serve(ics(vevent("a@x", "Uno", d), vevent("b@x", "Due", d.plusDays(5))))
        val preview = (service.preview(address) as PreviewResult.Ok).preview
        val id = (service.add(preview, "", "#0B8043", days(d.plusDays(4), null)) as AddResult.Added).calendarId
        now += 3_600_000
        serve(ics(vevent("a@x", "Uno", d), vevent("b@x", "Due", d.plusDays(5)), vevent("n@x", "Nuovo", d.plusDays(6)), vevent("o@x", "Vecchio", d.plusDays(1))))
        service.refresh(id)
        assertEquals(setOf("Due", "Nuovo"), events(id).values.map { it.title }.toSet())
    }

    @Test
    fun `the limit of events counts the events kept and not the file`() = runTest {
        val d = soon
        val many = Array(SubscriptionService.MAX_EVENTS + 1) { vevent("e$it@x", "E$it", if (it == 0) d else d.plusDays(30)) }
        serve(ics(*many))
        val preview = (service.preview(address) as PreviewResult.Ok).preview
        assertEquals(AddResult.Problem(SubscriptionProblem.TOO_MANY_EVENTS), service.add(preview, "", "#0B8043"))
        val id = (service.add(preview, "", "#0B8043", days(null, d.plusDays(1))) as AddResult.Added).calendarId
        assertEquals(1, events(id).size)
    }

    @Test
    fun `a series that goes on is drawn only inside the days`() = runTest {
        val d = soon
        serve(ics(vevent("s@x", "Serie", d.minusDays(20), extra = "RRULE:FREQ=DAILY\r\n")))
        val preview = (service.preview(address) as PreviewResult.Ok).preview
        val id = (service.add(preview, "", "#0B8043", days(d, d.plusDays(2))) as AddResult.Added).calendarId
        assertEquals(1, events(id).size) // the series is saved once
        assertTrue(instancesOf(d.minusDays(1)).isEmpty())
        assertEquals(1, instancesOf(d).size)
        assertEquals(1, instancesOf(d.plusDays(2)).size)
        assertTrue(instancesOf(d.plusDays(3)).isEmpty())
    }

    @Test
    fun `changing the days reads the address again and applies them to the new copy`() = runTest {
        val d = soon
        val body = ics(vevent("a@x", "Uno", d), vevent("b@x", "Due", d.plusDays(5)), vevent("c@x", "Tre", d.plusDays(10)))
        val id = add(body, days(d.plusDays(5), d.plusDays(5)))
        assertEquals(listOf("Due"), events(id).values.map { it.title })
        val requests = server.requestCount
        // the same content at the other end: the old copy is not used, the days are applied to the new one
        serve(body)
        val result = service.setRange(id, days(null, d.plusDays(5)))
        assertTrue(result is RefreshResult.Updated)
        assertEquals(requests + 1, server.requestCount)
        assertEquals(setOf("Uno", "Due"), events(id).values.map { it.title }.toSet())
        assertEquals(days(null, d.plusDays(5)), service.rangeOf(id))
        assertEquals(1, instancesOf(d).size)
        assertTrue(instancesOf(d.plusDays(10)).isEmpty())
    }

    @Test
    fun `the same days do nothing and a failed reading keeps the old days and events`() = runTest {
        val d = soon
        val body = ics(vevent("a@x", "Uno", d), vevent("b@x", "Due", d.plusDays(5)))
        val id = add(body, days(d.plusDays(5), null))
        val requests = server.requestCount
        assertEquals(RefreshResult.Unchanged, service.setRange(id, days(d.plusDays(5), null)))
        assertEquals(requests, server.requestCount)
        server.enqueue(MockResponse.Builder().code(500).body("no").build())
        assertEquals(RefreshResult.Failed(SubscriptionProblem.SERVER), service.setRange(id, SubscriptionRange()))
        assertEquals(days(d.plusDays(5), null), service.rangeOf(id))
        assertEquals(listOf("Due"), events(id).values.map { it.title })
        assertEquals(SubscriptionProblem.SERVER.name, db.calendarDao().get(id)!!.lastError)
    }

    @Test
    fun `days with the last before the first are refused`() = runTest {
        val d = soon
        serve(ics(vevent("a@x", "Uno", d)))
        val preview = (service.preview(address) as PreviewResult.Ok).preview
        assertEquals(AddResult.Problem(SubscriptionProblem.BAD_RANGE), service.add(preview, "", "#0B8043", days(d.plusDays(2), d)))
        assertTrue(db.calendarDao().getByKind(CalendarKind.SUBSCRIPTION).isEmpty())
        val id = add(ics(vevent("a@x", "Uno", d)), url = address + "?n=2")
        assertEquals(RefreshResult.Failed(SubscriptionProblem.BAD_RANGE), service.setRange(id, days(d.plusDays(2), d)))
    }

    // ---- U155: what a subscription is

    @Test
    fun `a subscription reads only, has no reminders and no outbox, and its address is stored encrypted`() = runTest {
        val id = add(ics(vevent("a@x", "Uno", extra = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nDESCRIPTION:x\r\nEND:VALARM\r\n")))
        val calendar = db.calendarDao().get(id)!!
        assertTrue(id.startsWith("sub:"))
        assertEquals(CalendarKind.SUBSCRIPTION, calendar.kind)
        assertEquals("reader", calendar.accessRole)
        assertFalse(calendar.canWrite)
        assertFalse(calendar.syncsWithGoogle)
        assertNull(calendar.accountEmail)
        assertTrue(calendar.defaultReminders.isEmpty())
        assertNull(calendar.refreshMinutes) // the waiting time is one setting for all, not a column
        assertNotEquals(address, calendar.sourceUrl)
        assertFalse(calendar.sourceUrl!!.contains("example.org"))
        val event = events(id).values.single()
        assertEquals(emptyList<Any>(), event.reminders)
        assertFalse(event.hasAlarm)
        assertTrue(event.attendees.isEmpty())
        assertTrue(db.outboxDao().getAll().isEmpty())
        assertEquals(1, instancesOf(soon).size)
    }

    @Test
    fun `the name given wins over the name of the file and the address can be shown masked or revealed`() = runTest {
        val id = add(ics(vevent("a@x", "Uno")), name = "  Lavoro  ")
        assertEquals("Lavoro", db.calendarDao().get(id)!!.name)
        assertEquals("https://localhost/…/basic.ics", service.maskedAddress(id)!!.replaceFirst(Regex(":\\d+"), ""))
        assertEquals(address, service.revealAddress(id))
    }

    @Test
    fun `the same address twice and more than ten subscriptions are refused`() = runTest {
        add(ics(vevent("a@x", "Uno")))
        serve(ics(vevent("a@x", "Uno")))
        val again = (service.preview(address) as PreviewResult.Ok).preview
        assertEquals(AddResult.Problem(SubscriptionProblem.ALREADY_SUBSCRIBED), service.add(again, "", "#000000"))
        repeat(SubscriptionService.MAX_SUBSCRIPTIONS - 1) { add(ics(vevent("a@x", "Uno")), url = address + "?n=$it") }
        serve(ics(vevent("a@x", "Uno")))
        val eleventh = (service.preview(address + "?n=99") as PreviewResult.Ok).preview
        assertEquals(AddResult.Problem(SubscriptionProblem.TOO_MANY_SUBSCRIPTIONS), service.add(eleventh, "", "#000000"))
        assertEquals(SubscriptionService.MAX_SUBSCRIPTIONS, db.calendarDao().getByKind(CalendarKind.SUBSCRIPTION).size)
    }

    // ---- U156: the feed of Google without a parent event

    @Test
    fun `109 occurrences with the same UID and no series are 109 events`() = runTest {
        val day = soon
        val occurrences = Array(109) { n ->
            val d = day.plusDays(n.toLong())
            "BEGIN:VEVENT\r\nUID:same@google.com\r\nDTSTAMP:20261021T000000Z\r\nDTSTART:${stamp(d, 8)}\r\nDTEND:${stamp(d, 9)}\r\n" +
                "RECURRENCE-ID:${stamp(d, 8)}\r\nSUMMARY:Busy\r\nEND:VEVENT\r\n"
        }
        val id = add(ics(*occurrences, name = "Calendario di lavoro"))
        assertEquals(109, events(id).size)
        assertTrue(events(id).values.all { it.title == "Busy" && it.seriesMasterId == null })
        assertEquals(1, instancesOf(day).size)
        assertEquals(1, instancesOf(day.plusDays(60)).size)
    }

    // ---- U128: replacing by difference

    @Test
    fun `a new copy adds what is new, replaces what changed and removes what is gone`() = runTest {
        val id = add(ics(vevent("a@x", "Uno"), vevent("b@x", "Due"), vevent("c@x", "Tre")))
        serve(ics(vevent("a@x", "Uno"), vevent("b@x", "Due, spostato", soon.plusDays(2)), vevent("d@x", "Quattro")))
        val result = service.refresh(id) as RefreshResult.Updated
        assertEquals(SubscriptionChange(added = 1, updated = 1, removed = 1), result.change)
        val now = events(id)
        assertEquals(setOf("$id|a@x", "$id|b@x", "$id|d@x"), now.keys)
        assertEquals("Due, spostato", now["$id|b@x"]!!.title)
        assertEquals(1, instancesOf(soon.plusDays(2)).size) // b moved there
        assertEquals(2, instancesOf(soon).size)              // a and d
        assertTrue(db.instanceDao().observeRange(soon.toEpochDay(), soon.toEpochDay() + 5).first().none { it.title == "Tre" })
    }

    @Test
    fun `what is equal is not written again`() = runTest {
        val id = add(ics(vevent("a@x", "Uno"), vevent("b@x", "Due")))
        serve(ics(vevent("a@x", "Uno"), vevent("b@x", "Due"), vevent("c@x", "Tre"))) // another hash, a and b equal
        val result = service.refresh(id) as RefreshResult.Updated
        assertEquals(SubscriptionChange(added = 1, updated = 0, removed = 0), result.change)
    }

    // ---- U129: the same content

    @Test
    fun `a copy with the same hash writes no event and no instance`() = runTest {
        val body = ics(vevent("a@x", "Uno"), vevent("b@x", "Due"))
        val id = add(body)
        val seen = Collections.synchronizedList(ArrayList<Set<String>>())
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer("events", "instances") {
            override fun onInvalidated(tables: Set<String>) { seen += tables }
        })
        serve(body)
        now += 3_600_000
        assertEquals(RefreshResult.Unchanged, service.refresh(id))
        Thread.sleep(400)
        assertTrue("events or instances were written: $seen", seen.isEmpty())
        assertEquals(now, db.calendarDao().get(id)!!.lastFetchedAt)
    }

    @Test
    fun `a 304 keeps everything and notes the time`() = runTest {
        serve(ics(vevent("a@x", "Uno")), etag = "\"v1\"")
        val id = (service.add((service.preview(address) as PreviewResult.Ok).preview, "", "#000000") as AddResult.Added).calendarId
        assertEquals("\"v1\"", db.calendarDao().get(id)!!.sourceEtag)
        now += 3_600_000
        server.enqueue(MockResponse.Builder().code(304).build())
        assertEquals(RefreshResult.Unchanged, service.refresh(id))
        server.takeRequest() // the first download
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
        assertEquals(now, db.calendarDao().get(id)!!.lastFetchedAt)
        assertEquals(1, events(id).size)
    }

    // ---- U130: an error in the middle

    @Test
    fun `an error in the middle of a replacement leaves the earlier state`() = runTest {
        val id = add(ics(vevent("a@x", "Uno"), vevent("b@x", "Due")))
        val before = events(id)
        // a row that cannot be saved (its calendar does not exist) after the removal of "a"
        val broken = EventEntity(id = "$id|z@x", calendarId = "sub:missing", title = "Z", startUtc = 1_800_000_000_000, endUtc = 1_800_003_600_000)
        val keep = before["$id|b@x"]!!
        try {
            service.apply(id, listOf(keep, broken))
            fail("the broken row should have failed")
        } catch (e: Exception) {
            // expected
        }
        assertEquals(before.keys, events(id).keys)
        assertEquals(before["$id|a@x"]!!.title, events(id)["$id|a@x"]!!.title)
        assertEquals(2, instancesOf(soon).size)
    }

    // ---- U157: failures keep the data

    @Test
    fun `a failed download keeps the events, notes the reason and the next good one clears it`() = runTest {
        val id = add(ics(vevent("a@x", "Uno")))
        listOf(404 to SubscriptionProblem.NOT_FOUND, 403 to SubscriptionProblem.FORBIDDEN, 500 to SubscriptionProblem.SERVER).forEach { (code, problem) ->
            server.enqueue(MockResponse.Builder().code(code).body("no").build())
            assertEquals(RefreshResult.Failed(problem), service.refresh(id))
            assertEquals(problem.name, db.calendarDao().get(id)!!.lastError)
            assertEquals(1, events(id).size)
        }
        serve(ics(vevent("a@x", "Uno"), vevent("b@x", "Due")))
        service.refresh(id)
        assertNull(db.calendarDao().get(id)!!.lastError)
        assertEquals(2, events(id).size)
    }

    @Test
    fun `a page that is not a calendar and a file that is too big are failures, not data`() = runTest {
        val id = add(ics(vevent("a@x", "Uno")))
        serve("<html>moved</html>")
        assertEquals(RefreshResult.Failed(SubscriptionProblem.NOT_CALENDAR), service.refresh(id))
        serve(ics(*Array(SubscriptionService.MAX_EVENTS + 1) { vevent("e$it@x", "E$it") }))
        assertEquals(RefreshResult.Failed(SubscriptionProblem.TOO_MANY_EVENTS), service.refresh(id))
        assertEquals(setOf("$id|a@x"), events(id).keys)
    }

    @Test
    fun `an address that cannot be decrypted is noted and the events stay`() = runTest {
        val id = add(ics(vevent("a@x", "Uno")))
        cipher.lost = true
        assertEquals(RefreshResult.Failed(SubscriptionProblem.ADDRESS_LOST), service.refresh(id))
        assertEquals(SubscriptionProblem.ADDRESS_LOST.name, db.calendarDao().get(id)!!.lastError)
        assertEquals(1, events(id).size)
        assertNull(service.revealAddress(id))
    }

    // ---- U158: when it is due

    @Test
    fun `only the subscriptions whose hour is up are downloaded`() = runTest {
        val first = add(ics(vevent("a@x", "Uno")), url = address + "?n=1")
        now += 30 * 60_000
        val second = add(ics(vevent("b@x", "Due")), url = address + "?n=2")
        now += 31 * 60_000 // 61 minutes since the first, 31 since the second
        serve(ics(vevent("a@x", "Uno"), vevent("a2@x", "Uno bis")))
        assertEquals(1, service.refreshDue())
        assertEquals(2, events(first).size)
        assertEquals(1, events(second).size)
        assertEquals(0, service.refreshDue())
    }

    @Test
    fun `the waiting time of the settings decides which subscriptions are due`() = runTest {
        prefs.setSubscriptionMinutes(15)
        val id = add(ics(vevent("a@x", "Uno")))
        now += 10 * 60_000
        assertEquals(0, service.refreshDue())
        now += 5 * 60_000 // fifteen minutes since the first download
        serve(ics(vevent("a@x", "Uno"), vevent("a2@x", "Uno bis")))
        assertEquals(1, service.refreshDue())
        assertEquals(2, events(id).size)
        now += 10 * 60_000
        assertEquals(0, service.refreshDue()) // the waiting time is counted from the last attempt
    }

    @Test
    fun `with the shortest waiting time a worker a minute early still counts as on time`() = runTest {
        prefs.setSubscriptionMinutes(15)
        add(ics(vevent("a@x", "Uno")))
        now += 13 * 60_000 // 13:00, more than the tolerance of 90 seconds early
        assertEquals(0, service.refreshDue())
        now += 60_000 + 15_000 // 14:15
        serve(ics(vevent("a@x", "Uno")))
        assertEquals(1, service.refreshDue())
    }

    @Test
    fun `a longer waiting time keeps a subscription out of the way`() = runTest {
        prefs.setSubscriptionMinutes(24 * 60)
        add(ics(vevent("a@x", "Uno")))
        now += 23 * 3_600_000L
        assertEquals(0, service.refreshDue())
        now += 61 * 60_000L
        serve(ics(vevent("a@x", "Uno")))
        assertEquals(1, service.refreshDue())
    }

    @Test
    fun `the waiting time cannot be set under 15 minutes or over a day`() = runTest {
        prefs.setSubscriptionMinutes(5)
        assertEquals(15, prefs.subscriptionMinutes.first())
        prefs.setSubscriptionMinutes(0)
        assertEquals(15, prefs.subscriptionMinutes.first())
        prefs.setSubscriptionMinutes(100_000)
        assertEquals(24 * 60, prefs.subscriptionMinutes.first())
        prefs.setSubscriptionMinutes(30)
        assertEquals(30, prefs.subscriptionMinutes.first())
    }

    @Test
    fun `refreshDue stops when the network is gone`() = runTest {
        add(ics(vevent("a@x", "Uno")), url = address + "?n=1")
        add(ics(vevent("b@x", "Due")), url = address + "?n=2")
        now += 2 * 3_600_000
        server.close() // nothing answers
        assertEquals(1, service.refreshDue())
    }

    // ---- U159: removing, hiding, renaming

    @Test
    fun `removing a subscription removes its events and instances, and only a subscription`() = runTest {
        val id = add(ics(vevent("a@x", "Uno")))
        db.calendarDao().upsert(db.calendarDao().get(id)!!.copy(id = "local:other", kind = CalendarKind.LOCAL, accessRole = "owner", sourceUrl = null))
        service.delete("local:other") // not a subscription: untouched
        assertNotNull(db.calendarDao().get("local:other"))
        service.delete(id)
        assertNull(db.calendarDao().get(id))
        assertTrue(events(id).isEmpty())
        assertTrue(instancesOf(soon).isEmpty())
    }

    @Test
    fun `a subscription is renamed and recoloured and can be hidden like the others`() = runTest {
        val id = add(ics(vevent("a@x", "Uno")))
        service.update(id, "  Ferie  ", "#D93025")
        assertEquals("Ferie", db.calendarDao().get(id)!!.name)
        assertEquals("#D93025", db.calendarDao().get(id)!!.color)
        db.calendarDao().setVisible(id, false)
        assertTrue(instancesOf(soon).isEmpty())
        db.calendarDao().setVisible(id, true)
        assertEquals(1, instancesOf(soon).size)
    }

    // ---- U160: other kinds of feed

    @Test
    fun `a feed in UTC and one with a series are read`() = runTest {
        val day = soon
        val series = "BEGIN:VEVENT\r\nUID:s@x\r\nDTSTAMP:20261021T000000Z\r\nDTSTART:${stamp(day, 7)}\r\nDTEND:${stamp(day, 8)}\r\nRRULE:FREQ=DAILY;COUNT=5\r\nSUMMARY:Serie\r\nEND:VEVENT\r\n"
        val id = add(ics(series))
        assertEquals(1, events(id).size)
        assertEquals(1, instancesOf(day).size)
        assertEquals(1, instancesOf(day.plusDays(4)).size)
        assertEquals(0, instancesOf(day.plusDays(5)).size)
        val zone = ZoneId.systemDefault()
        val first = instancesOf(day).single()
        assertEquals(java.time.ZonedDateTime.of(day, java.time.LocalTime.of(7, 0), java.time.ZoneOffset.UTC).toInstant(), java.time.Instant.ofEpochMilli(first.beginUtc))
        assertNotNull(zone)
    }

    // ---- attachments (issue #3, phase 3)

    @Test fun `X6 a feed with attachments is stored with them and a copy that did not change is not rewritten`() = runTest {
        val id = add(ics(vevent("a", "Uno", extra = "ATTACH;FMTTYPE=application/pdf;FILENAME=verbale.pdf:https://example.com/a.pdf\r\nATTACH:http://example.com/no.pdf\r\n")))
        val stored = events(id).values.single()
        assertEquals(listOf("verbale.pdf"), stored.attachments?.map { it.title })
        // the stored row has the search text of its attachments; the copy just read has not: they are the same event
        val change = service.apply(id, listOf(stored.copy(attachmentText = null)))
        assertEquals(0, change.updated)
        assertEquals(0, change.added)
        assertEquals(0, change.removed)
    }

    @Test fun `X7 a link added or removed in the feed is an update`() = runTest {
        val id = add(ics(vevent("a", "Uno", extra = "ATTACH:https://example.com/a.pdf\r\n")))
        val stored = events(id).values.single()
        val change = service.apply(id, listOf(stored.copy(attachments = listOf(EventAttachment("https://example.com/b.pdf", "b.pdf", "application/pdf")), attachmentText = null)))
        assertEquals(1, change.updated)
        assertEquals(listOf("b.pdf"), events(id).values.single().attachments?.map { it.title })
    }

}
