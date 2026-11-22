package io.github.giansalvo.ggcalendar.core.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * (U148, U149, U150) The alarm notification carries a group of its own. Without it Android 16 folds two notifications of the app (a popup left in the
 * shade and the alarm) into an automatic group and does not launch the full-screen intent of the alarm: the screen stays dark
 * (seen on the API 36 emulator, case T52).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifier = ReminderNotifier(context).also { it.createChannels() }

    private fun alert(key: String, method: ReminderMethod = ReminderMethod.ALARM) = ReminderAlert(
        key = key, eventId = "e-$key", beginUtc = 1_800_000_000_000L, endUtc = 1_800_003_600_000L, allDay = false,
        title = "Prova $key", location = "", description = "", meetLink = null, method = method, minutes = 10,
    )

    @Test
    fun `an alarm notification has a group of its own, different for every alarm`() {
        val a = alert("a")
        val b = alert("b")
        val na = notifier.alarmNotification(a)
        val nb = notifier.alarmNotification(b)
        assertEquals("alarm_" + a.notificationId, na.group)
        assertEquals("alarm_" + b.notificationId, nb.group)
        assertNotEquals(na.group, nb.group)
    }

    @Test
    fun `the group is kept for the queued and the quiet variants of the alarm`() {
        val a = alert("c")
        assertEquals("alarm_" + a.notificationId, notifier.alarmNotification(a, fullScreen = false).group)
        assertEquals("alarm_" + a.notificationId, notifier.alarmNotification(a, fullScreen = false, quiet = true).group)
    }

    @Test
    fun `the alarm asks for the full-screen intent only in the build that has it`() {
        // (U335) -PenableFullScreen=false (Google Play) never sets it; the default build does, unless the caller says otherwise.
        val n = notifier.alarmNotification(alert("d"))
        if (BuildConfig.FULL_SCREEN) assertNotNull(n.fullScreenIntent) else assertNull(n.fullScreenIntent)
        assertNull(notifier.alarmNotification(alert("d"), fullScreen = false).fullScreenIntent)
    }

    @Test
    fun `the permission status reports full screen as unavailable only in the build without it`() {
        // (U336) null means "no such permission here": the settings row and the first-start question are skipped.
        val status = NotificationChecks(context).status()
        if (BuildConfig.FULL_SCREEN) assertNotNull(status.fullScreen) else assertNull(status.fullScreen)
    }

    @Test
    fun `a reminder notification removes itself when the event ends`() {
        // (U349) issue #62: without a timeout the popup stayed in the shade, with its "in 29 min", long after the event.
        val a = alert("e", ReminderMethod.POPUP).copy(endUtc = System.currentTimeMillis() + 3_600_000L)
        val ms = ReminderNotifier.expiresAfter(a, System.currentTimeMillis())!!
        assertTrue(ms in 3_590_000L..3_600_000L)
        notifier.showPopup(a)
        val shown = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(a.notificationId)
        assertTrue(shown.timeoutAfter in 3_000_000L..3_600_000L)
    }

    @Test
    fun `the expiry is the time left to the end, and none when the event has already ended`() {
        // (U350) never a zero or negative timeout: it would remove the notification at once.
        val a = alert("f")
        assertEquals(1_000L, ReminderNotifier.expiresAfter(a, a.endUtc - 1_000L))
        assertNull(ReminderNotifier.expiresAfter(a, a.endUtc))
        assertNull(ReminderNotifier.expiresAfter(a, a.endUtc + 5_000L))
    }

    @Test
    fun `the missed alarm notification also expires at the end of the event`() {
        // (U351)
        val a = alert("g").copy(endUtc = System.currentTimeMillis() + 1_800_000L)
        notifier.showMissed(a)
        val shown = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(a.notificationId + 1)
        assertTrue(shown.timeoutAfter in 1_700_000L..1_800_000L)
    }

    @Test
    fun `the missed alarm says so next to the app name and keeps the event as its title`() {
        // (U360) "Missed alarm" in the line under the app name; the title is just the event, as in the other notifications.
        val a = alert("o").copy(title = "Riunione", endUtc = System.currentTimeMillis() + 1_800_000L)
        notifier.showMissed(a)
        val extras = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(a.notificationId + 1).extras
        assertEquals("Riunione", extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals(context.getString(R.string.missed_alarm), extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT).toString())
    }

    @Test
    fun `the alarm notification keeps no timeout and stays ongoing`() {
        // (U352) the alarm is closed only by the user (Dismiss, Snooze) or by its own ringing timeout: unchanged.
        val a = alert("h")
        val n = notifier.alarmNotification(a)
        assertEquals(0L, n.timeoutAfter)
        assertTrue(n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `an alarm that falls back to a popup keeps no timeout`() {
        // (U353) showPopup is also the fallback when the alarm service cannot start: that notification is still an alarm.
        val a = alert("i").copy(endUtc = System.currentTimeMillis() + 3_600_000L)
        notifier.showPopup(a)
        val shown = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(a.notificationId)
        assertEquals(0L, shown.timeoutAfter)
    }

    private val day = 24L * 3_600_000L

    @Test
    fun `the text of a reminder gives the day and the hours, never the time left`() {
        // (U354) issue #59: "in 29 min" is wrong as soon as the notification is seen a while after it appeared.
        val now = 1_800_000_000_000L
        val a = alert("j").copy(beginUtc = now + 3_600_000L, endUtc = now + 2 * 3_600_000L)
        val text = ReminderNotifier.whenText(context, a, now)
        assertTrue(text, !text.contains("min", ignoreCase = true) && !text.contains(" in ") && !text.startsWith("In "))
        assertTrue(text, text.contains(", "))
        assertEquals(ReminderNotifier.dayLabel(context, a.beginUtc, now), text.substringBefore(", "))
    }

    @Test
    fun `the day is today, tomorrow or the full date with weekday and year`() {
        // (U355) a far day shows the weekday and the whole date, e.g. "lunedì 23/11/2026".
        val now = java.time.LocalDate.of(2026, 11, 21).atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val today = ReminderNotifier.dayLabel(context, now + 3_600_000L, now)
        val tomorrow = ReminderNotifier.dayLabel(context, now + day, now)
        val far = ReminderNotifier.dayLabel(context, now + 2 * day, now)
        assertNotEquals(today, tomorrow)
        assertTrue(far, far.contains("2026") && far.contains("23"))
        assertNotEquals(tomorrow, far)
    }

    @Test
    fun `an all-day event says so after the day`() {
        // (U356)
        val now = 1_800_000_000_000L
        val a = alert("k").copy(allDay = true, beginUtc = now + 3_600_000L, endUtc = now + day)
        assertTrue(ReminderNotifier.whenText(context, a, now).endsWith(", " + context.getString(R.string.all_day)))
    }

    @Test
    fun `the notification names the calendar and the account, one per line, and leaves the app name alone`() {
        // (U357) issue #59: in the expanded text, not under the app name (a long calendar name cut "GGCalendar" there).
        val a = alert("l", ReminderMethod.POPUP).copy(calendarName = "Lavoro", accountEmail = "me@example.com", location = "Aula 3", endUtc = System.currentTimeMillis() + 3_600_000L)
        assertEquals(listOf("Lavoro", "me@example.com"), ReminderNotifier.sourceLines(a))
        notifier.showPopup(a)
        val shown = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(a.notificationId)
        val lines = shown.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().split('\n')
        assertEquals(listOf("Aula 3", "Lavoro", "me@example.com"), lines.drop(1))
        assertNull(shown.extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT))
        val alarm = notifier.alarmNotification(a).extras
        assertNull(alarm.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT))
        assertTrue(alarm.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString().endsWith("Lavoro" + '\n' + "me@example.com"))
    }

    @Test
    fun `the source lines have no repeats and are absent when nothing is known`() {
        // (U358) a calendar named after its account says it once; a local calendar has no account; an old saved reminder has neither.
        val a = alert("m")
        assertEquals(listOf("ME@example.com"), ReminderNotifier.sourceLines(a.copy(calendarName = "ME@example.com", accountEmail = "me@example.com")))
        assertEquals(listOf("Locale"), ReminderNotifier.sourceLines(a.copy(calendarName = "Locale", accountEmail = null)))
        assertTrue(ReminderNotifier.sourceLines(a).isEmpty())
        assertEquals(1, ReminderNotifier.bodyLines(a, "Oggi, 10:00").size)
    }

    @Test
    fun `the calendar and the account survive the intent and the journal, and old data without them still loads`() {
        // (U359)
        val a = alert("n").copy(calendarName = "Lavoro", accountEmail = "me@example.com")
        val viaIntent = ReminderAlert.from(a.putInto(android.content.Intent()))!!
        assertEquals(a, viaIntent)
        assertEquals(a, ReminderAlert.fromJson(a.toJson())!!)
        val old = a.toJson().also { it.remove("calendar"); it.remove("account") }
        val loaded = ReminderAlert.fromJson(old)!!
        assertEquals("", loaded.calendarName)
        assertNull(loaded.accountEmail)
    }
}
