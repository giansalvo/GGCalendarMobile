package io.github.giansalvo.ggcalendar.core.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.data.CalendarVisibility
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
    private lateinit var store: DirectBootStore
    private lateinit var reconciler: JournalReconciler

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        val notifier = ReminderNotifier(context).also { it.createChannels() }
        prefs = AppPreferences(context)
        store = DirectBootStore(context.filesDir.resolve("reminders"))
        reconciler = JournalReconciler(db, store)
        scheduler = ReminderScheduler(context, db, prefs, store, reconciler)
        engine = ReminderEngine(context, db, prefs, scheduler, notifier, reconciler)
        // The first catch-up of a fresh install looks back from now: simulate a phone that was off for a while.
        prefs.setReminderLastCheck(System.currentTimeMillis() - 2 * 60 * minute)
        manager = context.getSystemService(NotificationManager::class.java)
        db.accountDao().insert(AccountEntity("me@x.com"))
        db.calendarDao().upsert(CalendarEntity(calendarId, "me@x.com", "me@x.com", "Me", "#1A73E8", "owner"))
    }

    private val visibility get() = CalendarVisibility(db)
    private val alarmManager get() = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))

    private fun snoozeAlert(id: String, begin: Long, end: Long, method: ReminderMethod, minutes: Int) = ReminderAlert(
        key = DueReminder.keyOf(id, begin, Reminder(method, minutes)),
        eventId = id, beginUtc = begin, endUtc = end, allDay = false,
        title = "Event $id", location = "", description = "", meetLink = null, method = method, minutes = minutes,
    )

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
    fun anAlarmOfAnEventWhoseIdHasBarsShowsItsPopup() = runTest {
        val now = System.currentTimeMillis()
        // An imported event has the id calendar|UID: the key of its reminder has more bars than the four parts it is made of.
        val id = "local:abc|uid-1"
        addEvent(id, now + 10 * minute, now + 40 * minute, Reminder(ReminderMethod.POPUP, 5))
        engine.onAlarm(DueReminder.keyOf(id, now + 10 * minute, Reminder(ReminderMethod.POPUP, 5)))
        assertEquals(1, shown().size)
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

    @Test
    fun aHiddenCalendarNeitherRingsNorIsRegisteredNorListed() = runTest {
        val now = System.currentTimeMillis()
        addEvent("future", now + 100 * minute, now + 160 * minute, Reminder(ReminderMethod.ALARM, 10), Reminder(ReminderMethod.POPUP, 30))
        addEvent("missed", now - 5 * minute, now + 55 * minute, Reminder(ReminderMethod.POPUP, 30))
        visibility.set(calendarId, false, now)
        scheduler.reschedule(now)
        assertTrue(alarmManager.scheduledAlarms.isEmpty())
        assertTrue(scheduler.upcomingAlarms(now).isEmpty())
        engine.catchUp()
        assertTrue(shown().isEmpty())
        // An alarm registered earlier that still goes off finds the calendar hidden.
        engine.onAlarm(DueReminder.keyOf("future", now + 100 * minute, Reminder(ReminderMethod.ALARM, 10)))
        assertTrue(shown().isEmpty())
    }

    @Test
    fun hidingCancelsTheRegisteredAlarmsAndTheSnoozes() = runTest {
        val now = System.currentTimeMillis()
        addEvent("x", now + 100 * minute, now + 160 * minute, Reminder(ReminderMethod.ALARM, 10))
        engine.snooze(snoozeAlert("x", now + 100 * minute, now + 160 * minute, ReminderMethod.ALARM, 10), 5)
        scheduler.reschedule(now)
        assertEquals(2, alarmManager.scheduledAlarms.size)
        visibility.set(calendarId, false, now)
        scheduler.reschedule(now)
        assertTrue(db.reminderDao().snoozed().isEmpty())
        assertTrue(alarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun showingAgainRegistersWhatIsToComeAndDoesNotRingWhatWasMissed() = runTest {
        val now = System.currentTimeMillis()
        addEvent("future", now + 100 * minute, now + 160 * minute, Reminder(ReminderMethod.ALARM, 10))
        addEvent("missed", now - 5 * minute, now + 55 * minute, Reminder(ReminderMethod.POPUP, 30))
        visibility.set(calendarId, false, now - 60 * minute)
        visibility.set(calendarId, true, now)
        scheduler.reschedule(now)
        assertEquals(listOf(now + 90 * minute), alarmManager.scheduledAlarms.map { it.triggerAtMs })
        assertEquals(listOf("future"), scheduler.upcomingAlarms(now).map { it.eventId })
        engine.catchUp()
        assertTrue("the reminder that went off while hidden is not shown", shown().isEmpty())
    }

    @Test
    fun aReminderThatGoesOffAfterShowingAgainStillRings() = runTest {
        val now = System.currentTimeMillis()
        visibility.set(calendarId, false, now - 60 * minute)
        visibility.set(calendarId, true, now - 30 * minute)
        // Created after the calendar was shown again, and past due: an ordinary missed reminder.
        addEvent("later", now - 5 * minute, now + 55 * minute, Reminder(ReminderMethod.POPUP, 30))
        engine.catchUp()
        assertEquals(1, shown().size)
    }

    @Test
    fun snoozeAskedOnAHiddenCalendarRingsAnyway() = runTest {
        val now = System.currentTimeMillis()
        addEvent("x", now + 100 * minute, now + 160 * minute, Reminder(ReminderMethod.ALARM, 10))
        visibility.set(calendarId, false, now)
        // A notification shown before still has its "Snooze" button: the user asked for another reminder.
        engine.snooze(snoozeAlert("x", now + 100 * minute, now + 160 * minute, ReminderMethod.ALARM, 10), 5)
        assertEquals(1, db.reminderDao().snoozed().size)
        assertEquals(1, alarmManager.scheduledAlarms.size)
        assertEquals(listOf(true), scheduler.upcomingAlarms(now).map { it.snoozed })
        // Hiding it again forgets that snooze too.
        visibility.set(calendarId, false, now)
        assertTrue(db.reminderDao().snoozed().isEmpty())
    }
}
