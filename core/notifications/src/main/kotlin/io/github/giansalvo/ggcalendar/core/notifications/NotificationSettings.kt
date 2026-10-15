package io.github.giansalvo.ggcalendar.core.notifications

import androidx.compose.ui.platform.testTag
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.LifecycleEventEffect

internal fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Some phones lack the specific screen: the app's own settings are always there.
        startActivity(NotificationChecks(this).appDetails().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The "Notifications and alarms" block of the settings screen: what Android allows and how to fix it, and how long an
 * alarm rings (1 to 10 minutes). The permissions are Android's own screens; the duration is part of the screen's draft.
 */
@Composable
fun NotificationSettingsSection(alarmMinutes: Int, onAlarmMinutesChange: (Int) -> Unit) {
    val context = LocalContext.current
    val checks = remember { NotificationChecks(context) }
    var status by remember { mutableStateOf(checks.status()) }
    // The user comes back from the system screen: read the permissions again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = checks.status() }
    var slider by remember(alarmMinutes) { mutableFloatStateOf(alarmMinutes.toFloat()) }

    Column(Modifier.exposeTestTags().testTag("settings_notification_options").fillMaxWidth().padding(horizontal = 16.dp)) {
        PermissionRow(stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_help), status.notifications) {
            context.open(checks.notificationSettings())
        }
        PermissionRow(stringResource(R.string.perm_exact), stringResource(R.string.perm_exact_help), status.exactAlarms) {
            context.open(checks.exactAlarmSettings())
        }
        PermissionRow(stringResource(R.string.perm_fullscreen), stringResource(R.string.perm_fullscreen_help), status.fullScreen) {
            context.open(checks.fullScreenSettings())
        }
        PermissionRow(stringResource(R.string.perm_overlay), stringResource(R.string.perm_overlay_help), status.overlay) {
            context.open(checks.overlaySettings())
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
            modifier = Modifier.testTag("notif_alarm_slider"),
            value = slider,
            onValueChange = { slider = it },
            onValueChangeFinished = { onAlarmMinutesChange(slider.toInt()) },
            valueRange = 1f..10f,
            steps = 8,
        )
        Text(stringResource(R.string.alarm_duration_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PermissionRow(title: String, help: String, granted: Boolean, onFix: () -> Unit) {
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
