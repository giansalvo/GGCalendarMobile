package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import io.github.giansalvo.ggcalendar.core.data.DuplicateAction
import io.github.giansalvo.ggcalendar.core.data.ImportProblem
import io.github.giansalvo.ggcalendar.core.data.ReminderChoice
import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The window that imports a file or an address into a local calendar. It opens on the choice of the source (from the menu) or
 * straight on the summary (a file sent by another app); nothing is written until "Import" is touched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportScreen(viewModel: ImportViewModel, onShow: (java.time.LocalDate?) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.stage == ImportStage.CLOSED) return
    val importing = state.stage == ImportStage.IMPORTING
    val close = { if (importing) viewModel.stopImport() else viewModel.close() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.readFile(uri) }
    BackHandler(onBack = close)

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.exposeTestTags().fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.import_title), Modifier.testTag("import_title")) },
                        navigationIcon = { IconButton(onClick = close, modifier = Modifier.testTag("import_close")) { Icon(Icons.Filled.Close, stringResource(R.string.import_close)) } },
                    )
                },
            ) { padding ->
                Column(Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (state.stage) {
                        ImportStage.SOURCE -> SourceStage(onPick = { picker.launch(arrayOf("*/*")) }, onAddress = viewModel::readAddress)
                        ImportStage.READING -> ReadingStage()
                        ImportStage.SUMMARY -> SummaryStage(state, viewModel)
                        ImportStage.IMPORTING -> ImportingStage(state, viewModel::stopImport)
                        ImportStage.DONE -> DoneStage(state, onShow = { onShow(viewModel.firstDay()); viewModel.close() }, onClose = viewModel::close)
                        ImportStage.ERROR -> ErrorStage(state.problem, onClose = viewModel::close, onAgain = viewModel::open)
                        ImportStage.CLOSED -> Unit
                    }
                }
            }
        }
    }
    if (state.confirmLarge) {
        AlertDialog(
            modifier = Modifier.exposeTestTags(),
            onDismissRequest = viewModel::dismissLargeWarning,
            title = { Text(stringResource(R.string.import_large_title)) },
            text = { Text(stringResource(R.string.import_large_text, state.plan?.events ?: 0), Modifier.testTag("import_large_text")) },
            confirmButton = { TextButton(modifier = Modifier.testTag("import_warn_continue"), onClick = { viewModel.startImport(confirmed = true) }) { Text(stringResource(R.string.import_continue)) } },
            dismissButton = { TextButton(modifier = Modifier.testTag("import_warn_change"), onClick = viewModel::dismissLargeWarning) { Text(stringResource(R.string.import_change_days)) } },
        )
    }
}

@Composable
private fun SourceStage(onPick: () -> Unit, onAddress: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Text(stringResource(R.string.import_intro), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onPick, modifier = Modifier.fillMaxWidth().testTag("import_choose_file")) { Text(stringResource(R.string.import_choose_file)) }
    HorizontalDivider()
    Text(stringResource(R.string.import_from_address), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = address,
        onValueChange = { address = it.take(2048) },
        label = { Text(stringResource(R.string.subscription_address)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        maxLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("import_address"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { clipboard.getText()?.text?.let { address = it.take(2048) } }, modifier = Modifier.testTag("import_paste")) { Text(stringResource(R.string.subscription_paste)) }
        OutlinedButton(onClick = { onAddress(address) }, enabled = address.isNotBlank(), modifier = Modifier.testTag("import_address_go")) { Text(stringResource(R.string.import_read)) }
    }
    Text(stringResource(R.string.import_address_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ReadingStage() {
    Row(Modifier.padding(top = 24.dp).testTag("import_reading"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(stringResource(R.string.import_reading))
    }
}

@Composable
private fun ErrorStage(problem: ImportProblem?, onClose: () -> Unit, onAgain: () -> Unit) {
    Text(importProblemText(problem), Modifier.padding(top = 16.dp).testTag("import_problem"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onAgain, modifier = Modifier.testTag("import_again")) { Text(stringResource(R.string.import_try_again)) }
        TextButton(onClick = onClose, modifier = Modifier.testTag("import_error_close")) { Text(stringResource(R.string.import_close)) }
    }
}

@Composable
private fun ImportingStage(state: ImportState, onStop: () -> Unit) {
    val (done, total) = state.progress ?: (0 to 0)
    val writing = total > 0 && done >= total
    Text(stringResource(R.string.import_importing), Modifier.padding(top = 16.dp).testTag("import_importing"), style = MaterialTheme.typography.titleMedium)
    if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().testTag("import_progress"))
    else LinearProgressIndicator(Modifier.fillMaxWidth().testTag("import_progress"))
    // Once the write has begun it is short and cannot be stopped: Stop works only while the events are being prepared.
    OutlinedButton(onClick = onStop, enabled = !writing, modifier = Modifier.testTag("import_stop")) { Text(stringResource(R.string.import_stop)) }
}

@Composable
private fun DoneStage(state: ImportState, onShow: () -> Unit, onClose: () -> Unit) {
    val r = state.result ?: return
    Text(stringResource(R.string.import_done, r.imported, r.updated, r.skipped, r.ignored), Modifier.padding(top = 16.dp).testTag("import_done_text"), style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onShow, modifier = Modifier.testTag("import_done_show")) { Text(stringResource(R.string.import_show)) }
        TextButton(onClick = onClose, modifier = Modifier.testTag("import_done_close")) { Text(stringResource(R.string.import_close)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryStage(state: ImportState, vm: ImportViewModel) {
    val s = state.summary ?: return
    val date = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    // What the file holds.
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (s.label.isNotBlank()) Text(s.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("import_label"))
        Text(stringResource(R.string.import_summary_line, s.eventCount, s.seriesCount), Modifier.testTag("import_summary"))
        if (s.firstDay != null && s.lastDay != null) Text(stringResource(R.string.import_summary_days, s.firstDay!!.format(date), s.lastDay!!.format(date)), Modifier.testTag("import_summary_days"), style = MaterialTheme.typography.bodySmall)
        if (s.ignored.isNotEmpty()) Text(stringResource(R.string.import_summary_ignored, s.ignored.entries.joinToString { "${it.key} ${it.value}" }), Modifier.testTag("import_summary_ignored"), style = MaterialTheme.typography.bodySmall)
        if (s.cancelledEvents > 0) Text(stringResource(R.string.import_summary_cancelled, s.cancelledEvents), Modifier.testTag("import_summary_cancelled"), style = MaterialTheme.typography.bodySmall)
        if (s.attachments > 0) Text(stringResource(R.string.import_attachments, s.attachments), Modifier.testTag("import_summary_attachments"), style = MaterialTheme.typography.bodySmall)
        if (s.attachmentsIgnored > 0) Text(stringResource(R.string.import_attachments_ignored, s.attachmentsIgnored), Modifier.testTag("import_summary_attachments_ignored"), style = MaterialTheme.typography.bodySmall)
        if (s.attachmentsCut > 0) Text(stringResource(R.string.import_attachments_cut, s.attachmentsCut), Modifier.testTag("import_summary_attachments_cut"), style = MaterialTheme.typography.bodySmall)
        if (s.method != null && s.method != "PUBLISH") Text(stringResource(R.string.import_summary_method, s.method!!), Modifier.testTag("import_summary_method"), style = MaterialTheme.typography.bodySmall)
        if (s.hasFloatingTimes) Text(stringResource(R.string.import_floating_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // The destination.
    Text(stringResource(R.string.import_destination), style = MaterialTheme.typography.titleSmall)
    state.locals.forEachIndexed { i, local ->
        ChoiceRow(local.name, state.destination == local.id, "import_dest_$i") { vm.change { it.copy(destination = local.id) } }
    }
    ChoiceRow(stringResource(R.string.import_new_calendar), state.destination == ImportState.NEW, "import_dest_new") { vm.change { it.copy(destination = ImportState.NEW) } }
    if (state.destination == ImportState.NEW) {
        OutlinedTextField(
            value = state.newName,
            onValueChange = { v -> vm.change { it.copy(newName = v.take(100)) } },
            label = { Text(stringResource(R.string.local_calendar_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("import_new_name"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LocalCalendarColors.forEachIndexed { i, hex ->
                Box(
                    Modifier.size(32.dp).background(parseColor(hex), CircleShape)
                        .then(if (hex.equals(state.newColor, ignoreCase = true)) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { vm.change { it.copy(newColor = hex) } }
                        .testTag("import_color_$i"),
                )
            }
        }
    }

    // The days: the same two fields and the same rule as the public calendars.
    Text(stringResource(R.string.import_days), style = MaterialTheme.typography.titleSmall)
    DateRangeFields(state.range, s.firstDay) { r -> vm.change { it.copy(range = r) } }
    state.plan?.let { plan ->
        val text = if (plan.events == s.eventCount) stringResource(R.string.import_count, plan.events) else stringResource(R.string.import_count_of, plan.events, s.eventCount)
        Text(text, Modifier.testTag("import_count"), style = MaterialTheme.typography.bodyMedium)
    }

    // What to do with the events that are already in the calendar: one choice for all of them.
    val duplicates = state.plan?.duplicates ?: 0
    if (duplicates > 0) {
        Text(stringResource(R.string.import_duplicates, duplicates), Modifier.testTag("import_dup_info"), style = MaterialTheme.typography.titleSmall)
        ChoiceRow(stringResource(R.string.import_dup_skip), state.duplicates == DuplicateAction.SKIP, "import_dup_skip") { vm.change { it.copy(duplicates = DuplicateAction.SKIP) } }
        ChoiceRow(stringResource(R.string.import_dup_update), state.duplicates == DuplicateAction.UPDATE, "import_dup_update") { vm.change { it.copy(duplicates = DuplicateAction.UPDATE) } }
        ChoiceRow(stringResource(R.string.import_dup_new), state.duplicates == DuplicateAction.AS_NEW, "import_dup_new") { vm.change { it.copy(duplicates = DuplicateAction.AS_NEW) } }
    }

    // Reminders: three choices, the same for every event the import makes.
    Text(stringResource(R.string.import_reminders), style = MaterialTheme.typography.titleSmall)
    if (s.fileHasAlarms) {
        ChoiceRow(stringResource(R.string.import_rem_file, s.eventsWithAlarms), state.reminders == ReminderChoice.FILE, "import_rem_file") { vm.change { it.copy(reminders = ReminderChoice.FILE) } }
    }
    val appValue = if (state.appDefaultMinutes < 0) stringResource(R.string.import_rem_app_none) else minutesText(state.appDefaultMinutes)
    ChoiceRow(stringResource(R.string.import_rem_app, appValue), state.reminders == ReminderChoice.APP_DEFAULT, "import_rem_app") { vm.change { it.copy(reminders = ReminderChoice.APP_DEFAULT) } }
    ChoiceRow(stringResource(R.string.import_rem_none), state.reminders == ReminderChoice.NONE, "import_rem_none") { vm.change { it.copy(reminders = ReminderChoice.NONE) } }

    val ready = (state.plan?.events ?: 0) > 0 && state.range.isValid && (state.destination != ImportState.NEW || state.newName.isNotBlank())
    Row(Modifier.padding(top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.startImport() }, enabled = ready, modifier = Modifier.testTag("import_confirm")) { Text(stringResource(R.string.import_do)) }
        TextButton(onClick = vm::close, modifier = Modifier.testTag("import_cancel")) { Text(stringResource(android.R.string.cancel)) }
    }
    // Room under the buttons: they can scroll clear of the navigation bar.
    Spacer(Modifier.height(72.dp))
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(tag).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun minutesText(m: Int): String = when {
    m == 1440 -> stringResource(R.string.import_one_day)
    m % 60 == 0 -> stringResource(R.string.import_hours_before, m / 60)
    else -> stringResource(R.string.import_minutes_before, m)
}

@Composable
internal fun importProblemText(problem: ImportProblem?): String = stringResource(
    when (problem) {
        ImportProblem.TOO_LARGE -> R.string.import_problem_too_large
        ImportProblem.NOT_CALENDAR -> R.string.import_problem_not_calendar
        ImportProblem.EMPTY -> R.string.import_problem_empty
        ImportProblem.NO_EVENTS -> R.string.import_problem_no_events
        ImportProblem.BAD_ADDRESS -> R.string.subscription_problem_bad_address
        ImportProblem.NO_NETWORK -> R.string.subscription_problem_no_network
        ImportProblem.TIMEOUT -> R.string.subscription_problem_timeout
        ImportProblem.TLS -> R.string.subscription_problem_tls
        ImportProblem.NOT_FOUND -> R.string.subscription_problem_not_found
        ImportProblem.FORBIDDEN -> R.string.subscription_problem_forbidden
        ImportProblem.SERVER -> R.string.subscription_problem_server
        ImportProblem.OTHER_HTTP -> R.string.subscription_problem_other_http
        ImportProblem.TOO_MANY_REDIRECTS -> R.string.subscription_problem_redirects
        ImportProblem.REDIRECT_NOT_HTTPS -> R.string.subscription_problem_redirect_not_https
        ImportProblem.NOT_A_LOCAL_CALENDAR -> R.string.import_problem_not_local
        ImportProblem.UNREADABLE_FILE, null -> R.string.import_problem_unreadable
    },
)
