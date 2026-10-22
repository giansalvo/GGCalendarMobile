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

    // A calendar file another app opened or shared with us: the import shows its summary and writes nothing until the user says so.
    private val importUri = MutableStateFlow<android.net.Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Diag.log { "activity onCreate id=${System.identityHashCode(this)} savedState=${savedInstanceState != null} action=${intent.action}" }
        enableEdgeToEdge()
        // The test runs start the app with `--ez skip_intro true`: the first-start splash and questions count as seen, so that
        // a fresh install goes straight to the calendar. A normal start never has the extra.
        if (intent.getBooleanExtra(EXTRA_SKIP_INTRO, false)) lifecycleScope.launch { prefs.setPermissionsIntroSeen() }
        readLink(intent)
        readImport(intent)
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
                val file by importUri.collectAsState()
                CalendarScreen(link = open, onLinkHandled = { link.value = null }, importUri = file, onImportHandled = { importUri.value = null })
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
        readImport(intent)
    }

    private companion object {
        const val EXTRA_SKIP_INTRO = "skip_intro"
    }

    /** `VIEW` (open with) and `SEND` (share) of a calendar file: only a content Uri, read once by the import. */
    private fun readImport(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        if (uri.scheme != "content") return
        importUri.value = uri
        // The same intent must not import again when the activity is recreated.
        intent.data = null
        intent.action = Intent.ACTION_MAIN
    }

    private fun readLink(intent: Intent) {
        val event = intent.getStringExtra(Reminders.EXTRA_OPEN_EVENT) ?: return
        link.value = EventLink(event, intent.getLongExtra(Reminders.EXTRA_OPEN_BEGIN, 0))
    }
}
