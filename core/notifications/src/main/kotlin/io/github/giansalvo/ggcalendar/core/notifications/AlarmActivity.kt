package io.github.giansalvo.ggcalendar.core.notifications

import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

/** The alarm screen over the lock screen (mockup 7): time, title, and the Dismiss and Snooze buttons. */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {
    @Inject lateinit var engine: ReminderEngine

    @Inject lateinit var prefs: AppPreferences

    private val alert = MutableStateFlow<ReminderAlert?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = ReminderAlert.from(intent)
        if (first == null) {
            finish()
            return
        }
        alert.value = first
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        // The screen closes by itself when its alarm is closed from the notification or has timed out.
        lifecycleScope.launch {
            var seen = false
            AlarmService.active.collect { keys ->
                val key = alert.value?.key
                if (key != null && key in keys) seen = true else if (seen) finish()
            }
        }
        setContent {
            val current by alert.collectAsState()
            val minutes by prefs.alarmMinutes.collectAsState(AppPreferences.DEFAULT_ALARM_MINUTES)
            MaterialTheme(colorScheme = darkColorScheme()) {
                current?.let { a ->
                    AlarmScreen(
                        alert = a,
                        durationMinutes = minutes,
                        onDismiss = {
                            engine.dismiss(a)
                            finish()
                        },
                        onSnooze = { m ->
                            lifecycleScope.launch {
                                engine.snooze(a, m)
                                finish()
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        ReminderAlert.from(intent)?.let { alert.value = it }
    }
}

@Composable
private fun AlarmScreen(alert: ReminderAlert, durationMinutes: Int, onDismiss: () -> Unit, onSnooze: (Int) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (alert.allDay) stringResource(R.string.all_day) else DateFormat.getTimeFormat(context).format(Date(alert.beginUtc)),
            fontSize = 64.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            alert.title.ifBlank { stringResource(R.string.alarm_title) },
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(ReminderNotifier.relative(context, alert.beginUtc, System.currentTimeMillis()), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (alert.location.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(alert.location, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(48.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSnooze(AppPreferences.DEFAULT_SNOOZE_MINUTES) }, Modifier.weight(1f)) {
                Text(stringResource(R.string.snooze_minutes, AppPreferences.DEFAULT_SNOOZE_MINUTES))
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.ArrowDropDown, stringResource(R.string.snooze))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Reminders.SNOOZE_CHOICES.forEach { m ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.snooze_minutes, m)) }, onClick = {
                            menu = false
                            onSnooze(m)
                        })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDismiss, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.dismiss)) }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.alarm_stops_in, durationMinutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
