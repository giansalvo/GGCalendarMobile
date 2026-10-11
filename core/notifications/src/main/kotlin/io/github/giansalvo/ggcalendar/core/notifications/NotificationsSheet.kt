package io.github.giansalvo.ggcalendar.core.notifications

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(private val prefs: AppPreferences) : ViewModel() {
    val alarmMinutes: StateFlow<Int> = prefs.alarmMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.DEFAULT_ALARM_MINUTES)

    fun setAlarmMinutes(minutes: Int) {
        viewModelScope.launch { prefs.setAlarmMinutes(minutes) }
    }
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Some phones lack the specific screen: the app's own settings are always there.
        startActivity(NotificationChecks(this).appDetails().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** "Notifications and alarms": what Android allows, how to fix it, and how long an alarm rings (1 to 10 minutes). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSheet(onDismiss: () -> Unit, viewModel: NotificationsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val checks = remember { NotificationChecks(context) }
    var status by remember { mutableStateOf(checks.status()) }
    // The user comes back from the system screen: read the permissions again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = checks.status() }
    val saved by viewModel.alarmMinutes.collectAsState()
    var slider by remember(saved) { mutableFloatStateOf(saved.toFloat()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.notifications_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            PermissionRow(stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_help), status.notifications) {
                context.open(checks.notificationSettings())
            }
            PermissionRow(stringResource(R.string.perm_exact), stringResource(R.string.perm_exact_help), status.exactAlarms) {
                context.open(checks.exactAlarmSettings())
            }
            PermissionRow(stringResource(R.string.perm_fullscreen), stringResource(R.string.perm_fullscreen_help), status.fullScreen) {
                context.open(checks.fullScreenSettings())
            }
            PermissionRow(stringResource(R.string.perm_battery), stringResource(R.string.perm_battery_help), status.batteryUnrestricted) {
                context.open(checks.batterySettings())
            }
            Text(stringResource(R.string.samsung_sleeping), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = { context.open(checks.batteryList()) }) { Text(stringResource(R.string.open_battery_settings)) }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.alarm_duration), style = MaterialTheme.typography.titleMedium)
            Text(
                pluralStringResource(R.plurals.minutes, slider.toInt(), slider.toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = slider,
                onValueChange = { slider = it },
                onValueChangeFinished = { viewModel.setAlarmMinutes(slider.toInt()) },
                valueRange = 1f..10f,
                steps = 8,
            )
            Text(stringResource(R.string.alarm_duration_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionRow(title: String, help: String, granted: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) TextButton(onClick = onFix) { Text(stringResource(R.string.fix)) }
    }
}

/** Under an event's reminders (mockup 5): shown when Android blocks them, with a link to fix it. */
@Composable
fun ReminderPermissionWarning(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val checks = remember { NotificationChecks(context) }
    var status by remember { mutableStateOf(checks.status()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = checks.status() }
    if (!status.blocksReminders) return
    Column(modifier) {
        Text(
            stringResource(if (!status.notifications) R.string.warning_notifications_off else R.string.warning_exact_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { context.open(if (!status.notifications) checks.notificationSettings() else checks.exactAlarmSettings()) }) {
            Text(stringResource(R.string.fix))
        }
    }
}
