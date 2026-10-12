package io.github.giansalvo.ggcalendar.feature.event

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.giansalvo.ggcalendar.core.data.AccountWithCalendars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Export calendars to an `.ics` file: which calendars (hidden ones too) and which days. The file goes where the user
 * chooses with the system file picker, so no storage permission is needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportDialog(accounts: List<AccountWithCalendars>, onDismiss: () -> Unit, viewModel: ExportViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val year = remember { LocalDate.now().year }
    var chosen by rememberSaveable { mutableStateOf<Set<String>?>(null) }
    var from by rememberSaveable { mutableStateOf(LocalDate.of(year, 1, 1).toString()) }
    var to by rememberSaveable { mutableStateOf(LocalDate.of(year, 12, 31).toString()) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true: the first day, false: the last
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ExportResult?>(null) }

    val all = accounts.flatMap { it.calendars }
    val selected = chosen ?: all.filter { it.isVisible }.map { it.id }.toSet()
    val fromDay = LocalDate.parse(from)
    val toDay = LocalDate.parse(to)
    val valid = selected.isNotEmpty() && !toDay.isBefore(fromDay)

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val result = pending
        pending = null
        if (uri == null || result == null) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(result.content.toByteArray(Charsets.UTF_8)) } }.isSuccess
            }
            val text = if (ok) context.resources.getQuantityString(R.plurals.export_done, result.count, result.count) else context.getString(R.string.export_failed)
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            busy = false
            if (ok) onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.export_calendars), style = MaterialTheme.typography.titleSmall)
                accounts.forEach { account ->
                    Text(account.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    account.calendars.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (c.id in selected) selected - c.id else selected + c.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = c.id in selected, onCheckedChange = { chosen = if (it) selected + c.id else selected - c.id })
                            Text(if (c.isPrimary) account.email else c.name, maxLines = 1)
                        }
                    }
                }
                Text(stringResource(R.string.export_range), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picking = true }, Modifier.weight(1f)) { Text(day(fromDay)) }
                    Text("→")
                    OutlinedButton(onClick = { picking = false }, Modifier.weight(1f)) { Text(day(toDay)) }
                }
                if (toDay.isBefore(fromDay)) Text(stringResource(R.string.export_range_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.export_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                busy = true
                scope.launch {
                    val result = viewModel.ics(selected, fromDay, toDay)
                    if (result.count == 0) {
                        Toast.makeText(context, R.string.export_nothing, Toast.LENGTH_LONG).show()
                        busy = false
                    } else {
                        pending = result
                        save.launch("GGCalendar_${fromDay}_$toDay.ics")
                    }
                }
            }) { Text(stringResource(R.string.export_action)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )

    picking?.let { first ->
        val initial = if (first) fromDay else toDay
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        if (first) from = d.toString() else to = d.toString()
                    }
                    picking = null
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(stringResource(android.R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

private fun day(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
