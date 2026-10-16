package io.github.giansalvo.ggcalendar.core.notifications

import androidx.compose.ui.platform.testTag
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.core.content.IntentCompat
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
fun NotificationSettingsSection(alarmMinutes: Int, onAlarmMinutesChange: (Int) -> Unit, alarmSound: String? = null, onAlarmSoundChange: (String?) -> Unit = {}) {
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
        // From Android 13 calendars hold USE_EXACT_ALARM from the install and Android does not let it be taken away: the row
        // is only for Android 12 and earlier, where the user decides, or for a phone that denies it all the same.
        if (!status.exactAlarms || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(stringResource(R.string.perm_exact), stringResource(R.string.perm_exact_help), status.exactAlarms) {
                context.open(checks.exactAlarmSettings())
            }
        }
        PermissionRow(stringResource(R.string.perm_fullscreen), stringResource(R.string.perm_fullscreen_help), status.fullScreen) {
            context.open(checks.fullScreenSettings())
        }
        PermissionRow(stringResource(R.string.perm_overlay), stringResource(R.string.perm_overlay_help), status.overlay) {
            context.open(checks.overlaySettings())
        }
        PermissionRow(stringResource(R.string.perm_battery), stringResource(R.string.perm_battery_help), status.batteryUnrestricted) {
            // Already allowed: the choice (unrestricted, optimized, limited) is on the app's battery page, one tap from the app info
            context.open(if (status.batteryUnrestricted) checks.appDetails() else checks.batterySettings())
        }
        Text(stringResource(R.string.samsung_sleeping), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = { context.open(checks.batteryList()) }) { Text(stringResource(R.string.open_battery_settings)) }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        AlarmSoundRow(alarmSound, onAlarmSoundChange)
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

/**
 * The alarm sound: the name of the one in use (or "System default"); a tap opens Android's own chooser of alarm sounds, which
 * also plays a preview. Choosing the phone's default stores nothing: the alarm then follows the phone's setting.
 */
@Composable
private fun AlarmSoundRow(alarmSound: String?, onChange: (String?) -> Unit) {
    val context = LocalContext.current
    val default = remember { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val picked = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        onChange(picked?.takeIf { it != default }?.toString())
    }
    val systemDefault = stringResource(R.string.alarm_sound_default)
    val name = remember(alarmSound, systemDefault) {
        alarmSound?.let { runCatching { RingtoneManager.getRingtone(context, Uri.parse(it)).getTitle(context) }.getOrNull() } ?: systemDefault
    }
    val title = stringResource(R.string.alarm_sound)
    Row(
        Modifier.testTag("settings_alarm_sound").fillMaxWidth().clickable {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, default)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, alarmSound?.let(Uri::parse) ?: default)
            runCatching { picker.launch(intent) }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.alarm_sound_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Text(name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    // The chooser of some phones lists the default only when it is the current choice: going back to it is a button of ours.
    if (alarmSound != null) {
        TextButton(modifier = Modifier.testTag("alarm_sound_reset"), onClick = { onChange(null) }) { Text(stringResource(R.string.alarm_sound_reset)) }
    }
}

@Composable
internal fun PermissionRow(title: String, help: String, granted: Boolean, onFix: () -> Unit) {
    // Granted or not, the row opens Android's own screen for that permission: that is also where it is taken away.
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onFix).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onFix) { Text(stringResource(if (granted) R.string.perm_change else R.string.fix)) }
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
