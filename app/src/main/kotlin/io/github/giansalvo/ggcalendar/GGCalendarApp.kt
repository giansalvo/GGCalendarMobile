package io.github.giansalvo.ggcalendar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.giansalvo.ggcalendar.core.data.CalendarRepository
import io.github.giansalvo.ggcalendar.core.model.Diag
import io.github.giansalvo.ggcalendar.core.notifications.ReminderStartup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class GGCalendarApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    // Lazy: before the first unlock (direct boot) nothing that opens the normal storage may be created.
    @Inject lateinit var repository: dagger.Lazy<CalendarRepository>

    @Inject lateinit var reminders: dagger.Lazy<ReminderStartup>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        if (BuildConfig.DIAG) {
            Diag.sink = { android.util.Log.d("GGCalDiag", "[${Thread.currentThread().name}] $it") }
            Diag.log { "process start pid=${android.os.Process.myPid()} version=${BuildConfig.VERSION_NAME}" }
        }
        super.onCreate()
        // A direct boot aware component also starts the app before the first unlock, when the normal storage (Room,
        // WorkManager) does not exist yet: the setup waits for the unlock.
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            start()
        } else {
            ContextCompat.registerReceiver(
                this,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        unregisterReceiver(this)
                        start()
                    }
                },
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun start() {
        repository.get().schedulePeriodicSync()
        reminders.get().start()
        // First start of the day or after a change of time zone while the app was closed.
        scope.launch {
            // The database is opened (and, after an update from 1.0.x, made again) before anything reads the window of instances.
            repository.get().acknowledgeSchemaReset(this@GGCalendarApp)
            repository.get().refreshInstances()
        }
    }
}
