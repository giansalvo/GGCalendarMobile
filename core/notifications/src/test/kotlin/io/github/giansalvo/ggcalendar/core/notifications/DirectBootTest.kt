package io.github.giansalvo.ggcalendar.core.notifications

import android.app.AlarmManager
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Direct boot: the snapshot and journal files, the light layer that works without Room, and the reconciliation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DirectBootTest {
    private val minute = 60_000L
    private val calendarId = "me@x.com|me@x.com"
    private lateinit var context: Context
    private lateinit var db: GGCalendarDatabase
    private lateinit var store: DirectBootStore
    private lateinit var scheduler: ReminderScheduler
    private lateinit var engine: ReminderEngine
    private lateinit var reconciler: JournalReconciler
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        store = DirectBootStore(context.filesDir.resolve("boot-test"))
        prefs = AppPreferences(context)
        reconciler = JournalReconciler(db, store)
        val notifier = ReminderNotifier(context).also { it.createChannels() }
        scheduler = ReminderScheduler(context, db, prefs, store, reconciler)
        engine = ReminderEngine(context, db, prefs, scheduler, notifier, reconciler)
        db.accountDao().insert(AccountEntity("me@x.com"))
        db.calendarDao().upsert(CalendarEntity(calendarId, "me@x.com", "me@x.com", "Me", "#1A73E8", "owner"))
    }

    @After
    fun tearDown() = db.close()

    private fun alert(id: String, begin: Long, method: ReminderMethod = ReminderMethod.POPUP, minutes: Int = 10) = ReminderAlert(
        key = DueReminder.keyOf(id, begin, Reminder(method, minutes)),
        eventId = id, beginUtc = begin, endUtc = begin + 60 * minute, allDay = false,
        title = "Event $id", location = "Room 1", description = "A description with \"quotes\"\nand lines", meetLink = "https://meet.google.com/x", method = method, minutes = minutes,
    )

    private fun entry(id: String, begin: Long, fireAt: Long, method: ReminderMethod = ReminderMethod.POPUP) =
        alert(id, begin, method).let { SnapshotEntry(it.key, fireAt, it) }

    private fun shown() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private fun alarmManager() = shadowOf(context.getSystemService(AlarmManager::class.java))

    private suspend fun addEvent(id: String, begin: Long, vararg reminders: Reminder) {
        db.eventDao().upsert(listOf(EventEntity(id = id, calendarId = calendarId, title = "Event $id", startUtc = begin, endUtc = begin + 60 * minute, reminders = reminders.toList())))
        db.instanceDao().insert(listOf(InstanceEntity(id, begin, begin + 60 * minute, 0, 1, allDay = false)))
    }

    @Test
    fun theSnapshotSurvivesAWriteAndReadWithEveryField() {
        val now = System.currentTimeMillis()
        val snapshot = Snapshot(now, listOf(entry("a", now + 60 * minute, now + 50 * minute), entry("b", now + 90 * minute, now + 60 * minute, ReminderMethod.ALARM)))
        store.writeSnapshot(snapshot)
        assertEquals(snapshot, store.readSnapshot())
        // Atomic write: no temporary file is left behind and a second write replaces the first.
        store.writeSnapshot(Snapshot(now, emptyList()))
        assertEquals(emptyList<SnapshotEntry>(), store.readSnapshot()?.entries)
        assertTrue(context.filesDir.resolve("boot-test").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun aMissingOrDamagedSnapshotReadsAsNothing() {
        assertNull(store.readSnapshot())
        context.filesDir.resolve("boot-test").mkdirs()
        context.filesDir.resolve("boot-test/snapshot.json").writeText("{ not json")
        assertNull(store.readSnapshot())
        assertEquals(AppPreferences.DEFAULT_ALARM_MINUTES, store.alarmMinutes())
        store.setAlarmMinutes(7)
        assertEquals(7, store.alarmMinutes())
    }

    @Test
    fun theJournalIsEmptiedWhenTaken() {
        val now = System.currentTimeMillis()
        store.appendJournal(JournalEntry.Shown("k1", now))
        store.appendJournal(JournalEntry.Snoozed("snooze|1", now + 5 * minute, alert("a", now)))
        assertEquals(2, store.readJournal().size)
        assertEquals(2, store.takeJournal().size)
        assertTrue(store.readJournal().isEmpty())
        assertTrue(store.takeJournal().isEmpty())
    }

    @Test
    fun reschedulingWritesTheSnapshotWithAlarmsAndPopups() = runTest {
        val now = System.currentTimeMillis()
        addEvent("n", now + 60 * minute, Reminder(ReminderMethod.POPUP, 10), Reminder(ReminderMethod.ALARM, 30))
        prefs.setAlarmMinutes(4)
        scheduler.reschedule(now)
        val snapshot = store.readSnapshot()!!
        assertEquals(setOf(now + 50 * minute, now + 30 * minute), snapshot.entries.map { it.fireAt }.toSet())
        assertEquals(setOf(ReminderMethod.POPUP, ReminderMethod.ALARM), snapshot.entries.map { it.alert.method }.toSet())
        assertEquals("Event n", snapshot.entries.first().alert.title)
        assertEquals(4, store.alarmMinutes())
        assertEquals(2, store.scheduledKeys().size)
    }

    @Test
    fun afterARebootTheLightLayerRegistersTheAlarmsFromTheSnapshotAlone() {
        val now = System.currentTimeMillis()
        store.writeSnapshot(Snapshot(now, listOf(entry("a", now + 60 * minute, now + 50 * minute), entry("b", now + 90 * minute, now + 60 * minute, ReminderMethod.ALARM))))
        LightReminders(context, store).restore(now)
        assertEquals(setOf(now + 50 * minute, now + 60 * minute), alarmManager().scheduledAlarms.map { it.triggerAtMs }.toSet())
        assertEquals(2, store.scheduledKeys().size)
    }

    @Test
    fun aReminderThatWentOffWhilePoweredOffIsShownOnceInDirectBoot() {
        val now = System.currentTimeMillis()
        // Fired 20 minutes ago, the event started 10 minutes ago and goes on.
        store.writeSnapshot(Snapshot(now, listOf(entry("a", now - 10 * minute, now - 20 * minute))))
        val light = LightReminders(context, store)
        light.restore(now)
        assertEquals(1, shown().size)
        light.restore(now)
        assertEquals("not shown again", 1, shown().size)
        assertEquals(listOf("a"), store.readJournal().filterIsInstance<JournalEntry.Shown>().map { it.key.substringBefore('|') })
    }

    @Test
    fun anAlarmWentOffInDirectBootShowsOnlyIfItsEventIsNotOver() {
        val now = System.currentTimeMillis()
        val over = Snapshot(now, listOf(entry("old", now - 3 * 60 * minute, now - 3 * 60 * minute - 10 * minute)))
        store.writeSnapshot(over)
        LightReminders(context, store).onAlarm(over.entries.single().key, now)
        assertTrue(shown().isEmpty())
    }

    @Test
    fun snoozingWhileLockedKeepsAJournalAndAnAlarmAndRoomLearnsItAtTheUnlock() = runTest {
        val now = System.currentTimeMillis()
        val begin = now + 60 * minute
        addEvent("s", begin, Reminder(ReminderMethod.POPUP, 10))
        val a = alert("s", begin)
        val light = LightReminders(context, store)
        light.snooze(a, 5, now)
        assertEquals(setOf(now + 5 * minute), alarmManager().scheduledAlarms.map { it.triggerAtMs }.toSet())
        assertNotNull(store.readSnapshot()!!.entries.singleOrNull { it.fireAt == now + 5 * minute })
        // The unlock: Room learns the snooze.
        reconciler.reconcile()
        val snoozed = db.reminderDao().snoozed().single()
        assertEquals(now + 5 * minute, snoozed.fireAt)
        assertEquals("s", snoozed.eventId)
        // Again: nothing doubles.
        store.appendJournal(JournalEntry.Snoozed("snooze|${now + 5 * minute}", now + 5 * minute, a))
        reconciler.reconcile()
        assertEquals(1, db.reminderDao().snoozed().size)
    }

    @Test
    fun aSnoozeThatRangInDirectBootNeverReachesRoom() = runTest {
        val now = System.currentTimeMillis()
        val a = alert("s", now + 60 * minute)
        store.appendJournal(JournalEntry.Snoozed("snooze|123", now - minute, a))
        store.appendJournal(JournalEntry.Shown("snooze|123", now - minute))
        reconciler.reconcile()
        assertTrue(db.reminderDao().snoozed().isEmpty())
    }

    @Test
    fun remindersShownInDirectBootAreNotShownAgainAfterTheUnlock() = runTest {
        val now = System.currentTimeMillis()
        // Started 5 minutes ago, 30-minute reminder fired 35 minutes ago.
        val begin = now - 5 * minute
        addEvent("a", begin, Reminder(ReminderMethod.POPUP, 30))
        prefs.setReminderLastCheck(now - 2 * 60 * minute)
        store.appendJournal(JournalEntry.Shown(DueReminder.keyOf("a", begin, Reminder(ReminderMethod.POPUP, 30)), begin - 30 * minute))
        engine.catchUp()
        assertTrue("the journal says it was shown", shown().isEmpty())
        assertFalse(db.reminderDao().fired(listOf(DueReminder.keyOf("a", begin, Reminder(ReminderMethod.POPUP, 30)))).isEmpty())
    }
}
