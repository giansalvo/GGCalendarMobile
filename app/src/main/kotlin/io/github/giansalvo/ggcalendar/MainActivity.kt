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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.model.Diag
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
        Diag.log { "activity onCreate id=${System.identityHashCode(this)} savedState=${savedInstanceState != null} action=${intent.action}" }
        enableEdgeToEdge()
        // The test runs start the app with `--ez skip_intro true`: the first-start splash and questions count as seen, so that
        // a fresh install goes straight to the calendar. A normal start never has the extra.
        if (intent.getBooleanExtra(EXTRA_SKIP_INTRO, false)) lifecycleScope.launch { prefs.setPermissionsIntroSeen() }
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

    override fun onStart() {
        super.onStart()
        Diag.log { "activity onStart id=${System.identityHashCode(this)}" }
    }

    override fun onResume() {
        super.onResume()
        Diag.log { "activity onResume id=${System.identityHashCode(this)}" }
    }

    override fun onPause() {
        Diag.log { "activity onPause id=${System.identityHashCode(this)}" }
        super.onPause()
    }

    override fun onStop() {
        Diag.log { "activity onStop id=${System.identityHashCode(this)}" }
        super.onStop()
    }

    override fun onDestroy() {
        Diag.log { "activity onDestroy id=${System.identityHashCode(this)} finishing=$isFinishing config=$isChangingConfigurations" }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Diag.log { "activity focus=$hasFocus" }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Diag.log { "activity onNewIntent action=${intent.action}" }
        setIntent(intent)
        readLink(intent)
    }

    private companion object {
        const val EXTRA_SKIP_INTRO = "skip_intro"
    }

    private fun readLink(intent: Intent) {
        val event = intent.getStringExtra(Reminders.EXTRA_OPEN_EVENT) ?: return
        link.value = EventLink(event, intent.getLongExtra(Reminders.EXTRA_OPEN_BEGIN, 0))
    }
}
