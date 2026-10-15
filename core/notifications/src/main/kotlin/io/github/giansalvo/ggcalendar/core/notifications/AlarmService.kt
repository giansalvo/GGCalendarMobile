package io.github.giansalvo.ggcalendar.core.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Rings an alarm like the system Clock: the ringtone loops as a system alarm (so it also rings in silent mode and in
 * Do Not Disturb) with vibration, until the user closes or snoozes it or the set duration (1 to 10 minutes) passes;
 * then it stops and leaves a "missed alarm" notification. Alarms that arrive together ring one after the other.
 * Direct boot aware: it uses no injection and reads the duration from the [DirectBootStore], so it also rings
 * after a reboot, before the first unlock.
 */
class AlarmService : Service() {
    private val notifier by lazy { ReminderNotifier(this) }

    private val store by lazy { DirectBootStore(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayDeque<ReminderAlert>()
    private var current: ReminderAlert? = null
    private var player: MediaPlayer? = null
    private var timeout: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alert = intent?.let(ReminderAlert::from)
        if (alert == null) {
            if (current == null) stopSelf()
            return START_NOT_STICKY
        }
        active.update { it + alert.key }
        if (current == null) {
            current = alert
            foreground(alert)
            ring()
        } else {
            // The service was started for this alarm and must go to the foreground even though another one is ringing.
            foreground(current!!)
            if (current!!.key != alert.key && queue.none { it.key == alert.key }) {
                queue.add(alert)
                notifier.showQueuedAlarm(alert)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * With "display over other apps" the alarm screen opens by itself, even over another app: then the notification stays
     * quiet (a banner would cover the top of the screen). Without it, or if the screen did not open, the notification is
     * the loud one with the banner, which carries the two answers.
     */
    private fun foreground(alert: ReminderAlert, quiet: Boolean = Settings.canDrawOverlays(this)) {
        // The "system exempted" type exists from Android 14; before, the service has no type.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, alert.notificationId, notifier.alarmNotification(alert, quiet = quiet), type)
    }

    private fun ring() {
        val alert = current ?: return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        // The alarm screen opens by itself, also while the phone is in use (a notification alone would only slide in).
        runCatching {
            startActivity(
                alert.putInto(Intent(this, AlarmActivity::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                    .setData(android.net.Uri.parse("ggcalendar://alarm/" + android.net.Uri.encode(alert.key))),
            )
        }
        // With "display over other apps" the screen should be up in a moment; if Android refused it, the banner takes its place.
        if (Settings.canDrawOverlays(this)) {
            scope.launch {
                delay(1_500)
                if (current?.key == alert.key && !AlarmActivity.visible) foreground(alert, quiet = false)
            }
        }
        // The system ringtone is a media provider content: before the first unlock it does not exist, and the alarm
        // rings with the sound bundled in the app instead.
        player = runCatching { loopingPlayer { it.setDataSource(this, uri) } }.getOrNull()
            ?: runCatching {
                loopingPlayer { player -> resources.openRawResourceFd(R.raw.alarm_fallback).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) } }
            }.getOrNull()
        @Suppress("DEPRECATION")
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
        timeout?.cancel()
        timeout = scope.launch {
            delay(store.alarmMinutes() * 60_000L)
            if (current?.key == alert.key) finishCurrent(missed = true)
        }
    }

    private fun loopingPlayer(source: (MediaPlayer) -> Unit): MediaPlayer {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            source(player)
            player.isLooping = true
            player.prepare()
            player.start()
        } catch (e: Exception) {
            player.release()
            throw e
        }
        return player
    }

    private fun stopRinging() {
        timeout?.cancel()
        player?.run { runCatching { stop() }; release() }
        player = null
        @Suppress("DEPRECATION")
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel()
    }

    /** The current alarm is over (closed, snoozed or timed out): the next one in line starts, or the service ends. */
    private fun finishCurrent(missed: Boolean) {
        val done = current ?: return
        stopRinging()
        active.update { it - done.key }
        if (missed) notifier.showMissed(done)
        val next = queue.removeFirstOrNull()
        current = next
        if (next == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notifier.cancel(done)
            stopSelf()
        } else {
            foreground(next)
            notifier.cancel(done)
            ring()
        }
    }

    private fun stopAlert(key: String) {
        when {
            current?.key == key -> finishCurrent(missed = false)
            else -> {
                queue.removeAll { it.key == key }
                active.update { it - key }
            }
        }
    }

    override fun onDestroy() {
        stopRinging()
        scope.cancel()
        active.value = emptySet()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: AlarmService? = null

        /** Keys of the alarms ringing or waiting their turn: the alarm screen closes when its alarm leaves. */
        val active = MutableStateFlow<Set<String>>(emptySet())

        /** Starts ringing [alert]. False if Android does not allow a foreground service now (the caller shows a notification). */
        fun start(context: Context, alert: ReminderAlert): Boolean = runCatching {
            ContextCompat.startForegroundService(context, alert.putInto(Intent(context, AlarmService::class.java)))
        }.isSuccess

        fun stop(key: String) {
            Handler(Looper.getMainLooper()).post { instance?.stopAlert(key) }
        }
    }
}
