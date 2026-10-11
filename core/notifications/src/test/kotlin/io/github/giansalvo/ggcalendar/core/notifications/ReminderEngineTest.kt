package io.github.giansalvo.ggcalendar.core.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.data.DueReminder
import io.github.giansalvo.ggcalendar.core.database.AccountEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.InstanceEntity
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Missed reminders, snoozes and the scheduling of the next ones, on an in-memory database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderEngineTest {
    private val minute = 60_000L
    private val calendarId = "me@x.com|me@x.com"
    private lateinit var context: Context
    private lateinit var db: GGCalendarDatabase
    private lateinit var engine: ReminderEngine
    private lateinit var scheduler: ReminderScheduler
    private lateinit var manager: NotificationManager
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        val notifier = ReminderNotifier(context).also { it.createChannels() }
        scheduler = ReminderScheduler(context, db, notifier)
        prefs = AppPreferences(context)
        engine = ReminderEngine(context, db, prefs, scheduler, notifier)
        // The first catch-up of a fresh install looks back from now: simulate a phone that was off for a while.
        prefs.setReminderLastCheck(System.currentTimeMillis() - 2 * 60 * minute)
        manager = context.getSystemService(NotificationManager::class.java)
        db.accountDao().insert(AccountEntity("me@x.com"))
        // A hidden calendar still rings: hiding only cleans the views.
        db.calendarDao().upsert(CalendarEntity(calendarId, "me@x.com", "me@x.com", "Me", "#1A73E8", "owner", isVisible = false))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addEvent(id: String, begin: Long, end: Long, vararg reminders: Reminder) {
        db.eventDao().upsert(
            listOf(EventEntity(id = id, calendarId = calendarId, title = "Event $id", startUtc = begin, endUtc = end, reminders = reminders.toList())),
        )
        db.instanceDao().insert(listOf(InstanceEntity(id, begin, end, 0, 1, allDay = false)))
    }

    private fun shown() = shadowOf(manager).allNotifications

    @Test
    fun aMissedPopupIsShownOnceAndRemembered() = runTest {
        val now = System.currentTimeMillis()
        // Started 5 minutes ago with a 30-minute reminder that fired 35 minutes ago; the event goes on for an hour.
        addEvent("a", now - 5 * minute, now + 55 * minute, Reminder(ReminderMethod.POPUP, 30))
        engine.catchUp()
        assertEquals(1, shown().size)
        engine.catchUp()
        assertEquals("a reminder already shown is not shown again", 1, shown().size)
    }

    @Test
    fun remindersOfEndedEventsAreNotCaughtUp() = runTest {
        val now = System.currentTimeMillis()
        addEvent("old", now - 3 * 60 * minute, now - 2 * 60 * minute, Reminder(ReminderMethod.POPUP, 10))
        engine.catchUp()
        assertTrue(shown().isEmpty())
    }

    @Test
    fun snoozedReminderComesBackAndOnlyOnce() = runTest {
        val now = System.currentTimeMillis()
        addEvent("s", now + 60 * minute, now + 120 * minute, Reminder(ReminderMethod.POPUP, 10))
        val alert = ReminderAlert(
            key = DueReminder.keyOf("s", now + 60 * minute, Reminder(ReminderMethod.POPUP, 10)),
            eventId = "s", beginUtc = now + 60 * minute, endUtc = now + 120 * minute, allDay = false,
            title = "Event s", location = "", description = "", meetLink = null, method = ReminderMethod.POPUP, minutes = 10,
        )
        engine.snooze(alert, 5)
        assertEquals(1, db.reminderDao().snoozed().size)
        // Time passes: the snooze is due, the next catch-up shows it and forgets it.
        val snoozed = db.reminderDao().snoozed().single()
        db.reminderDao().deleteSnoozed(snoozed.id)
        db.reminderDao().insertSnoozed(snoozed.copy(id = 0, fireAt = now - minute))
        engine.catchUp()
        assertEquals(1, shown().size)
        assertTrue(db.reminderDao().snoozed().isEmpty())
    }

    @Test
    fun activeAlarmsListsOnlyAlarmsThatHaveNotRungYetSoonestFirst() = runTest {
        val now = System.currentTimeMillis()
        addEvent("late", now + 300 * minute, now + 360 * minute, Reminder(ReminderMethod.ALARM, 30), Reminder(ReminderMethod.POPUP, 5))
        addEvent("soon", now + 100 * minute, now + 160 * minute, Reminder(ReminderMethod.ALARM, 10))
        addEvent("past", now - 100 * minute, now + 60 * minute, Reminder(ReminderMethod.ALARM, 10))
        val list = scheduler.upcomingAlarms(now)
        assertEquals(listOf("soon", "late"), list.map { it.eventId })
        assertEquals(now + 90 * minute, list[0].fireAt)
        assertEquals(10, list[0].minutesBefore)
    }

    @Test
    fun closingAnAlarmEarlyRemovesItFromTheListAndFromAlarmManager() = runTest {
        val now = System.currentTimeMillis()
        addEvent("x", now + 100 * minute, now + 160 * minute, Reminder(ReminderMethod.ALARM, 10), Reminder(ReminderMethod.ALARM, 30))
        scheduler.reschedule(now)
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertEquals(2, alarms.scheduledAlarms.size)
        val first = scheduler.upcomingAlarms(now).first() // the 30-minute one, ringing first
        assertEquals(30, first.minutesBefore)
        engine.skip(first)
        assertEquals(listOf(10), scheduler.upcomingAlarms(now).map { it.minutesBefore })
        assertEquals(1, alarms.scheduledAlarms.size)
        // A snoozed alarm can be closed too.
        engine.snooze(ReminderAlert(first.key, "x", now + 100 * minute, now + 160 * minute, false, "T", "", "", null, ReminderMethod.ALARM, 10), 5)
        val snoozed = scheduler.upcomingAlarms().first { it.snoozed }
        engine.skip(snoozed)
        assertTrue(scheduler.upcomingAlarms().none { it.snoozed })
    }

    @Test
    fun theNextRemindersAreRegisteredWithAlarmManager() = runTest {
        val now = System.currentTimeMillis()
        addEvent("n", now + 60 * minute, now + 120 * minute, Reminder(ReminderMethod.POPUP, 10), Reminder(ReminderMethod.ALARM, 30))
        scheduler.reschedule(now)
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertEquals(2, alarms.scheduledAlarms.size)
        assertEquals(setOf(now + 50 * minute, now + 30 * minute), alarms.scheduledAlarms.map { it.triggerAtMs }.toSet())
        // The event moves later: the old alarms go and two new ones are set.
        db.instanceDao().deleteForEvent("n")
        db.instanceDao().insert(listOf(InstanceEntity("n", now + 600 * minute, now + 660 * minute, 0, 1, allDay = false)))
        scheduler.reschedule(now)
        assertEquals(setOf(now + 590 * minute, now + 570 * minute), alarms.scheduledAlarms.map { it.triggerAtMs }.toSet())
    }
}
