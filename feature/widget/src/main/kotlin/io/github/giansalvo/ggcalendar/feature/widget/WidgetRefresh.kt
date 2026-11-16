package io.github.giansalvo.ggcalendar.feature.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.giansalvo.ggcalendar.core.data.ReminderHook
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The data layer reports every change of events or instances (sync, local edit, import, a calendar shown or hidden) to the
 * `ReminderHook`s, to reschedule the reminders: the widgets listen to the same signal.
 */
@Singleton
class WidgetRefreshHook @Inject constructor(@ApplicationContext private val context: Context) : ReminderHook {
    override fun remindersChanged() = WidgetUpdater.request(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {
    @Binds
    @IntoSet
    abstract fun refreshHook(hook: WidgetRefreshHook): ReminderHook
}

/**
 * "Today" changes at midnight and with the clock or the time zone: Android sends these broadcasts to manifest receivers
 * (they are among the few it still delivers), so the widgets need no alarm of their own.
 */
class WidgetTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetUpdater.request(context)
    }
}
