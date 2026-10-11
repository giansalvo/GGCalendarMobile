package io.github.giansalvo.ggcalendar.core.notifications

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Date

/**
 * The alarm screen over the lock screen (mockup 7): time, title, description, the choice of the snooze, Stop.
 * Direct boot aware: no injection, everything it shows comes from the alarm and the [DirectBootStore].
 */
class AlarmActivity : ComponentActivity() {
    private val alert = MutableStateFlow<ReminderAlert?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val first = ReminderAlert.from(intent)
        if (first == null) {
            finish()
            return
        }
        alert.value = first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
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
            val minutes = remember { DirectBootStore(applicationContext).alarmMinutes() }
            MaterialTheme(colorScheme = darkColorScheme()) {
                current?.let { a ->
                    AlarmScreen(
                        alert = a,
                        durationMinutes = minutes,
                        onStop = {
                            ReminderActions.dismiss(applicationContext, a)
                            finish()
                        },
                        onSnooze = { m ->
                            lifecycleScope.launch {
                                ReminderActions.snooze(applicationContext, a, m)
                                finish()
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ReminderAlert.from(intent)?.let { alert.value = it }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmScreen(alert: ReminderAlert, durationMinutes: Int, onStop: () -> Unit, onSnooze: (Int) -> Unit) {
    val context = LocalContext.current
    var snooze by remember { mutableIntStateOf(AppPreferences.DEFAULT_SNOOZE_MINUTES) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (alert.allDay) stringResource(R.string.all_day) else DateFormat.getTimeFormat(context).format(Date(alert.beginUtc)),
            fontSize = 88.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            alert.title.ifBlank { stringResource(R.string.alarm_title) },
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            ReminderNotifier.relative(context, alert.beginUtc, System.currentTimeMillis()),
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
        )
        if (alert.location.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(alert.location, fontSize = 20.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (alert.description.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                alert.description.replace(Regex("<[^>]*>"), " ").trim(),
                fontSize = 18.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            )
        }
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.snooze_for), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Reminders.SNOOZE_CHOICES.forEach { m ->
                FilterChip(
                    selected = m == snooze,
                    onClick = { snooze = m },
                    label = { Text(stringResource(R.string.snooze_choice, m), fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp)) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { onSnooze(snooze) }, Modifier.fillMaxWidth().height(64.dp)) {
            Text(stringResource(R.string.snooze_minutes, snooze), fontSize = 22.sp)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStop, Modifier.fillMaxWidth().height(72.dp)) { Text(stringResource(R.string.stop_alarm), fontSize = 24.sp) }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.alarm_help), fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(pluralStringResource(R.plurals.alarm_stops_in, durationMinutes, durationMinutes), fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
