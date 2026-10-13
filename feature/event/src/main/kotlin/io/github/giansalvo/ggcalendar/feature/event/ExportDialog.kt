package io.github.giansalvo.ggcalendar.feature.event

import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.ui.state.ToggleableState
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
import androidx.compose.ui.text.style.TextAlign
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
    var csv by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ExportResult?>(null) }
    // What the export did, shown when it ends: events written and events left out, with the reason.
    var summary by remember { mutableStateOf<String?>(null) }
    fun summaryOf(r: ExportResult, saved: Boolean): String = buildString {
        append(if (!saved) context.getString(R.string.export_failed) else context.resources.getQuantityString(if (r.occurrences) R.plurals.export_rows_done else R.plurals.export_done, r.count, r.count))
        if (r.skippedPrivate > 0) append("\n").append(context.resources.getQuantityString(R.plurals.export_skipped_private, r.skippedPrivate, r.skippedPrivate))
    }

    val all = accounts.flatMap { it.calendars }
    val selected = chosen ?: all.filter { it.isVisible }.map { it.id }.toSet()
    val fromDay = LocalDate.parse(from)
    val toDay = LocalDate.parse(to)
    val valid = selected.isNotEmpty() && !toDay.isBefore(fromDay)

    val onSaved: (android.net.Uri?) -> Unit = { uri ->
        val result = pending
        pending = null
        if (uri == null || result == null) {
            busy = false
        } else {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(result.content.toByteArray(Charsets.UTF_8)) } }.isSuccess
                }
                summary = summaryOf(result, ok)
                busy = false
            }
        }
    }
    val saveIcs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar"), onSaved)
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), onSaved)

    summary?.let { text ->
        AlertDialog(
            modifier = Modifier.exposeTestTags(),
            onDismissRequest = { summary = null; onDismiss() },
            title = { Text(stringResource(R.string.export_title)) },
            text = { Text(text) },
            confirmButton = { TextButton(modifier = Modifier.testTag("export_summary_ok"), onClick = { summary = null; onDismiss() }) { Text(stringResource(android.R.string.ok)) } },
        )
    }

    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            // The days stay in view at the top; only the list of calendars scrolls when there are many.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !csv, onClick = { csv = false }, modifier = Modifier.testTag("export_ics").weight(1f).fillMaxHeight(), label = { Text(stringResource(R.string.export_format_ics), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 2) })
                    FilterChip(selected = csv, onClick = { csv = true }, modifier = Modifier.testTag("export_csv").weight(1f).fillMaxHeight(), label = { Text(stringResource(R.string.export_format_csv), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 2) })
                }
                Text(stringResource(R.string.export_range), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picking = true }, Modifier.weight(1f)) { Text(day(fromDay)) }
                    Text("→")
                    OutlinedButton(onClick = { picking = false }, Modifier.weight(1f)) { Text(day(toDay)) }
                }
                if (toDay.isBefore(fromDay)) Text(stringResource(R.string.export_range_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Column(Modifier.padding(top = 12.dp).heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.export_calendars), style = MaterialTheme.typography.titleSmall)
                accounts.forEach { account ->
                    // The master checkbox of the account: all of its calendars or none; a dash when only some are chosen.
                    val ids = account.calendars.map { it.id }
                    val picked = ids.count { it in selected }
                    val all = picked == ids.size && ids.isNotEmpty()
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp).clickable { chosen = if (all) selected - ids.toSet() else selected + ids },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(
                            state = when {
                                all -> ToggleableState.On
                                picked == 0 -> ToggleableState.Off
                                else -> ToggleableState.Indeterminate
                            },
                            onClick = { chosen = if (all) selected - ids.toSet() else selected + ids },
                        )
                        Text(account.email, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    }
                    account.calendars.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (c.id in selected) selected - c.id else selected + c.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(24.dp))
                            Checkbox(checked = c.id in selected, onCheckedChange = { chosen = if (it) selected + c.id else selected - c.id })
                            Text(if (c.isPrimary) account.email else c.name, maxLines = 1)
                        }
                    }
                }
                }
                Text(stringResource(R.string.export_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("export_confirm"), enabled = valid && !busy, onClick = {
                busy = true
                scope.launch {
                    val result = if (csv) viewModel.csv(selected, fromDay, toDay, context.resources) else viewModel.ics(selected, fromDay, toDay)
                    if (result.count == 0) {
                        summary = context.getString(R.string.export_nothing) + (if (result.skippedPrivate > 0) "\n" + context.resources.getQuantityString(R.plurals.export_skipped_private, result.skippedPrivate, result.skippedPrivate) else "")
                        busy = false
                    } else {
                        pending = result
                        if (csv) saveCsv.launch("GGCalendar_${fromDay}_$toDay.csv") else saveIcs.launch("GGCalendar_${fromDay}_$toDay.ics")
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
