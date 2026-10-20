package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags

/** Colors offered to a local calendar (those of Google Calendar's palette). */
internal val LocalCalendarColors = listOf(
    "#039BE5", "#7986CB", "#33B679", "#8E24AA", "#E67C73", "#F6BF26", "#F4511E", "#616161", "#3F51B5", "#0B8043", "#D50000", "#795548",
)

/** The "On this phone" part of the side menu: the local calendars (each with its menu) and the way to make a new one. */
@Composable
internal fun LocalCalendarsSection(
    calendars: List<CalendarInfo>,
    onToggle: (String, Boolean) -> Unit,
    onNew: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.PhoneAndroid, contentDescription = null)
        Text(stringResource(R.string.local_calendars), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
    }
    calendars.forEach { calendar ->
        CalendarRow(calendar, onToggle) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag("local_menu")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.local_calendar_options, calendar.name))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.exposeTestTags()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.local_calendar_edit)) },
                        onClick = { open = false; onEdit(calendar.id) },
                        modifier = Modifier.testTag("local_menu_edit"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.local_calendar_delete)) },
                        onClick = { open = false; onDelete(calendar.id) },
                        modifier = Modifier.testTag("local_menu_delete"),
                    )
                }
            }
        }
    }
    NavigationDrawerItem(
        icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
        label = { Text(stringResource(R.string.local_calendar_new)) },
        selected = false,
        onClick = onNew,
        modifier = Modifier.testTag("drawer_new_local"),
    )
}

/** Name and color of a local calendar, to make or to change ([initial] null = a new one). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalCalendarDialog(initial: CalendarInfo?, onConfirm: (name: String, colorHex: String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(initial?.colorHex ?: LocalCalendarColors.first()) }
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.local_calendar_new else R.string.local_calendar_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text(stringResource(R.string.local_calendar_name)) },
                    singleLine = true,
                    modifier = Modifier.testTag("local_name"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LocalCalendarColors.forEachIndexed { i, hex ->
                        Box(
                            Modifier.size(36.dp).background(parseColor(hex), CircleShape)
                                .then(if (hex.equals(color, ignoreCase = true)) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = hex }
                                .testTag("local_color_$i"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("local_save"), enabled = name.isNotBlank(), onClick = { onConfirm(name.trim(), color) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
internal fun DeleteLocalCalendarDialog(calendar: CalendarInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_calendar_delete_title, calendar.name)) },
        text = { Text(stringResource(R.string.local_calendar_delete_message)) },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("local_delete_confirm"), onClick = onConfirm) { Text(stringResource(R.string.local_calendar_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
