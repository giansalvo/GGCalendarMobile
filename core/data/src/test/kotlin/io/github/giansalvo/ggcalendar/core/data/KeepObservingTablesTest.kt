package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.database.AccountEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.OBSERVED_TABLES
import io.github.giansalvo.ggcalendar.core.database.OutboxEntity
import io.github.giansalvo.ggcalendar.core.database.keepObservingTables
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The database keeps Room's change tracking on for the tables the screens observe, even when no flow is being collected (U145, U146, U147).
 * Without that, a write made while no screen was observing was not announced to the one that opened afterwards (T144 and T145 of
 * the full run, 21/10/2026).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeepObservingTablesTest {
    private lateinit var db: GGCalendarDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** Waits until [seen] holds [table], at most a few seconds (Room tells its observers from a background thread). */
    private fun waitFor(seen: MutableSet<String>, table: String, latch: CountDownLatch) {
        latch.await(5, TimeUnit.SECONDS)
        assertTrue("no change of '$table' announced: $seen", table in seen)
    }

    @Test
    fun `every table of the screens is observed, with no flow being collected`() = runTest {
        val seen = Collections.synchronizedSet(HashSet<String>())
        var latch = CountDownLatch(1)
        db.keepObservingTables { tables -> seen += tables; latch.countDown() }

        db.accountDao().insert(AccountEntity("me@x.com"))
        waitFor(seen, "accounts", latch)

        latch = CountDownLatch(1)
        db.calendarDao().upsert(CalendarEntity("me@x.com|me@x.com", "me@x.com", "me@x.com", "Me", "#1A73E8", "owner"))
        waitFor(seen, "calendars", latch)

        latch = CountDownLatch(1)
        db.outboxDao().insert(OutboxEntity(eventId = "e1", calendarId = "me@x.com|me@x.com", operation = OutboxEntity.CREATE, payload = null))
        waitFor(seen, "outbox", latch)
    }

    @Test
    fun `the observed tables are the ones the flows of the daos read`() {
        // observeAll (accounts), observeAll (calendars), observe (events), observeCount (outbox), observeRange (instances and events),
        // observeUnread (sync_messages): a new flow on another table must be added to the list.
        assertEquals(setOf("accounts", "calendars", "events", "instances", "outbox", "sync_messages"), OBSERVED_TABLES.toSet())
    }

    @Test
    fun `a flow collected after a write made with nobody collecting sees the write`() = runTest {
        db.keepObservingTables()
        db.accountDao().insert(AccountEntity("one@x.com"))
        db.accountDao().insert(AccountEntity("two@x.com"))
        assertEquals(2, db.accountDao().observeAll().first().size)
        db.accountDao().insert(AccountEntity("three@x.com"))
        assertEquals(3, db.accountDao().observeAll().first().size)
    }
}
