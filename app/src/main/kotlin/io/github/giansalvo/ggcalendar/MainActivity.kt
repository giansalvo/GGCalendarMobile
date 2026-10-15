package io.github.giansalvo.ggcalendar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import io.github.giansalvo.ggcalendar.core.data.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.notifications.Reminders
import io.github.giansalvo.ggcalendar.feature.calendar.CalendarScreen
import io.github.giansalvo.ggcalendar.feature.calendar.EventLink
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import io.github.giansalvo.ggcalendar.ui.theme.GGCalendarTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: AppPreferences

    // The occurrence a tapped reminder notification asks to show.
    private val link = MutableStateFlow<EventLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readLink(intent)
        // Read once before the first frame so that a chosen dark or light theme does not flash the other one.
        val first = runBlocking { prefs.appearance.first() }
        setContent {
            val appearance by prefs.appearance.collectAsState(first)
            val dark = when (appearance.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The status and navigation bars icons follow the chosen theme, not the system one.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose {}
            }
            GGCalendarTheme(darkTheme = dark, dynamicColor = appearance.dynamicColor) {
                val open by link.collectAsState()
                CalendarScreen(link = open, onLinkHandled = { link.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readLink(intent)
    }

    private fun readLink(intent: Intent) {
        val event = intent.getStringExtra(Reminders.EXTRA_OPEN_EVENT) ?: return
        link.value = EventLink(event, intent.getLongExtra(Reminders.EXTRA_OPEN_BEGIN, 0))
    }
}
