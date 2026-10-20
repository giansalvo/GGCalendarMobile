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
import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import mockwebserver3.MockWebServer
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

/** Local calendars: they are made, changed and deleted on the phone, and their events never reach the outbox (U132, U133, U134, U135). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalCalendarsTest {
    private lateinit var context: Context
    private lateinit var db: GGCalendarDatabase
    private lateinit var locals: LocalCalendars
    private lateinit var writer: EventWriter
    private val server = MockWebServer()
    private val google = "me@x.com|me@x.com"

    @Before
    fun setUp() = runTest {
        server.start()
        context = ApplicationProvider.getApplicationContext()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        val hooks = ReminderHooks(emptySet())
        val api = GoogleCalendarApi(OkHttpClient(), server.url("/calendar/v3/"))
        val prefs = AppPreferences(context)
        val engine = SyncEngine(db, api, GoogleAuthorizer(context), prefs, OutboxPusher(context, db, api))
        writer = EventWriter(db, engine, SyncScheduler(context), hooks)
        locals = LocalCalendars(db, hooks)
        db.accountDao().insert(AccountEntity("me@x.com"))
        db.calendarDao().upsert(CalendarEntity(google, "me@x.com", "me@x.com", "Me", "#1A73E8", "owner"))
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun event(id: String, calendarId: String, title: String = "Event", extra: (EventEntity) -> EventEntity = { it }) =
        extra(EventEntity(id = id, calendarId = calendarId, title = title, startUtc = 1_800_000_000_000, endUtc = 1_800_003_600_000))

    @Test
    fun aLocalCalendarHasNoAccountAndDefaultReminders() = runTest {
        val id = locals.create("  Casa  ", "#33B679")
        val c = db.calendarDao().get(id)!!
        assertTrue(id.startsWith("local:"))
        assertEquals("Casa", c.name)
        assertEquals(CalendarKind.LOCAL, c.kind)
        assertNull(c.accountEmail)
        assertNull(c.remoteId)
        assertTrue(c.canWrite)
        assertTrue(c.isVisible)
        assertFalse(c.syncsWithGoogle)
        assertEquals(listOf(Reminder(ReminderMethod.POPUP, 10)), c.defaultReminders)
    }

    @Test
    fun theNewCalendarsKeepTheirOrder() = runTest {
        val a = locals.create("A", "#000000")
        val b = locals.create("B", "#000000")
        assertTrue(db.calendarDao().get(a)!!.sortOrder < db.calendarDao().get(b)!!.sortOrder)
    }

    // U132
    @Test
    fun anEventOfALocalCalendarNeverGoesToTheOutbox() = runTest {
        val id = locals.create("Casa", "#33B679")
        writer.save(event("e1", id, "Cena") { it.copy(attendees = listOf(Attendee("a@b.it")), addMeet = true, sendInvites = true) })
        val saved = db.eventDao().get("e1")!!
        assertEquals("Cena", saved.title)
        assertTrue(saved.attendees.isEmpty())
        assertFalse(saved.addMeet)
        assertTrue(db.outboxDao().getAll().isEmpty())
        // Editing and deleting stay local too.
        writer.save(saved.copy(title = "Cena 2"))
        assertEquals("Cena 2", db.eventDao().get("e1")!!.title)
        writer.delete("e1")
        assertNull(db.eventDao().get("e1"))
        assertTrue(db.outboxDao().getAll().isEmpty())
    }

    @Test
    fun aGoogleEventStillGoesToTheOutbox() = runTest {
        writer.save(event("g1", google))
        assertEquals(listOf(OutboxEntity.CREATE), db.outboxDao().getAll().map { it.operation })
    }

    @Test
    fun deletingAnOccurrenceOfALocalSeriesQueuesNothing() = runTest {
        val id = locals.create("Casa", "#33B679")
        writer.save(event("s", id) { it.copy(rrule = "FREQ=DAILY;COUNT=5") })
        writer.save(event("s|x", id) { it.copy(seriesMasterId = "s", originalStartUtc = 1_800_086_400_000) })
        writer.delete("s|x")
        assertTrue(db.eventDao().get("s|x")!!.isCancelled)
        assertTrue(db.outboxDao().getAll().isEmpty())
    }

    @Test
    fun movingBetweenLocalAndGoogleCalendars() = runTest {
        val id = locals.create("Casa", "#33B679")
        writer.save(event("e1", id))
        writer.move("e1", google)
        assertEquals(1, db.outboxDao().getAll().size)
        assertEquals(google, db.eventDao().getForCalendar(google).single().calendarId)
        val moved = db.eventDao().getForCalendar(google).single()
        writer.move(moved.id, id)
        assertEquals(id, db.eventDao().getForCalendar(id).single().calendarId)
        assertTrue(db.eventDao().getForCalendar(google).isEmpty())
    }

    @Test
    fun renamingAndRecoloring() = runTest {
        val id = locals.create("Casa", "#33B679")
        locals.update(id, " Lavoro ", "#D50000")
        val c = db.calendarDao().get(id)!!
        assertEquals("Lavoro", c.name)
        assertEquals("#D50000", c.color)
        // A Google calendar cannot be changed from here.
        locals.update(google, "Altro", "#000000")
        assertEquals("Me", db.calendarDao().get(google)!!.name)
    }

    @Test
    fun deletingTakesTheEventsAndSnoozesAndLeavesTheRest() = runTest {
        val id = locals.create("Casa", "#33B679")
        writer.save(event("e1", id))
        writer.save(event("g1", google))
        db.reminderDao().insertSnoozed(ReminderSnoozedEntity(fireAt = 5, eventId = "e1", beginUtc = 1_800_000_000_000, method = "POPUP", minutes = 10))
        locals.delete(id)
        assertNull(db.calendarDao().get(id))
        assertNull(db.eventDao().get("e1"))
        assertNotNull(db.eventDao().get("g1"))
        assertTrue(db.reminderDao().snoozed().isEmpty())
        // A Google calendar cannot be deleted from here.
        locals.delete(google)
        assertNotNull(db.calendarDao().get(google))
    }

    // T140 at the database level
    @Test
    fun removingTheGoogleAccountLeavesTheLocalCalendars() = runTest {
        val id = locals.create("Casa", "#33B679")
        writer.save(event("e1", id))
        db.accountDao().delete("me@x.com")
        assertNull(db.calendarDao().get(google))
        assertNotNull(db.calendarDao().get(id))
        assertNotNull(db.eventDao().get("e1"))
    }

    // U133
    @Test
    fun hidingALocalCalendarClearsItsSnoozes() = runTest {
        val id = locals.create("Casa", "#33B679")
        writer.save(event("e1", id))
        db.reminderDao().insertSnoozed(ReminderSnoozedEntity(fireAt = 5, eventId = "e1", beginUtc = 1_800_000_000_000, method = "POPUP", minutes = 10))
        CalendarVisibility(db).set(id, false)
        assertFalse(db.calendarDao().get(id)!!.isVisible)
        assertTrue(db.reminderDao().snoozed().isEmpty())
    }

    // U134
    @Test
    fun oldDatabasesAreDroppedAndTheCallbackIsTold() {
        val file = context.getDatabasePath("old.db")
        file.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use {
            it.execSQL("CREATE TABLE accounts (email TEXT PRIMARY KEY NOT NULL)")
            it.execSQL("INSERT INTO accounts VALUES ('old@x.com')")
            it.version = 3
        }
        var told = false
        val reopened = GGCalendarDatabase.configure(Room.databaseBuilder(context, GGCalendarDatabase::class.java, "old.db").allowMainThreadQueries()) { told = true }.build()
        try {
            kotlinx.coroutines.runBlocking { assertTrue(reopened.accountDao().getAll().isEmpty()) }
            assertTrue(told)
            assertEquals(GGCalendarDatabase.OLDEST_KEPT_VERSION, reopened.openHelper.readableDatabase.version)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun aCleanDatabaseIsMadeWithoutTheCallback() {
        var told = false
        val fresh = GGCalendarDatabase.configure(Room.databaseBuilder(context, GGCalendarDatabase::class.java, "fresh.db").allowMainThreadQueries()) { told = true }.build()
        try {
            kotlinx.coroutines.runBlocking { assertTrue(fresh.accountDao().getAll().isEmpty()) }
            assertFalse(told)
        } finally {
            fresh.close()
        }
    }

    // U135
    @Test
    fun theDataKeysAreForgottenAndTheSettingsKept() = runTest {
        val prefs = AppPreferences(context)
        prefs.setInstanceWindow(InstanceWindow(java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31), "Europe/Rome", java.time.LocalDate.of(2026, 10, 20)))
        prefs.setReminderLastCheck(123)
        prefs.setDayStartHour(9)
        prefs.resetDataKeys()
        assertNull(prefs.instanceWindow())
        assertNull(prefs.reminderLastCheck())
        assertEquals(9, prefs.view.first().dayStartHour)
    }

    @Test
    fun theResetFlagIsSetAndCleared() {
        assertFalse(SchemaReset.pending(context))
        SchemaReset.mark(context)
        assertTrue(SchemaReset.pending(context))
        SchemaReset.clear(context)
        assertFalse(SchemaReset.pending(context))
    }
}
