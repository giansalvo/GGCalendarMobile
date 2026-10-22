package io.github.giansalvo.ggcalendar.feature.settings

import io.github.giansalvo.ggcalendar.core.data.SecondZones
import io.github.giansalvo.ggcalendar.feature.event.ZonePickerDialog
import io.github.giansalvo.ggcalendar.feature.event.zoneLabel
import androidx.compose.material3.OutlinedTextField
import java.time.ZoneId
import io.github.giansalvo.ggcalendar.core.notifications.NotificationSettingsSection
import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
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
 * events) and the notifications and alarms (permissions, how long an alarm rings). The choices are made on a copy and apply only with Save; leaving with
 * unsaved choices asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
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
            Surface(Modifier.exposeTestTags().fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.settings_title)) },
                            navigationIcon = { IconButton(onClick = leave, modifier = Modifier.testTag("settings_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) } },
                            actions = {
                                Button(
                                    enabled = dirty,
                                    onClick = { scope.launch { viewModel.save(draft); onClose() } },
                                    modifier = Modifier.testTag("settings_save").padding(end = 12.dp),
                                ) { Text(stringResource(R.string.settings_save)) }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                        Section(stringResource(R.string.settings_appearance))
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                Modifier.testTag("settings_theme_" + mode.name).fillMaxWidth().selectable(selected = draft.theme == mode, role = Role.RadioButton) { draft = draft.copy(theme = mode) }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = draft.theme == mode, onClick = null)
                                Text(stringResource(modeLabel(mode)), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            SwitchRow(stringResource(R.string.settings_dynamic), stringResource(R.string.settings_dynamic_hint), draft.dynamicColor, tag = "settings_dynamic") { draft = draft.copy(dynamicColor = it) }
                        }

                        // Android 13 and later keep the language of each app on their own screen: it is opened from here.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LanguageRow()

                        Section(stringResource(R.string.settings_calendar))
                        WeekStartRow(draft.weekStart) { draft = draft.copy(weekStart = it) }
                        DayStartRow(draft.dayStartHour) { draft = draft.copy(dayStartHour = it) }
                        SwitchRow(stringResource(R.string.settings_dim_past), stringResource(R.string.settings_dim_past_hint), draft.dimPast, tag = "settings_dim_past") { draft = draft.copy(dimPast = it) }
                        TwoChoiceRow(
                            Icons.Outlined.WorkOutline, stringResource(R.string.settings_status), stringResource(R.string.settings_status_hint),
                            stringResource(R.string.settings_status_busy), stringResource(R.string.settings_status_free), draft.defaultBusy, "settings_status",
                        ) { draft = draft.copy(defaultBusy = it) }
                        TwoChoiceRow(
                            Icons.Outlined.Lock, stringResource(R.string.settings_visibility), stringResource(R.string.settings_visibility_hint),
                            stringResource(R.string.settings_visibility_public), stringResource(R.string.settings_visibility_private), !draft.defaultPrivate, "settings_visibility",
                        ) { draft = draft.copy(defaultPrivate = !it) }
                        DefaultReminderRow(draft.defaultReminderMinutes) { draft = draft.copy(defaultReminderMinutes = it) }
                        SecondZoneRows(draft) { draft = it }
                        SwitchRow(stringResource(R.string.settings_birthdays), stringResource(R.string.settings_birthdays_hint), draft.birthdays, tag = "settings_birthdays") { on ->
                            // The contacts are read only with the permission, asked when the user turns this on.
                            if (on && !viewModel.contactsGranted) askContacts.launch(Manifest.permission.READ_CONTACTS) else draft = draft.copy(birthdays = on)
                        }

                        SubscriptionRefreshRow(draft.subscriptionMinutes) { draft = draft.copy(subscriptionMinutes = it) }

                        Section(stringResource(R.string.settings_notifications))
                        NotificationSettingsSection(draft.alarmMinutes, { draft = draft.copy(alarmMinutes = it) }, draft.alarmSound) { draft = draft.copy(alarmSound = it) }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }

        if (confirmDiscard) {
            AlertDialog(
                modifier = Modifier.exposeTestTags(),
                onDismissRequest = { confirmDiscard = false },
                text = { Text(stringResource(R.string.settings_discard_question)) },
                confirmButton = { TextButton(modifier = Modifier.testTag("discard_confirm"), onClick = { confirmDiscard = false; onClose() }) { Text(stringResource(R.string.settings_discard)) } },
                dismissButton = { TextButton(modifier = Modifier.testTag("discard_keep"), onClick = { confirmDiscard = false }) { Text(stringResource(R.string.settings_keep_editing)) } },
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
private fun SwitchRow(title: String, hint: String, checked: Boolean, tag: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.testTag(tag).fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 12.dp))
    }
}

/** The language of the app: shown as it is now, changed on Android's own screen for this app (Android 13 and later). */
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val name = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    Row(
        Modifier.testTag("settings_language").fillMaxWidth().clickable {
            runCatching { context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:" + context.packageName))) }
        }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_language_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** The hour the day and week views open on when they do not show today (0:00 to 12:00). */
@Composable
private fun DayStartRow(hour: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val format = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    Column {
        Row(Modifier.testTag("settings_day_start").fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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

/** How long the app waits before it downloads a subscribed calendar again (5 minutes at least). */
@Composable
private fun SubscriptionRefreshRow(minutes: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choices = (AppPreferences.SUBSCRIPTION_CHOICES + minutes).distinct().sorted()
    @Composable
    fun label(m: Int) = if (m % 60 == 0) stringResource(R.string.settings_subscriptions_hours, m / 60) else stringResource(R.string.settings_subscriptions_minutes, m)
    Column {
        Row(Modifier.testTag("settings_subscription_refresh").fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_subscriptions_refresh), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_subscriptions_refresh_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(label(minutes), Modifier.testTag("settings_subscription_refresh_value"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { m ->
                DropdownMenuItem(text = { Text(label(m)) }, modifier = Modifier.testTag("settings_subscription_refresh_$m"), onClick = {
                    open = false
                    onChange(m)
                })
            }
        }
    }
}

/** The reminder a new event starts with, in every calendar (local and Google); events and calendars that already exist do not change. */
@Composable
private fun DefaultReminderRow(minutes: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    @Composable
    fun label(m: Int) = when {
        m < 0 -> stringResource(R.string.settings_default_reminder_none)
        m == 1440 -> stringResource(R.string.settings_default_reminder_day)
        m % 60 == 0 -> stringResource(R.string.settings_subscriptions_hours, m / 60)
        else -> stringResource(R.string.settings_subscriptions_minutes, m)
    }
    Column {
        Row(Modifier.testTag("settings_default_reminder").fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_default_reminder), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_default_reminder_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(label(minutes), Modifier.testTag("settings_default_reminder_value"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AppPreferences.REMINDER_CHOICES.forEach { m ->
                DropdownMenuItem(text = { Text(label(m)) }, modifier = Modifier.testTag("settings_default_reminder_$m"), onClick = {
                    open = false
                    onChange(m)
                })
            }
        }
    }
}

/**
 * The second column of hours of the time grids: a switch, the zone to show and a short name for the column (6 characters at most;
 * empty, it takes the name of the zone).
 */
@Composable
private fun SecondZoneRows(draft: SettingsDraft, onChange: (SettingsDraft) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val locale = Locale.getDefault()
    SwitchRow(stringResource(R.string.settings_second_zone), stringResource(R.string.settings_second_zone_hint), draft.secondZoneOn, tag = "settings_second_zone") { on ->
        // The first time it is turned on, it starts from a zone that is not the one of the phone.
        val id = draft.secondZoneId ?: if (ZoneId.systemDefault().id == "America/New_York") "Europe/London" else "America/New_York"
        onChange(draft.copy(secondZoneOn = on, secondZoneId = id))
    }
    val id = draft.secondZoneId
    if (draft.secondZoneOn && id != null) {
        val zone = runCatching { ZoneId.of(id) }.getOrNull()
        Row(Modifier.testTag("settings_second_zone_pick").fillMaxWidth().clickable { picking = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_second_zone_pick), style = MaterialTheme.typography.bodyLarge)
                Text(zone?.let { zoneLabel(it, locale) } ?: id, Modifier.testTag("settings_second_zone_value"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val default = SecondZones.defaultLabel(id)
        OutlinedTextField(
            value = draft.secondZoneLabel,
            onValueChange = { onChange(draft.copy(secondZoneLabel = limitLabel(it))) },
            label = { Text(stringResource(R.string.settings_second_zone_label)) },
            placeholder = { Text(default) },
            supportingText = { Text(stringResource(R.string.settings_second_zone_label_hint, SecondZones.MAX_LABEL, default)) },
            singleLine = true,
            modifier = Modifier.testTag("settings_second_zone_label").fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    if (picking) {
        ZonePickerDialog(draft.secondZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC"), { picking = false }) { z ->
            // A name left empty follows the zone; a name typed stays.
            onChange(draft.copy(secondZoneId = z.id))
        }
    }
}

/** The text of the name field, cut to the longest name the column has room for. */
internal fun limitLabel(text: String): String {
    val end = text.offsetByCodePoints(0, minOf(SecondZones.MAX_LABEL, text.codePointCount(0, text.length)))
    return text.substring(0, end)
}

/** Only Monday, Saturday or Sunday start a week anywhere; the language of the phone may say another day. */
internal fun weekOf(day: DayOfWeek): DayOfWeek = if (day in WEEK_STARTS) day else DayOfWeek.MONDAY

private val WEEK_STARTS = listOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** A setting with an icon and two exclusive choices side by side; [first] says the first one is on. */
@Composable
private fun TwoChoiceRow(icon: ImageVector, title: String, hint: String, firstLabel: String, secondLabel: String, first: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, Modifier.padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                listOf(true to firstLabel, false to secondLabel).forEach { (isFirst, label) ->
                    Row(
                        Modifier.testTag(tag + (if (isFirst) "_first" else "_second")).selectable(selected = first == isFirst, role = Role.RadioButton) { onChange(isFirst) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = first == isFirst, onClick = null)
                        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekStartRow(selected: DayOfWeek, onChange: (DayOfWeek) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.settings_week_start), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_week_start_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        WEEK_STARTS.forEach { day ->
            Row(
                Modifier.testTag("settings_week_" + day.name).fillMaxWidth().selectable(selected = selected == day, role = Role.RadioButton) { onChange(day) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == day, onClick = null)
                Text(day.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() }, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
