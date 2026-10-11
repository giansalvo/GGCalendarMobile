package io.github.giansalvo.ggcalendar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.notifications.Reminders
import io.github.giansalvo.ggcalendar.feature.calendar.CalendarScreen
import io.github.giansalvo.ggcalendar.feature.calendar.EventLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.giansalvo.ggcalendar.ui.theme.GGCalendarTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: AppPreferences

    // The occurrence a tapped reminder notification asks to show.
    private val link = MutableStateFlow<EventLink?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readLink(intent)
        askForNotifications()
        setContent {
            GGCalendarTheme {
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

    /** Android 13 and later: reminders need the notification permission, asked once at the first start. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        lifecycleScope.launch {
            if (prefs.askedNotifications()) return@launch
            prefs.setAskedNotifications()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
