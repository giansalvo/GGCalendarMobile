package io.github.giansalvo.ggcalendar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.giansalvo.ggcalendar.feature.calendar.CalendarScreen
import io.github.giansalvo.ggcalendar.ui.theme.GGCalendarTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GGCalendarTheme {
                CalendarScreen()
            }
        }
    }
}
