package io.github.giansalvo.ggcalendar.core.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The alarm notification carries a group of its own. Without it Android 16 folds two notifications of the app (a popup left in the
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
    fun `the alarm still asks for the full-screen intent`() {
        val n = notifier.alarmNotification(alert("d"))
        assertNotNull(n.fullScreenIntent)
        assertNull(notifier.alarmNotification(alert("d"), fullScreen = false).fullScreenIntent)
    }
}
