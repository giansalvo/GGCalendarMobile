package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import io.github.giansalvo.ggcalendar.core.auth.GoogleAuthorizer
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarKind
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** U190-U207 and U214: reading a file or an address once and writing it into a local calendar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportServiceTest {
    private val server = MockWebServer()
    private lateinit var context: Context
    private lateinit var db: GGCalendarDatabase
    private lateinit var service: ImportService
    private lateinit var locals: LocalCalendars
    private lateinit var prefs: AppPreferences
    private var remindersChanged = 0
    private lateinit var home: String

    @Before
    fun setUp() {
        val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
        server.start()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
        context = ApplicationProvider.getApplicationContext()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        val api = GoogleCalendarApi(client, server.url("/calendar/v3/"))
        val engine = SyncEngine(db, api, GoogleAuthorizer(context), AppPreferences(context), OutboxPusher(context, db, api))
        prefs = AppPreferences(context)
        runBlocking { prefs.setDefaultReminderMinutes(30) } // the store outlives a test
        remindersChanged = 0
        val hooks = ReminderHooks(setOf(ReminderHook { remindersChanged++ }))
        locals = LocalCalendars(db, hooks)
        service = ImportService(context, db, IcsFetcher(client), engine, hooks, prefs, locals)
        home = runBlocking { locals.create("Casa", "#33B679") }
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

    private fun alarm(minutes: Int) = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:x\r\nTRIGGER:-PT${minutes}M\r\nEND:VALARM\r\n"

    private fun ics(vararg events: String, name: String? = "Calendario del file", method: String? = null) = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n")
        method?.let { append("METHOD:$it\r\n") }
        name?.let { append("X-WR-CALNAME:$it\r\n") }
        events.forEach(::append)
        append("END:VCALENDAR\r\n")
    }

    private fun source(text: String) = ImportSource("file.ics", text)

    private suspend fun run(text: String, target: ImportTarget = ImportTarget.Existing(home), options: ImportOptions = ImportOptions()): ImportOutcome.Done =
        service.import(source(text), target, options) as ImportOutcome.Done

    private suspend fun rows(calendarId: String = home) = db.eventDao().getForCalendar(calendarId)

    private fun exceptionOf(uid: String, day: LocalDate) =
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20261021T000000Z\r\nRECURRENCE-ID:${stamp(day, 9)}\r\nDTSTART:${stamp(day, 15)}\r\nDTEND:${stamp(day, 16)}\r\nSUMMARY:Spostata\r\nEND:VEVENT\r\n"

    // ---- the summary and the reading (U190, U191, U206)

    @Test fun `U190 the summary counts events, series, days, what is ignored and the method`() = runTest {
        val text = ics(
            vevent("a", "Uno"), vevent("b", "Due", soon.plusDays(2), extra = "RRULE:FREQ=WEEKLY;COUNT=3\r\n"),
            "BEGIN:VTODO\r\nUID:t\r\nSUMMARY:x\r\nEND:VTODO\r\n", method = "PUBLISH",
        )
        val s = (service.summarize(source(text)) as SummaryOutcome.Ok).summary
        assertEquals(2, s.eventCount)
        assertEquals(1, s.seriesCount)
        assertEquals(soon, s.firstDay)
        assertEquals("Calendario del file", s.calendarName)
        assertEquals("PUBLISH", s.method)
        assertEquals(1, s.ignored["VTODO"])
        assertFalse(s.fileHasAlarms)
    }

    @Test fun `U190 a file that is not a calendar or has no events says so`() = runTest {
        assertEquals(SummaryOutcome.Problem(ImportProblem.NOT_CALENDAR), service.summarize(source("<html><body>no</body></html>")))
        assertEquals(SummaryOutcome.Problem(ImportProblem.NO_EVENTS), service.summarize(source(ics())))
    }

    @Test fun `U191 an address is read once with https, http is refused and nothing of it is kept`() = runTest {
        server.enqueue(MockResponse.Builder().body(ics(vevent("a", "Uno"))).build())
        val address = server.url("/private-0123456789abcdef0123456789abcdef/basic.ics").toString()
        val read = service.readAddress(address) as ReadOutcome.Ok
        assertTrue(read.source.toString().startsWith("ImportSource(chars="))
        assertEquals(ReadOutcome.Problem(ImportProblem.BAD_ADDRESS), service.readAddress(address.replace("https://", "http://")))
        // no calendar of the address, no subscription, no event
        assertEquals(listOf(home), db.calendarDao().getByKind(CalendarKind.LOCAL).map { it.id })
        assertTrue(db.calendarDao().getByKind(CalendarKind.SUBSCRIPTION).isEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test fun `U191 a file is read from a Uri, an empty one is an error`() = runTest {
        val f = File.createTempFile("import", ".ics").apply {
            writeText(ics(vevent("a", "Uno")))
            deleteOnExit()
        }
        val ok = service.readFile(Uri.fromFile(f)) as ReadOutcome.Ok
        assertEquals(1, (service.summarize(ok.source) as SummaryOutcome.Ok).summary.eventCount)
        val empty = File.createTempFile("import", ".ics").apply { deleteOnExit() }
        assertEquals(ReadOutcome.Problem(ImportProblem.EMPTY), service.readFile(Uri.fromFile(empty)))
    }

    @Test fun `U206 a file makes no request to any server`() = runTest {
        run(ics(vevent("a", "Uno", extra = "URL:https://localhost:${server.port}/x\r\nDESCRIPTION:https://localhost:${server.port}/y\r\n")))
        assertEquals(0, server.requestCount)
    }

    // ---- the destination and what is written (U192, U193, U204, U205)

    @Test fun `U192 only a local calendar is a destination`() = runTest {
        db.accountDao().insert(io.github.giansalvo.ggcalendar.core.database.AccountEntity("me@x.com"))
        db.calendarDao().upsert(CalendarEntity("me@x.com|primary", "me@x.com", "primary", "Me", "#1A73E8", "owner"))
        val outcome = service.import(source(ics(vevent("a", "Uno"))), ImportTarget.Existing("me@x.com|primary"), ImportOptions())
        assertEquals(ImportOutcome.Failed(ImportProblem.NOT_A_LOCAL_CALENDAR), outcome)
        assertEquals(ImportOutcome.Failed(ImportProblem.NOT_A_LOCAL_CALENDAR), service.import(source(ics(vevent("a", "Uno"))), ImportTarget.Existing("nowhere"), ImportOptions()))
        assertTrue(db.eventDao().getForCalendar("me@x.com|primary").isEmpty())
    }

    @Test fun `U193 events, series and exceptions are written as events of the user with no outbox`() = runTest {
        val series = vevent("s", "Serie", extra = "RRULE:FREQ=DAILY;COUNT=4\r\n")
        val done = run(ics(vevent("a", "Uno"), series, exceptionOf("s", soon.plusDays(1))))
        assertEquals(2, done.imported)
        val all = rows()
        assertEquals(3, all.size)
        assertTrue(all.all { it.calendarId == home && it.remoteId == null && !it.sendInvites })
        assertEquals(1, all.count { it.seriesMasterId != null })
        assertTrue(db.outboxDao().pendingEventIds().isEmpty())
        val c = db.calendarDao().get(home)!!
        assertTrue(c.canWrite && !c.syncsWithGoogle)
    }

    @Test fun `a series as the app exports it keeps its excluded date and its moved occurrence`() = runTest {
        // The export writes UTC with a TZID: reading it back must show the same occurrences (3 of the series and the moved one).
        fun local(day: LocalDate, hour: Int) = "%04d%02d%02dT%02d0000".format(day.year, day.monthValue, day.dayOfMonth, hour)
        val master = "BEGIN:VEVENT\r\nUID:s\r\nDTSTART;TZID=UTC:${local(soon, 9)}\r\nDTEND;TZID=UTC:${local(soon, 10)}\r\nSUMMARY:Serie\r\n" +
            "RRULE:FREQ=DAILY;COUNT=5\r\nEXDATE;TZID=UTC:${local(soon.plusDays(2), 9)}\r\nEND:VEVENT\r\n"
        run(ics(master, exceptionOf("s", soon.plusDays(1))))
        val shown = db.instanceDao().observeRange(soon.toEpochDay() - 1, soon.toEpochDay() + 10).first()
        assertEquals(listOf("Serie", "Spostata", "Serie", "Serie"), shown.map { it.title })
    }

    @Test fun `U204 guests and organizer never become guests`() = runTest {
        run(ics(vevent("a", "Uno", extra = "ORGANIZER;CN=Ada:mailto:ada@x.com\r\nATTENDEE;CN=Bob:mailto:bob@x.com\r\n")))
        val e = rows().single()
        assertTrue(e.attendees.isEmpty())
        assertNull(e.organizerEmail)
        assertTrue(db.outboxDao().pendingEventIds().isEmpty())
    }

    @Test fun `U205 imported, updated and skipped add up to what the file holds`() = runTest {
        val text = ics(vevent("a", "Uno"), vevent("b", "Due"), vevent("c", "Tre"))
        run(ics(vevent("a", "Uno"), vevent("b", "Due")))
        val skipped = run(text)
        assertEquals(1, skipped.imported)
        assertEquals(2, skipped.skipped)
        assertEquals(0, skipped.updated)
        val updated = run(text, options = ImportOptions(duplicates = DuplicateAction.UPDATE))
        assertEquals(0, updated.imported)
        assertEquals(3, updated.updated)
    }

    // ---- duplicates (U194-U197, U214)

    @Test fun `U194 skip leaves what is there`() = runTest {
        run(ics(vevent("a", "Originale")))
        val done = run(ics(vevent("a", "Cambiato")))
        assertEquals(1, done.skipped)
        assertEquals("Originale", rows().single().title)
        assertEquals(1, service.plan(source(ics(vevent("a", "x"))), home, SubscriptionRange()).duplicates)
    }

    @Test fun `U195 update replaces the content under the same id`() = runTest {
        run(ics(vevent("s", "Serie", extra = "RRULE:FREQ=DAILY;COUNT=3\r\n")))
        val before = rows().single().id
        run(ics(vevent("s", "Serie nuova", extra = "RRULE:FREQ=DAILY;COUNT=5\r\n")), options = ImportOptions(duplicates = DuplicateAction.UPDATE))
        val e = rows().single()
        assertEquals(before, e.id)
        assertEquals("Serie nuova", e.title)
        assertTrue(e.rrule!!.contains("COUNT=5"))
    }

    @Test fun `U196 as new gives new ids and keeps a series with its exception together`() = runTest {
        val text = ics(vevent("s", "Serie", extra = "RRULE:FREQ=DAILY;COUNT=4\r\n"), exceptionOf("s", soon.plusDays(1)))
        run(text)
        run(text, options = ImportOptions(duplicates = DuplicateAction.AS_NEW))
        val all = rows()
        assertEquals(4, all.size)
        assertEquals(4, all.map { it.id }.toSet().size)
        val masters = all.filter { it.seriesMasterId == null }
        assertEquals(2, masters.size)
        val exceptions = all.filter { it.seriesMasterId != null }
        assertEquals(2, exceptions.map { it.seriesMasterId }.toSet().size)
        assertTrue(exceptions.all { e -> masters.any { it.id == e.seriesMasterId } })
    }

    @Test fun `U197 duplicates are looked for only in the calendar chosen`() = runTest {
        run(ics(vevent("a", "Uno")))
        val other = locals.create("Altro", "#33B679")
        assertEquals(0, service.plan(source(ics(vevent("a", "Uno"))), other, SubscriptionRange()).duplicates)
        run(ics(vevent("a", "Uno")), ImportTarget.Existing(other))
        assertEquals(1, rows(other).size)
        assertEquals(1, rows(home).size)
    }

    @Test fun `U214 update leaves the reminders of the events that are there`() = runTest {
        run(ics(vevent("a", "Uno", extra = alarm(15))), options = ImportOptions(reminders = ReminderChoice.FILE))
        val mine = rows().single().reminders
        assertEquals(listOf(Reminder(ReminderMethod.POPUP, 15)), mine)
        run(ics(vevent("a", "Uno cambiato", extra = alarm(5)), vevent("b", "Nuovo")), options = ImportOptions(duplicates = DuplicateAction.UPDATE, reminders = ReminderChoice.NONE))
        val a = rows().first { it.iCalUid == "a" }
        val b = rows().first { it.iCalUid == "b" }
        assertEquals("Uno cambiato", a.title)
        assertEquals(mine, a.reminders)
        assertEquals(emptyList<Reminder>(), b.reminders)
    }

    // ---- the days (U198)

    @Test fun `U198 the days keep events and whole series with a day inside`() = runTest {
        val inside = soon
        val text = ics(
            vevent("early", "Prima", inside.minusDays(20)), vevent("in", "Dentro", inside), vevent("late", "Dopo", inside.plusDays(20)),
            vevent("series", "Serie", inside.minusDays(10), extra = "RRULE:FREQ=DAILY;COUNT=30\r\n"),
        )
        val range = SubscriptionRange(from = inside, to = inside.plusDays(1))
        assertEquals(2, service.plan(source(text), home, range).events) // the event inside and the series that goes through the days
        run(text, options = ImportOptions(range = range))
        assertEquals(setOf("Dentro", "Serie"), rows().map { it.title }.toSet())
        assertTrue(rows().first { it.title == "Serie" }.rrule!!.contains("COUNT=30")) // the series is whole
    }

    // ---- reminders (U199, U208, U209)

    @Test fun `U199 the reminders of the file, each event keeps its own and those without have none`() = runTest {
        val text = ics(vevent("a", "Con", extra = alarm(15)), vevent("b", "Senza"))
        run(text, options = ImportOptions(reminders = ReminderChoice.FILE))
        assertEquals(listOf(Reminder(ReminderMethod.POPUP, 15)), rows().first { it.iCalUid == "a" }.reminders)
        assertEquals(emptyList<Reminder>(), rows().first { it.iCalUid == "b" }.reminders)
        val s = (service.summarize(source(text)) as SummaryOutcome.Ok).summary
        assertTrue(s.fileHasAlarms)
        assertEquals(1, s.eventsWithAlarms)
    }

    @Test fun `U208 the default of the app goes to every event, with or without alarms in the file`() = runTest {
        run(ics(vevent("a", "Con", extra = alarm(15)), vevent("b", "Senza")), options = ImportOptions(reminders = ReminderChoice.APP_DEFAULT))
        rows().forEach { assertEquals(listOf(Reminder(ReminderMethod.POPUP, 30)), it.reminders) }
        prefs.setDefaultReminderMinutes(AppPreferences.NO_REMINDER)
        run(ics(vevent("c", "Terzo")), options = ImportOptions(reminders = ReminderChoice.APP_DEFAULT))
        assertEquals(emptyList<Reminder>(), rows().first { it.iCalUid == "c" }.reminders)
        prefs.setDefaultReminderMinutes(30)
    }

    @Test fun `U209 none is an empty list of its own, not the defaults of the calendar`() = runTest {
        run(ics(vevent("a", "Uno", extra = alarm(15))), options = ImportOptions(reminders = ReminderChoice.NONE))
        val r = rows().single().reminders
        assertNotNull(r)
        assertTrue(r!!.isEmpty())
        // the calendar has its own default, and the event does not use it
        assertEquals(1, db.calendarDao().get(home)!!.defaultReminders.size)
    }

    // ---- a new calendar (U200), one transaction (U201), the warning (U202), method (U203), the alarms once (U207)

    @Test fun `U200 a new calendar takes the name of the file and is made only by the import`() = runTest {
        val before = db.calendarDao().getByKind(CalendarKind.LOCAL).size
        val done = run(ics(vevent("a", "Uno"), name = "Corso di prova"), ImportTarget.NewCalendar("Corso di prova", "#8E24AA"))
        assertEquals(before + 1, db.calendarDao().getByKind(CalendarKind.LOCAL).size)
        val c = db.calendarDao().get(done.calendarId)!!
        assertEquals("Corso di prova", c.name)
        assertEquals(CalendarKind.LOCAL, c.kind)
        assertEquals(1, rows(done.calendarId).size)
        // the new calendar keeps the default reminder of this moment
        assertEquals(listOf(Reminder(ReminderMethod.POPUP, 30)), c.defaultReminders)
    }

    @Test fun `U201 cancelled or unreadable, nothing is written and no calendar is made`() = runTest {
        val before = db.calendarDao().getByKind(CalendarKind.LOCAL).size
        val cancelled = service.import(source(ics(vevent("a", "Uno"))), ImportTarget.NewCalendar("Nuovo", "#8E24AA"), ImportOptions(), isCancelled = { true })
        assertEquals(ImportOutcome.Cancelled, cancelled)
        val bad = service.import(source("not a calendar"), ImportTarget.NewCalendar("Nuovo", "#8E24AA"), ImportOptions())
        assertTrue(bad is ImportOutcome.Failed)
        assertEquals(before, db.calendarDao().getByKind(CalendarKind.LOCAL).size)
        assertTrue(rows().isEmpty())
        assertEquals(0, remindersChanged)
    }

    @Test fun `U202 over twenty thousand events after the days, the plan asks for a confirmation`() = runTest {
        val many = buildString {
            append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n")
            for (i in 0..ImportService.WARN_EVENTS) {
                val day = soon.plusDays((i % 300).toLong())
                append("BEGIN:VEVENT\r\nUID:u$i\r\nDTSTART:${stamp(day, 9)}\r\nDTEND:${stamp(day, 10)}\r\nSUMMARY:E$i\r\nEND:VEVENT\r\n")
            }
            append("END:VCALENDAR\r\n")
        }
        val all = service.plan(source(many), home, SubscriptionRange())
        assertEquals(ImportService.WARN_EVENTS + 1, all.events)
        assertTrue(all.needsConfirmation)
        // days that bring it under the limit take the warning away; it never blocks the import
        val few = service.plan(source(many), home, SubscriptionRange(from = soon, to = soon.plusDays(20)))
        assertTrue(few.events < ImportService.WARN_EVENTS)
        assertFalse(few.needsConfirmation)
    }

    @Test fun `U203 request is imported, cancel is counted and ignored`() = runTest {
        run(ics(vevent("a", "Invito"), method = "REQUEST"))
        assertEquals(1, rows().size)
        val cancel = ics(vevent("b", "Annullata", extra = "STATUS:CANCELLED\r\n"), method = "CANCEL")
        val outcome = service.summarize(source(cancel))
        assertTrue(outcome is SummaryOutcome.Problem || (outcome as SummaryOutcome.Ok).summary.cancelledEvents >= 1)
        val before = rows().size
        service.import(source(cancel), ImportTarget.Existing(home), ImportOptions())
        assertEquals(before, rows().size)
    }

    @Test fun `U207 the alarms are planned again once for the whole import`() = runTest {
        run(ics(vevent("a", "Uno"), vevent("b", "Due"), vevent("c", "Tre")))
        assertEquals(1, remindersChanged)
    }
}
