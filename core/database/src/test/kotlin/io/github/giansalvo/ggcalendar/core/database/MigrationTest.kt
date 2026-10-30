package io.github.giansalvo.ggcalendar.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The update of a published app: the database of version 7 (the one of 1.0.5) becomes version 8 (the attachments of the events, 1.0.6)
 * without losing anything, and the Google calendars are read again once so that their events come back with the attachments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GGCalendarDatabase::class.java)

    private fun SupportSQLiteDatabase.event(id: String, calendar: String, title: String) = execSQL(
        """INSERT INTO events (id, calendar_id, remote_id, title, location, description, all_day, start_utc, end_utc, exdates, is_cancelled, availability,
           visibility, add_meet, send_invites, guests_can_modify, guests_can_invite, guests_can_see_list, attendees, has_alarm, event_type, updated_at)
           VALUES ('$id', '$calendar', ${if (calendar.startsWith("me")) "'g-$id'" else "NULL"}, '$title', '', 'una descrizione', 0, 1791806400000, 1791810000000, '[]', 0,
           'BUSY', 'DEFAULT', 0, 1, 0, 1, 1, '[]', 0, 'default', 5)""",
    )

    private fun version7(): SupportSQLiteDatabase {
        val db = helper.createDatabase(NAME, 7)
        db.execSQL("INSERT INTO accounts (email, sort_order, added_at) VALUES ('me@x.com', 0, 1)")
        db.execSQL(
            """INSERT INTO calendars (id, account_email, remote_id, name, color, access_role, is_primary, is_visible, default_reminders, sort_order, sync_token, kind)
               VALUES ('me@x.com|me@x.com', 'me@x.com', 'me@x.com', 'Me', '#1A73E8', 'owner', 1, 1, '[]', 0, 'token-google', 'google')""",
        )
        db.execSQL("INSERT INTO calendars (id, name, color, access_role, is_primary, is_visible, default_reminders, sort_order, sync_token, kind) VALUES ('local:1', 'Locale', '#000000', 'owner', 0, 1, '[]', 1, 'token-local', 'local')")
        db.execSQL("INSERT INTO calendars (id, name, color, access_role, is_primary, is_visible, default_reminders, sort_order, kind, source_url) VALUES ('sub:1', 'Abbonamento', '#111111', 'reader', 0, 1, '[]', 2, 'subscription', 'https://example.com/a.ics')")
        db.event("meG", "me@x.com|me@x.com", "Evento Google")
        db.event("locE", "local:1", "Evento locale")
        db.event("subE", "sub:1", "Evento abbonamento")
        db.execSQL("INSERT INTO outbox (event_id, calendar_id, operation, payload, send_updates, created_at, attempts) VALUES ('meG', 'me@x.com|me@x.com', 'update', '{}', 1, 1, 0)")
        return db
    }

    private fun migrate(): SupportSQLiteDatabase {
        version7().close()
        return helper.runMigrationsAndValidate(NAME, 8, true, GGCalendarDatabase.MIGRATION_7_8)
    }

    private fun SupportSQLiteDatabase.text(sql: String): String? = query(sql).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    @Test
    fun theSchemaOfVersion8IsWhatRoomExpects() {
        migrate().close() // runMigrationsAndValidate compares the result with schemas/8.json
    }

    @Test
    fun nothingIsLost() {
        val db = migrate()
        assertEquals("3", db.text("SELECT COUNT(*) FROM events"))
        assertEquals("Evento Google", db.text("SELECT title FROM events WHERE id = 'meG'"))
        assertEquals("una descrizione", db.text("SELECT description FROM events WHERE id = 'locE'"))
        assertEquals("g-meG", db.text("SELECT remote_id FROM events WHERE id = 'meG'"))
        assertEquals("3", db.text("SELECT COUNT(*) FROM calendars"))
        assertEquals("me@x.com", db.text("SELECT email FROM accounts"))
        assertEquals("1", db.text("SELECT COUNT(*) FROM outbox"))
        db.close()
    }

    @Test
    fun theEventsOfGoogleAreNotReadYet() {
        val db = migrate()
        assertNull(db.text("SELECT attachments FROM events WHERE id = 'meG'"))
        assertNull(db.text("SELECT attachment_text FROM events WHERE id = 'meG'"))
        db.close()
    }

    @Test
    fun theEventsOfLocalCalendarsAndSubscriptionsHaveAnEmptyList() {
        val db = migrate()
        assertEquals("[]", db.text("SELECT attachments FROM events WHERE id = 'locE'"))
        assertEquals("[]", db.text("SELECT attachments FROM events WHERE id = 'subE'"))
        db.close()
    }

    @Test
    fun theGoogleCalendarsAreReadAgainOnce() {
        val db = migrate()
        assertNull(db.text("SELECT sync_token FROM calendars WHERE id = 'me@x.com|me@x.com'"))
        db.close()
    }

    @Test
    fun theOtherCalendarsKeepTheirSyncToken() {
        val db = migrate()
        assertEquals("token-local", db.text("SELECT sync_token FROM calendars WHERE id = 'local:1'"))
        db.close()
    }

    @Test
    fun theMigratedDatabaseOpensWithRoomAndReadsTheEvents() {
        migrate().close()
        val room = androidx.room.Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, GGCalendarDatabase::class.java, NAME)
            .addMigrations(GGCalendarDatabase.MIGRATION_7_8).allowMainThreadQueries().build()
        val ids = kotlinx.coroutines.runBlocking { room.eventDao().getForCalendar("local:1") }
        assertEquals(1, ids.size)
        assertEquals(emptyList<io.github.giansalvo.ggcalendar.core.model.EventAttachment>(), ids[0].attachments)
        assertNull(kotlinx.coroutines.runBlocking { room.eventDao().get("meG") }!!.attachments)
        room.close()
    }

    // The full path: Room's driver wrapper wants the name it was configured with to be the one it is asked to open.
    private val NAME: String get() = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("migration-test").absolutePath
}
