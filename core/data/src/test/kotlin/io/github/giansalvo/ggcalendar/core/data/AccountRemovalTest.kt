package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.database.AccountEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.InstanceEntity
import io.github.giansalvo.ggcalendar.core.database.OutboxEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What taking an account away from the phone removes: the account, its calendars, events, instances and queue (not the others'). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountRemovalTest {
    private lateinit var db: GGCalendarDatabase

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        db.accountDao().insert(AccountEntity("a_b@x.com"))
        db.accountDao().insert(AccountEntity("axb@x.com"))
        db.calendarDao().upsert(CalendarEntity("a_b@x.com|p", "a_b@x.com", "p", "A", "#000", "owner"))
        db.calendarDao().upsert(CalendarEntity("axb@x.com|p", "axb@x.com", "p", "B", "#000", "owner"))
        db.eventDao().upsert(
            listOf(
                EventEntity(id = "e1", calendarId = "a_b@x.com|p", title = "One", startUtc = 1, endUtc = 2),
                EventEntity(id = "e2", calendarId = "axb@x.com|p", title = "Two", startUtc = 1, endUtc = 2),
            ),
        )
        db.instanceDao().insert(listOf(InstanceEntity("e1", 1, 2, 1, 2, false), InstanceEntity("e2", 1, 2, 1, 2, false)))
        db.outboxDao().insert(OutboxEntity(eventId = "e1", calendarId = "a_b@x.com|p", operation = OutboxEntity.UPDATE, payload = null))
        db.outboxDao().insert(OutboxEntity(eventId = "e2", calendarId = "axb@x.com|p", operation = OutboxEntity.UPDATE, payload = null))
    }

    @After
    fun tearDown() = db.close()

    // The same steps as CalendarRepository.removeAccount (the repository needs the whole sync engine to be built).
    private suspend fun remove(email: String) = db.withTransaction {
        db.outboxDao().discardForAccount(email.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "|")
        db.accountDao().delete(email)
    }

    @Test
    fun theAccountAndEverythingOfItGoes() = runTest {
        remove("a_b@x.com")

        assertEquals(listOf("axb@x.com"), db.accountDao().getAll().map { it.email })
        assertNull(db.calendarDao().get("a_b@x.com|p"))
        assertNull(db.eventDao().get("e1"))
        assertEquals(listOf("e2"), db.outboxDao().getAll().map { it.eventId })
    }

    @Test
    fun anUnderscoreInTheAddressDoesNotTakeAnotherAccountsQueue() = runTest {
        // "a_b@x.com" as a LIKE pattern would also match "axb@x.com|...": the escaped prefix must not
        remove("a_b@x.com")

        assertNotNull(db.eventDao().get("e2"))
        assertEquals(1, db.outboxDao().getAll().size)
    }
}
