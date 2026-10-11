package io.github.giansalvo.ggcalendar.core.notifications

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Rings an alarm like the system Clock: the ringtone loops as a system alarm (so it also rings in silent mode and in
 * Do Not Disturb) with vibration, until the user closes or snoozes it or the set duration (1 to 10 minutes) passes;
 * then it stops and leaves a "missed alarm" notification. Alarms that arrive together ring one after the other.
 */
@AndroidEntryPoint
class AlarmService : Service() {
    @Inject lateinit var notifier: ReminderNotifier

    @Inject lateinit var prefs: AppPreferences

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

    private fun foreground(alert: ReminderAlert) {
        ServiceCompat.startForeground(
            this, alert.notificationId, notifier.alarmNotification(alert), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
    }

    private fun ring() {
        val alert = current ?: return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        @Suppress("DEPRECATION")
        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
        timeout?.cancel()
        timeout = scope.launch {
            delay(prefs.alarmMinutes.first() * 60_000L)
            if (current?.key == alert.key) finishCurrent(missed = true)
        }
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
