package io.github.giansalvo.ggcalendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.giansalvo.ggcalendar.core.data.CalendarRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The phone changed time zone: the day of each instance depends on it, so the instances are rebuilt. */
@AndroidEntryPoint
class TimeZoneReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: CalendarRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                repository.refreshInstances()
            } finally {
                pending.finish()
            }
        }
    }
}
