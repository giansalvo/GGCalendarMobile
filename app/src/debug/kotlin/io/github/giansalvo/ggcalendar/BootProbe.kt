package io.github.giansalvo.ggcalendar

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Experiment on direct boot, in debug builds only. It answers three questions without touching the real reminders:
 * does LOCKED_BOOT_COMPLETED reach the app before the PIN, does BOOT_COMPLETED reach it after, and can an alarm
 * registered at LOCKED_BOOT_COMPLETED ring before the PIN. Everything is logged to a file in device protected
 * storage (the only storage that exists before the first unlock).
 */
internal object BootProbe {
    const val ALARM_DELAY_MS = 90_000L

    fun log(context: Context, text: String) {
        val de = context.createDeviceProtectedStorageContext()
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val sinceBoot = SystemClock.elapsedRealtime() / 1000
        File(de.filesDir, "boot_probe.log").appendText("$time up=${sinceBoot}s unlocked=$unlocked $text\n")
    }
}

class BootProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BootProbe.log(context, "received ${intent.action}")
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        // An alarm armed before the first unlock, as the real direct boot solution would do.
        val at = System.currentTimeMillis() + BootProbe.ALARM_DELAY_MS
        val fire = PendingIntent.getBroadcast(
            context, 0, Intent(context, BootProbeAlarmReceiver::class.java).setAction("probe.fire"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, fire), fire)
        BootProbe.log(context, "armed probe alarm for ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))}")
    }
}

class BootProbeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        BootProbe.log(context, "PROBE ALARM FIRED")
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("probe", "Boot probe", NotificationManager.IMPORTANCE_HIGH))
        manager.notify(
            4242,
            NotificationCompat.Builder(context, "probe").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Prova direct boot").setContentText("Sveglia suonata prima dello sblocco")
                .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).build(),
        )
        // A few seconds of the alarm ringtone as a system alarm, to hear it before the PIN.
        val pending = goAsync()
        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                prepare()
                start()
            }
        }.onFailure { BootProbe.log(context, "ringtone failed: $it") }.getOrNull()
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { player?.stop() }
            player?.release()
            BootProbe.log(context, "ringtone stopped (played=${player != null})")
            pending.finish()
        }, 8_000)
    }
}
