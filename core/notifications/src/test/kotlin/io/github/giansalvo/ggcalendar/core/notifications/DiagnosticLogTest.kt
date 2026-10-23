package io.github.giansalvo.ggcalendar.core.notifications

import android.content.Context
import android.net.Uri
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
import io.github.giansalvo.ggcalendar.core.model.Diag
import io.github.giansalvo.ggcalendar.core.model.FileLog
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
import org.robolectric.annotation.Config
import java.io.File

/** The diagnostic log of the Advanced settings, and the lines it holds about reminders (the hidden-calendar bug). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticLogTest {
    private val minute = 60_000L
    private val visibleId = "me@x.com|me@x.com"
    private val hiddenId = "me@x.com|other"
    private lateinit var context: Context
    private lateinit var db: GGCalendarDatabase
    private lateinit var log: DiagnosticLog
    private lateinit var engine: ReminderEngine
    private lateinit var scheduler: ReminderScheduler
    private lateinit var store: DirectBootStore

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        DiagnosticLog.dir(context).deleteRecursively()
        Diag.file = null
        DiagnosticLog.install(context)
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        val notifier = ReminderNotifier(context).also { it.createChannels() }
        val prefs = AppPreferences(context)
        store = DirectBootStore(context.filesDir.resolve("reminders"))
        val reconciler = JournalReconciler(db, store)
        scheduler = ReminderScheduler(context, db, prefs, store, reconciler)
        engine = ReminderEngine(context, db, prefs, scheduler, notifier, reconciler)
        prefs.setReminderLastCheck(System.currentTimeMillis() - 2 * 60 * minute)
        db.accountDao().insert(AccountEntity("me@x.com"))
        db.calendarDao().upsert(CalendarEntity(visibleId, "me@x.com", "me@x.com", "Me", "#1A73E8", "owner"))
        db.calendarDao().upsert(CalendarEntity(hiddenId, "me@x.com", "other", "Other", "#EA4335", "owner"))
        log = DiagnosticLog(context, db)
    }

    @After
    fun tearDown() {
        db.close()
        Diag.file = null
        DiagnosticLog.dir(context).deleteRecursively()
        DiagnosticLog.install(context)
    }

    private suspend fun addEvent(id: String, title: String, calendar: String, begin: Long, end: Long, vararg reminders: Reminder) {
        db.eventDao().upsert(listOf(EventEntity(id = id, calendarId = calendar, title = title, startUtc = begin, endUtc = end, reminders = reminders.toList())))
        db.instanceDao().insert(listOf(InstanceEntity(id, begin, end, 0, 1, allDay = false)))
    }

    private fun text(): String = Diag.file!!.text()

    // ---------------------------------------------------------------- the switch

    @Test
    fun theLogIsOffByDefault() {
        assertFalse(log.isEnabled())
        assertFalse(log.enabled.value)
        assertNull(Diag.file)
    }

    @Test
    fun turningItOnMakesTheFileTheSinkAndWritesTheState() = runTest {
        log.setEnabled(true)
        assertTrue(log.isEnabled())
        assertTrue(log.enabled.value)
        assertNotNull(Diag.file)
        val t = text()
        assertTrue(t, "state (log turned on)" in t)
        assertTrue("both calendars with their visibility", "'Me'" in t && "'Other'" in t && "visible" in t)
    }

    @Test
    fun turningItOffDeletesTheLog() = runTest {
        log.setEnabled(true)
        Diag.log { "something" }
        Diag.file!!.flush()
        log.setEnabled(false)
        assertFalse(log.isEnabled())
        assertFalse(log.enabled.value)
        assertNull(Diag.file)
        assertEquals(0, DiagnosticLog.dir(context).listFiles()?.size ?: 0)
        assertNotNull("a new writer finds nothing left", FileLog(DiagnosticLog.dir(context)).also { assertTrue(it.isEmpty()) })
    }

    @Test
    fun turningItOnAgainStartsFromAnEmptyLog() = runTest {
        log.setEnabled(true)
        Diag.log { "first run" }
        log.setEnabled(false)
        log.setEnabled(true)
        assertFalse("first run" in text())
    }

    @Test
    fun theSwitchSurvivesTheEndOfTheProcess() = runTest {
        log.setEnabled(true)
        Diag.file = null // a new process: nothing is in memory
        DiagnosticLog.install(context)
        assertNotNull(Diag.file)
        assertTrue(DiagnosticLog(context, db).isEnabled())
    }

    @Test
    fun theLinesSurviveAReopeningOfTheApp() = runTest {
        log.setEnabled(true)
        Diag.log { "before closing" }
        Diag.file!!.flush()
        Diag.file = null
        DiagnosticLog.install(context)
        Diag.log { "after reopening" }
        val t = text()
        assertTrue("before closing" in t && "after reopening" in t)
        assertTrue(t.indexOf("before closing") < t.indexOf("after reopening"))
    }

    @Test
    fun anOffSwitchLeavesNoFileAtStart() {
        DiagnosticLog.install(context)
        assertNull(Diag.file)
        assertFalse(File(DiagnosticLog.dir(context), FileLog.CURRENT).exists())
    }

    @Test
    fun nothingIsWrittenWhileItIsOff() = runTest {
        val before = DiagnosticLog.dir(context).listFiles()?.size ?: 0
        Diag.log { "nobody listens" }
        engine.catchUp()
        scheduler.reschedule()
        assertEquals(before, DiagnosticLog.dir(context).listFiles()?.size ?: 0)
    }

    // ---------------------------------------------------------------- the mail

    @Test
    fun theSubjectHasTheVersionAndTheBuild() {
        assertEquals("GGCalendar Android: 1.0.3 (16)", DiagnosticLog.subject("1.0.3", 16))
    }

    @Test
    fun theDestinationAndTheContactPageAreTheOnesTheOwnerChose() {
        assertEquals("profgusinu+ggcalendar@gmail.com", DiagnosticLog.ADDRESS)
        assertEquals("https://profgusinu.altervista.org/GGCalendarAndroid/index.html#contatti", DiagnosticLog.CONTACT_PAGE)
    }

    @Test
    fun theMailIsANewMessageToTheAuthorWithTheLogAttached() {
        val uri = Uri.parse("content://x.share/share/ggcalendar-log.txt")
        val mail = DiagnosticLog.mailIntentFor(uri, DiagnosticLog.subject("1.0.3", 16), "body text")
        assertEquals("a SEND carries the attachment", android.content.Intent.ACTION_SEND, mail.action)
        assertEquals("only mail apps answer: the selector is mailto:", android.content.Intent.ACTION_SENDTO, mail.selector?.action)
        assertEquals("mailto", mail.selector?.data?.scheme)
        assertEquals(listOf("profgusinu+ggcalendar@gmail.com"), mail.getStringArrayExtra(android.content.Intent.EXTRA_EMAIL)!!.toList())
        assertEquals("GGCalendar Android: 1.0.3 (16)", mail.getStringExtra(android.content.Intent.EXTRA_SUBJECT))
        assertEquals("body text", mail.getStringExtra(android.content.Intent.EXTRA_TEXT))
        @Suppress("DEPRECATION")
        assertEquals(uri, mail.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM))
        assertTrue(mail.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, mail.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun theFirstLineNamesThePhoneAndTheApp() {
        assertEquals("Samsung SM-A235F, Android 14 (API 34), GGCalendar 1.0.3 (16)", DiagnosticLog.deviceLine("Samsung", "SM-A235F", "14", 34, "1.0.3", 16))
    }

    @Test
    fun theAttachmentIsTheWholeLogOlderFileFirst() = runTest {
        log.setEnabled(true)
        val dir = DiagnosticLog.dir(context)
        File(dir, FileLog.PREVIOUS).writeText("older line\n")
        Diag.log { "newer line" }
        val file = log.attachment()
        val t = file.readText()
        assertTrue(t.indexOf("older line") in 0 until t.indexOf("newer line"))
        assertTrue(file.name, Regex("""^ggcalendar_\d{8}-\d{6}_.+-\d+\.log$""").matches(file.name))
    }

    @Test
    fun theLogCanBeSavedWhereTheUserChooses() = runTest {
        log.setEnabled(true)
        Diag.log { "to save" }
        val target = File(context.cacheDir, "saved.txt")
        assertTrue(log.saveTo(Uri.fromFile(target)))
        assertTrue("to save" in target.readText())
    }

    @Test
    fun savingToAnUnwritableTargetReportsFailure() = runTest {
        log.setEnabled(true)
        assertFalse(log.saveTo(Uri.parse("content://no.such.provider/file")))
    }

    @Test
    fun theFileNameHasTheMomentOfTheExportAndTheVersion() {
        // 2026-10-23 10:15:30 UTC is 12:15:30 in Rome (summer time)
        assertEquals("ggcalendar_20261023-101530_1.0.3-16.log", DiagnosticLog.fileName(1_792_750_530_123L, java.time.ZoneOffset.UTC, "1.0.3", 16))
        assertEquals("ggcalendar_20261023-121530_1.0.3-16.log", DiagnosticLog.fileName(1_792_750_530_123L, java.time.ZoneId.of("Europe/Rome"), "1.0.3", 16))
        assertFalse("no colons in a file name", DiagnosticLog.fileName(1_792_750_530_123L, java.time.ZoneOffset.UTC, "1.0.3", 16).contains(':'))
        assertEquals("a version with odd characters stays a valid name", "ggcalendar_20261023-101530_1.0.3_beta_-16.log", DiagnosticLog.fileName(1_792_750_530_123L, java.time.ZoneOffset.UTC, "1.0.3 beta?", 16))
        assertTrue(log.suggestedFileName().startsWith("ggcalendar_") && log.suggestedFileName().endsWith(".log"))
    }

    @Test
    fun aNewAttachmentReplacesThePreviousOne() = runTest {
        log.setEnabled(true)
        val first = log.attachment(1_792_750_530_000L)
        val second = log.attachment(1_792_750_590_000L)
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertEquals(1, second.parentFile!!.listFiles { f -> f.name.endsWith(".log") }!!.size)
    }

    @Test
    @Config(sdk = [35], qualifiers = "en")
    fun theMailBodyIsInEnglish() = assertEquals("Describe what happened and when. Please keep the attached log.", context.getString(R.string.log_mail_body))

    @Test
    @Config(sdk = [35], qualifiers = "it")
    fun theMailBodyIsInItalian() = assertEquals("Descrivi cosa è successo e quando. Lascia allegato il registro.", context.getString(R.string.log_mail_body))

    @Test
    @Config(sdk = [35], qualifiers = "de")
    fun theMailBodyIsInGerman() = assertTrue(context.getString(R.string.log_mail_body).startsWith("Beschreibe, was passiert ist"))

    @Test
    @Config(sdk = [35], qualifiers = "es")
    fun theMailBodyIsInSpanish() = assertTrue(context.getString(R.string.log_mail_body).startsWith("Describe qué pasó"))

    // ---------------------------------------------------------------- the lines about reminders

    @Test
    fun aShownReminderLogsTitleCalendarAndVisibility() = runTest {
        log.setEnabled(true)
        val now = System.currentTimeMillis()
        val key = DueReminder.keyOf("v", now + 10 * minute, Reminder(ReminderMethod.POPUP, 5))
        addEvent("v", "Dentist", visibleId, now + 10 * minute, now + 40 * minute, Reminder(ReminderMethod.POPUP, 5))
        engine.onAlarm(key)
        val t = text()
        assertTrue(t, "reminder alarm went off: key=$key" in t)
        assertTrue(t, "reminder SHOWN by alarm: 'Dentist' calendar='Me' (visible=true)" in t)
        assertTrue("the popup is logged where it is presented", "present popup: 'Dentist'" in t)
    }

    @Test
    fun aReminderOfAHiddenCalendarIsSkippedAndTheLogSaysWhy() = runTest {
        log.setEnabled(true)
        val now = System.currentTimeMillis()
        val key = DueReminder.keyOf("h", now + 10 * minute, Reminder(ReminderMethod.POPUP, 5))
        addEvent("h", "Secret meeting", hiddenId, now + 10 * minute, now + 40 * minute, Reminder(ReminderMethod.POPUP, 5))
        CalendarVisibility(db).set(hiddenId, false)
        engine.onAlarm(key)
        val t = text()
        assertTrue(t, "reminder skipped (calendar 'Other' is HIDDEN): 'Secret meeting'" in t)
        assertFalse("nothing is presented", "present popup" in t)
        assertFalse(t, "SHOWN" in t)
    }

    @Test
    fun theUserHidingACalendarIsLogged() = runTest {
        log.setEnabled(true)
        CalendarVisibility(db).set(hiddenId, false)
        assertTrue(text(), FileLog.redact("calendar visibility set by the user: 'Other' ($hiddenId) visible=false") in text())
        assertFalse("the address of the calendar id is shortened", "me@x.com" in text())
    }

    @Test
    fun aReminderAlreadyShownIsSkippedWithItsReason() = runTest {
        log.setEnabled(true)
        val now = System.currentTimeMillis()
        val key = DueReminder.keyOf("d", now + 10 * minute, Reminder(ReminderMethod.POPUP, 5))
        addEvent("d", "Twice", visibleId, now + 10 * minute, now + 40 * minute, Reminder(ReminderMethod.POPUP, 5))
        engine.onAlarm(key)
        engine.onAlarm(key)
        assertTrue(text(), "reminder skipped (already shown): key=$key" in text())
    }

    @Test
    fun anEndedEventIsSkippedWithItsReason() = runTest {
        log.setEnabled(true)
        val now = System.currentTimeMillis()
        val key = DueReminder.keyOf("e", now - 60 * minute, Reminder(ReminderMethod.POPUP, 5))
        addEvent("e", "Over", visibleId, now - 60 * minute, now - 30 * minute, Reminder(ReminderMethod.POPUP, 5))
        engine.onAlarm(key)
        assertTrue(text(), "reminder skipped (the event is over): 'Over'" in text())
    }

    @Test
    fun anOccurrenceThatIsGoneIsSkippedWithItsReason() = runTest {
        log.setEnabled(true)
        val now = System.currentTimeMillis()
        engine.onAlarm(DueReminder.keyOf("gone", now + 5 * minute, Reminder(ReminderMethod.POPUP, 5)))
        assertTrue(text(), "no such occurrence any more" in text())
    }

    @Test
    fun theCatchUpLogsHowManyRemindersWereDue() = runTest {
        log.setEnabled(true)
        val now = System.currentTimeMillis()
        addEvent("c", "Missed", visibleId, now - 5 * minute, now + 55 * minute, Reminder(ReminderMethod.POPUP, 30))
        addEvent("c2", "Missed in hidden", hiddenId, now - 5 * minute, now + 55 * minute, Reminder(ReminderMethod.POPUP, 30))
        CalendarVisibility(db).set(hiddenId, false)
        engine.catchUp()
        val t = text()
        assertTrue(t, "catch-up: 1 occurrences of visible calendars, 1 due" in t)
        assertTrue(t, "reminder SHOWN by catch-up: 'Missed' calendar='Me'" in t)
        assertFalse("the hidden calendar is not caught up", "Missed in hidden" in t)
    }

    @Test
    fun theSchedulerLogsTheNextRemindersWithTheirTitles() = runTest {
        log.setEnabled(true)
        val now = System.currentTimeMillis()
        addEvent("n", "Next one", visibleId, now + 100 * minute, now + 160 * minute, Reminder(ReminderMethod.POPUP, 10))
        scheduler.reschedule(now)
        assertTrue(text(), "reschedule: 1 occurrences of visible calendars, 1 alarms wanted, 0 cancelled; next: 'Next one'" in text())
    }

    @Test
    fun hidingACalendarCancelsItsAlarmsAndTheLogCountsThem() = runTest {
        log.setEnabled(true)
        val now = System.currentTimeMillis()
        addEvent("k", "Kept", visibleId, now + 100 * minute, now + 160 * minute, Reminder(ReminderMethod.POPUP, 10))
        addEvent("g", "Gone", hiddenId, now + 100 * minute, now + 160 * minute, Reminder(ReminderMethod.POPUP, 10))
        scheduler.reschedule(now)
        CalendarVisibility(db).set(hiddenId, false)
        scheduler.reschedule(now)
        val t = text()
        assertTrue(t, "1 alarms wanted, 1 cancelled; next: 'Kept'" in t)
    }

    @Test
    fun aSnoozeAfterHidingIsLoggedAsHidden() = runTest {
        log.setEnabled(true)
        val now = System.currentTimeMillis()
        addEvent("s", "Snoozed", hiddenId, now + 60 * minute, now + 120 * minute, Reminder(ReminderMethod.POPUP, 10))
        val snoozeKey = DueReminder.keyOf("s", now + 60 * minute, Reminder(ReminderMethod.POPUP, 10))
        db.reminderDao().insertSnoozed(
            io.github.giansalvo.ggcalendar.core.database.ReminderSnoozedEntity(fireAt = now - minute, eventId = "s", beginUtc = now + 60 * minute, method = "POPUP", minutes = 10),
        )
        CalendarVisibility(db).set(hiddenId, false) // this forgets the snooze: put it back, as a snooze made while locked would
        db.reminderDao().insertSnoozed(
            io.github.giansalvo.ggcalendar.core.database.ReminderSnoozedEntity(fireAt = now - minute, eventId = "s", beginUtc = now + 60 * minute, method = "POPUP", minutes = 10),
        )
        engine.catchUp()
        assertTrue(snoozeKey, "reminder SHOWN after snooze: 'Snoozed' calendar='Other' (visible=false, HIDDEN: a snooze rings anyway)" in text())
    }

    @Test
    fun directBootLogsThatItDoesNotCheckVisibility() = runTest {
        log.setEnabled(true)
        val now = System.currentTimeMillis()
        addEvent("b", "Boot", visibleId, now + 10 * minute, now + 40 * minute, Reminder(ReminderMethod.POPUP, 5))
        scheduler.reschedule(now)
        val key = DueReminder.keyOf("b", now + 10 * minute, Reminder(ReminderMethod.POPUP, 5))
        LightReminders(context, store).onAlarm(key)
        assertTrue(text(), "reminder SHOWN in direct boot (from the snapshot, the visibility of the calendar is not checked here): 'Boot'" in text())
    }

    @Test
    fun everyLineHasDateTimeThreadWindowAndText() = runTest {
        log.setEnabled(true)
        Diag.foreground = true
        Diag.screen = "settings"
        Diag.log { "a line" }
        Diag.foreground = false
        val lines = text().trim().lines()
        val rx = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[[^\]]+] \[[^\]]+] .+$""")
        assertTrue(lines.joinToString("\n"), lines.all { rx.matches(it) })
        assertTrue(lines.any { "[settings] a line" in it })
    }
}
