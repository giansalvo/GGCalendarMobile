package io.github.giansalvo.ggcalendar.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.giansalvo.ggcalendar.core.data.ThemeMode
import io.github.giansalvo.ggcalendar.feature.event.WeekSettings
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Settings, in sections: appearance (theme, wallpaper colors), calendar (first day of the week, start of the day, past
 * events) and a way to the notification screen. The choices are made on a copy and apply only with Save; leaving with
 * unsaved choices asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit, onOpenNotifications: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val saved by produceState<SettingsDraft?>(null) { value = viewModel.load() }
    saved?.let { original ->
        var draft by remember { mutableStateOf(original) }
        var confirmDiscard by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val dirty = draft != original
        val leave = { if (dirty) confirmDiscard = true else onClose() }
        val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            draft = draft.copy(birthdays = granted)
        }
        BackHandler(onBack = leave)

        Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.settings_title)) },
                            navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) } },
                            actions = {
                                Button(
                                    enabled = dirty,
                                    onClick = { scope.launch { viewModel.save(draft); onClose() } },
                                    modifier = Modifier.padding(end = 12.dp),
                                ) { Text(stringResource(R.string.settings_save)) }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                        Section(stringResource(R.string.settings_appearance))
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = draft.theme == mode, role = Role.RadioButton) { draft = draft.copy(theme = mode) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = draft.theme == mode, onClick = null)
                                Text(stringResource(modeLabel(mode)), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SwitchRow(stringResource(R.string.settings_dynamic), stringResource(R.string.settings_dynamic_hint), draft.dynamicColor) { draft = draft.copy(dynamicColor = it) }
                        }

                        Section(stringResource(R.string.settings_calendar))
                        WeekStartRow(draft.weekStart) { draft = draft.copy(weekStart = it) }
                        DayStartRow(draft.dayStartHour) { draft = draft.copy(dayStartHour = it) }
                        SwitchRow(stringResource(R.string.settings_dim_past), stringResource(R.string.settings_dim_past_hint), draft.dimPast) { draft = draft.copy(dimPast = it) }
                        SwitchRow(stringResource(R.string.settings_birthdays), stringResource(R.string.settings_birthdays_hint), draft.birthdays) { on ->
                            // The contacts are read only with the permission, asked when the user turns this on.
                            if (on && !viewModel.contactsGranted) askContacts.launch(Manifest.permission.READ_CONTACTS) else draft = draft.copy(birthdays = on)
                        }

                        Section(stringResource(R.string.settings_notifications))
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onOpenNotifications).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Notifications, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(stringResource(R.string.settings_notifications_alarms), style = MaterialTheme.typography.bodyLarge)
                                Text(stringResource(R.string.settings_notifications_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                text = { Text(stringResource(R.string.settings_discard_question)) },
                confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.settings_discard)) } },
                dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.settings_keep_editing)) } },
            )
        }
    }
}

private fun modeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The hour the day and week views open on when they do not show today (0:00 to 12:00). */
@Composable
private fun DayStartRow(hour: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_day_start), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_day_start_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(LocalTime.of(hour, 0).format(format), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..12).forEach { h ->
                DropdownMenuItem(text = { Text(LocalTime.of(h, 0).format(format)) }, onClick = {
                    open = false
                    onChange(h)
                })
            }
        }
    }
}

/** Only Monday, Saturday or Sunday start a week anywhere; the language of the phone may say another day. */
internal fun weekOf(day: DayOfWeek): DayOfWeek = if (day in WEEK_STARTS) day else DayOfWeek.MONDAY

private val WEEK_STARTS = listOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

@Composable
private fun WeekStartRow(selected: DayOfWeek, onChange: (DayOfWeek) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.settings_week_start), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_week_start_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WEEK_STARTS.forEach { day ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = selected == day, role = Role.RadioButton) { onChange(day) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == day, onClick = null)
                Text(day.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() }, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
