package io.github.giansalvo.ggcalendar.feature.event

import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.Frequency
import io.github.giansalvo.ggcalendar.core.model.RecurrenceRule
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/** Google's event colors (colorId 1–11) with their names, as in Google Calendar. */
internal val EVENT_COLORS = listOf(
    "#7986CB" to R.string.color_lavender, "#33B679" to R.string.color_sage, "#8E24AA" to R.string.color_grape,
    "#E67C73" to R.string.color_flamingo, "#F6BF26" to R.string.color_banana, "#F4511E" to R.string.color_tangerine,
    "#039BE5" to R.string.color_peacock, "#616161" to R.string.color_graphite, "#3F51B5" to R.string.color_blueberry,
    "#0B8043" to R.string.color_basil, "#D50000" to R.string.color_tomato,
)

private val REMINDER_MINUTES = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 2880, 10080)
private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

/**
 * The event editor (mockup 6): a full-height bottom sheet with Save. Leaving with unsaved changes asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorSheet(
    request: EditorRequest,
    onDismiss: () -> Unit,
    viewModel: EventEditorViewModel = hiltViewModel(key = "editor"),
) {
    LaunchedEffect(request) { viewModel.load(request) }
    val dismiss: () -> Unit = {
        viewModel.forget()
        onDismiss()
    }
    var confirmDiscard by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden && viewModel.dirty) {
                confirmDiscard = true
                false
            } else {
                true
            }
        },
    )
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { dismiss() } }
    // Scrolling the form past its top must not drag the sheet down: it closes only with X, Back or Save.
    val keepSheetOpen = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available
            override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available
        }
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheet,
        dragHandle = null,
        // Keep the content above Android's navigation buttons.
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        val draft = viewModel.draft
        val calendars by viewModel.calendars.collectAsStateWithLifecycle()
        Column(Modifier.exposeTestTags().fillMaxWidth().fillMaxHeight(0.96f).imePadding().nestedScroll(keepSheetOpen)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (viewModel.dirty) confirmDiscard = true else close() }, modifier = Modifier.testTag("editor_close")) {
                    Icon(Icons.Filled.Close, stringResource(R.string.close))
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { viewModel.save(close) }, modifier = Modifier.testTag("editor_save"), enabled = draft != null, contentPadding = PaddingValues(horizontal = 24.dp)) {
                    Text(stringResource(R.string.save))
                }
            }
            if (draft != null) {
                val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) viewModel.reloadSuggestions()
                }
                var askedContacts by rememberSaveable { mutableStateOf(false) }
                EditorFields(
                    draft, calendars, viewModel.isNew, viewModel.suggestions, viewModel::update,
                    onGuestsFocused = {
                        if (viewModel.needsContacts && !askedContacts) {
                            askedContacts = true
                            askContacts.launch(android.Manifest.permission.READ_CONTACTS)
                        }
                    },
                )
            }
        }
    }

    if (viewModel.askMove) {
        val all by viewModel.calendars.collectAsStateWithLifecycle()
        val from = all.firstOrNull { it.id == viewModel.originalCalendarId }
        val to = all.firstOrNull { it.id == viewModel.draft?.calendarId }
        AlertDialog(
            modifier = Modifier.exposeTestTags(),
            onDismissRequest = viewModel::cancelMove,
            title = { Text(stringResource(R.string.move_calendar_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.move_calendar_question, from?.let { calendarWithAccount(it) } ?: "", to?.let { calendarWithAccount(it) } ?: ""))
                    if (from?.accountEmail != to?.accountEmail) {
                        Text(stringResource(R.string.move_calendar_other_account), modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("move_confirm"), onClick = { viewModel.confirmMove(close) }) { Text(stringResource(R.string.move_calendar_confirm)) }
            },
            dismissButton = { TextButton(modifier = Modifier.testTag("move_cancel"), onClick = viewModel::cancelMove) { Text(stringResource(R.string.keep_editing)) } },
        )
    }

    if (viewModel.askNotify) {
        NotifyGuestsDialog(onCancel = viewModel::cancelNotify) { notify -> viewModel.saveNotifying(close, notify!!) }
    }

    if (viewModel.askScope) {
        SeriesScopeDialog(deleting = false, onDismiss = viewModel::cancelScope) { scope -> viewModel.save(close, scope) }
    }

    if (confirmDiscard) {
        AlertDialog(
            modifier = Modifier.exposeTestTags(),
            onDismissRequest = { confirmDiscard = false },
            text = { Text(stringResource(R.string.discard_question)) },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("discard_confirm"), onClick = {
                    confirmDiscard = false
                    viewModel.update { it } // keep the draft object; the sheet closes anyway
                    dismiss()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(modifier = Modifier.testTag("discard_keep"), onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_editing)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorFields(
    draft: Draft,
    calendars: List<CalendarEntity>,
    isNew: Boolean,
    suggestions: List<Attendee>,
    update: ((Draft) -> Draft) -> Unit,
    onGuestsFocused: () -> Unit,
) {
    val res = LocalContext.current.resources
    val locale = Locale.getDefault()
    var picking by remember { mutableStateOf<Picker?>(null) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TextField(
            value = draft.title,
            onValueChange = { v -> update { it.copy(title = v) } },
            placeholder = { Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall) },
            textStyle = MaterialTheme.typography.headlineSmall,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.testTag("editor_title").fillMaxWidth().padding(start = 40.dp, end = 8.dp),
        )

        CalendarChooser(calendars, draft.calendarId) { id -> update { it.copy(calendarId = id) } }
        Divider()

        Row(Modifier.testTag("editor_all_day").fillMaxWidth().clickable { update { it.copy(allDay = !it.allDay) } }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.all_day), Modifier.weight(1f))
            Switch(checked = draft.allDay, onCheckedChange = { v -> update { it.copy(allDay = v) } })
        }
        DateTimeRow(draft.startDate, draft.startTime, draft.allDay, onDate = { picking = Picker.StartDate }, onTime = { picking = Picker.StartTime }, tag = "editor_start")
        DateTimeRow(draft.endDate, draft.endTime, draft.allDay, onDate = { picking = Picker.EndDate }, onTime = { picking = Picker.EndTime }, tag = "editor_end")
        if (!draft.allDay) {
            FieldRow(Icons.Outlined.Public, tag = "editor_zone", onClick = { picking = Picker.Zone }) { Text(zoneLabel(draft.zone, locale)) }
        }
        FieldRow(Icons.Outlined.Repeat, tag = "editor_recurrence", onClick = { picking = Picker.Recurrence }) {
            Text(draft.rrule?.let { describe(it, res) ?: stringResource(R.string.custom_recurrence) } ?: stringResource(R.string.does_not_repeat))
        }
        Divider()

        Guests(draft, suggestions, update, onGuestsFocused)
        Divider()

        FieldRow(Icons.Outlined.Videocam, tag = "editor_meet", onClick = { update { it.copy(addMeet = !it.addMeet) } }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.add_meet), Modifier.weight(1f))
                Switch(checked = draft.addMeet, onCheckedChange = { v -> update { it.copy(addMeet = v) } })
            }
        }
        draft.meetLink?.takeIf { draft.addMeet }?.let { link ->
            val clipboard = LocalClipboardManager.current
            Row(Modifier.padding(start = 56.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(link, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { clipboard.setText(AnnotatedString(link)) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_link)) }
            }
        }
        Divider()

        PlainField(Icons.Outlined.LocationOn, draft.location, R.string.add_location, tag = "editor_location") { v -> update { it.copy(location = v) } }
        Divider()

        Reminders(draft, calendars.firstOrNull { it.id == draft.calendarId }, onAdd = { picking = Picker.Reminder }, update = update)
        Divider()

        FieldRow(Icons.Outlined.Circle, tag = "editor_color", onClick = { picking = Picker.Color }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val cal = calendars.firstOrNull { it.id == draft.calendarId }
                Box(Modifier.size(16.dp).background(parseColor(draft.color ?: cal?.color), CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(EVENT_COLORS.firstOrNull { it.first.equals(draft.color, ignoreCase = true) }?.let { stringResource(it.second) } ?: stringResource(R.string.default_color))
            }
        }
        FieldRow(Icons.Outlined.WorkOutline, tag = "editor_availability", onClick = { update { it.copy(availability = if (it.availability == "FREE") "BUSY" else "FREE") } }) {
            Text(stringResource(if (draft.availability == "FREE") R.string.free else R.string.busy))
        }
        FieldRow(Icons.Outlined.Lock, tag = "editor_visibility", onClick = { picking = Picker.Visibility }) {
            Text(
                stringResource(
                    when (draft.visibility) {
                        "PUBLIC" -> R.string.visibility_public
                        "PRIVATE" -> R.string.visibility_private
                        else -> R.string.visibility_default
                    },
                ),
            )
        }
        Divider()
        PlainField(Icons.Outlined.Notes, draft.description, R.string.add_description, singleLine = false, tag = "editor_description") { v -> update { it.copy(description = v) } }
    }

    when (picking) {
        Picker.StartDate -> DateDialog(draft.startDate, { picking = null }) { d -> update { it.withStart(date = d) } }
        Picker.EndDate -> DateDialog(draft.endDate, { picking = null }) { d -> update { it.withEnd(date = d) } }
        Picker.StartTime -> TimeDialog(draft.startTime, { picking = null }) { t -> update { it.withStart(time = t) } }
        Picker.EndTime -> TimeDialog(draft.endTime, { picking = null }) { t -> update { it.withEnd(time = t) } }
        Picker.Zone -> ZoneDialog(draft.zone, { picking = null }) { z -> update { it.copy(zoneId = z.id) } }
        Picker.Recurrence -> RecurrenceDialog(draft, { picking = null }) { r -> update { it.copy(rrule = r) } }
        Picker.Reminder -> ReminderDialog({ picking = null }) { r -> update { it.copy(reminders = (it.reminders.orEmpty() + r).distinct()) } }
        Picker.Color -> ColorDialog(draft.color, { picking = null }) { c -> update { it.copy(color = c) } }
        Picker.Visibility -> ChoiceDialog(
            listOf("DEFAULT" to R.string.visibility_default, "PUBLIC" to R.string.visibility_public, "PRIVATE" to R.string.visibility_private),
            draft.visibility, { picking = null },
            onPick = { v -> update { it.copy(visibility = v) } },
        )
        null -> Unit
    }
}

/** Drop-down of the writable calendars, grouped by account (the primary one shown as the account). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarChooser(calendars: List<CalendarEntity>, selectedId: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = calendars.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = Modifier.padding(start = 56.dp, end = 20.dp)) {
        OutlinedTextField(
            value = selected?.let { calendarLabel(it) } ?: "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.calendar)) },
            // The account always shows: "My calendar" and calendar names can repeat across accounts.
            supportingText = selected?.accountEmail?.let { { Text(it) } },
            leadingIcon = { Box(Modifier.size(14.dp).background(parseColor(selected?.color), CircleShape)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            calendars.groupBy { it.accountEmail ?: "" }.forEach { (account, list) ->
                Text(
                    account,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                list.forEach { cal ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(calendarLabel(cal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                cal.accountEmail?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                        leadingIcon = { Box(Modifier.size(14.dp).background(parseColor(cal.color), CircleShape)) },
                        onClick = {
                            onSelect(cal.id)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun calendarLabel(cal: CalendarEntity): String =
    if (cal.isPrimary) stringResource(R.string.my_calendar) else cal.name

/** The calendar with its account: "My calendar" and calendar names can repeat across accounts. */
@Composable
private fun calendarWithAccount(cal: CalendarEntity): String =
    calendarLabel(cal) + (cal.accountEmail?.let { " ($it)" } ?: "")

private enum class Picker { StartDate, EndDate, StartTime, EndTime, Zone, Recurrence, Reminder, Color, Visibility }

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun FieldRow(icon: ImageVector, tag: String? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Row(
        (if (tag != null) Modifier.testTag(tag) else Modifier).fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun PlainField(icon: ImageVector, value: String, placeholder: Int, singleLine: Boolean = true, tag: String? = null, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        TextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(stringResource(placeholder)) },
            singleLine = singleLine,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = (if (tag != null) Modifier.testTag(tag) else Modifier).weight(1f),
        )
    }
}

private val editorDate get() = io.github.giansalvo.ggcalendar.core.data.DateFormats.of("EEEdMMMy")

@Composable
private fun DateTimeRow(date: LocalDate, time: LocalTime, allDay: Boolean, onDate: () -> Unit, onTime: () -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            date.format(editorDate).replaceFirstChar { it.titlecase(Locale.getDefault()) },
            Modifier.testTag(tag + "_date").weight(1f).clickable(onClick = onDate).padding(vertical = 12.dp),
        )
        if (!allDay) Text(time.format(timeFormat), Modifier.testTag(tag + "_time").clickable(onClick = onTime).padding(12.dp))
    }
}

private fun zoneLabel(zone: ZoneId, locale: Locale): String {
    val offset = zone.rules.getOffset(Instant.now()).id.let { if (it == "Z") "" else it }
    return "${zone.getDisplayName(TextStyle.FULL, locale)} (GMT$offset)"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Guests(draft: Draft, suggestions: List<Attendee>, update: ((Draft) -> Draft) -> Unit, onFocused: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    fun add(email: String, name: String? = null) {
        val e = email.trim()
        if (!EMAIL.matches(e)) {
            error = true
            return
        }
        error = false
        update { d -> if (d.attendees.any { it.email.equals(e, ignoreCase = true) }) d else d.copy(attendees = d.attendees + Attendee(e, displayName = name)) }
        text = ""
    }
    val matches = remember(text, suggestions, draft.attendees) {
        if (text.length < 2) emptyList()
        else suggestions.filter { s ->
            draft.attendees.none { it.email.equals(s.email, ignoreCase = true) } &&
                (s.email.contains(text, ignoreCase = true) || s.displayName?.contains(text, ignoreCase = true) == true)
        }.take(6)
    }
    var browsing by remember { mutableStateOf(false) }
    // Keep the suggestions in sight above the keyboard while typing.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(matches) { if (matches.isNotEmpty()) bringIntoView.bringIntoView() }
    Column(Modifier.bringIntoViewRequester(bringIntoView)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                onFocused() // asks for the contacts permission the first time
                browsing = true
            }) {
                Icon(Icons.Outlined.Group, stringResource(R.string.browse_contacts), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f)) {
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = false
                    },
                    placeholder = { Text(stringResource(R.string.add_guests)) },
                    singleLine = true,
                    isError = error,
                    supportingText = if (error) ({ Text(stringResource(R.string.invalid_email)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) add(text) }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.testTag("editor_guest_input").fillMaxWidth().onFocusChanged { if (it.isFocused) onFocused() },
                )
            }
        }
        // Suggestions as a list under the field (a pop-up menu would steal touches and its dismissal lost the text).
        matches.forEach { m ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { add(m.email, m.displayName) }
                    .padding(start = 56.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(m.displayName ?: m.email, style = MaterialTheme.typography.bodyMedium)
                if (m.displayName != null) {
                    Text(m.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (browsing) {
            ContactsDialog(
                contacts = suggestions,
                selected = draft.attendees.map { it.email.lowercase() }.toSet(),
                onToggle = { c ->
                    update { d ->
                        if (d.attendees.any { it.email.equals(c.email, ignoreCase = true) }) d.copy(attendees = d.attendees.filterNot { it.email.equals(c.email, ignoreCase = true) })
                        else d.copy(attendees = d.attendees + Attendee(c.email, displayName = c.displayName))
                    }
                },
                onDismiss = { browsing = false },
            )
        }
        if (draft.attendees.isNotEmpty()) {
            FlowRow(Modifier.padding(start = 56.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.attendees.forEach { a ->
                    InputChip(
                        selected = a.optional,
                        onClick = { update { d -> d.copy(attendees = d.attendees.map { if (it.email == a.email) it.copy(optional = !it.optional) else it }) } },
                        label = { Text((a.displayName ?: a.email) + if (a.optional) " · " + stringResource(R.string.optional) else "") },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close, stringResource(R.string.remove),
                                Modifier.size(18.dp).clickable { update { d -> d.copy(attendees = d.attendees.filterNot { it.email == a.email }) } },
                            )
                        },
                    )
                }
            }
            CheckRow(stringResource(R.string.send_invites), draft.sendInvites) { v -> update { it.copy(sendInvites = v) } }
            CheckRow(stringResource(R.string.guests_can_modify), draft.guestsCanModify) { v -> update { it.copy(guestsCanModify = v) } }
            CheckRow(stringResource(R.string.guests_can_invite), draft.guestsCanInvite) { v -> update { it.copy(guestsCanInvite = v) } }
            CheckRow(stringResource(R.string.guests_can_see_list), draft.guestsCanSeeList) { v -> update { it.copy(guestsCanSeeList = v) } }
        }
    }
}

/** Every known address (contacts and past guests), searchable and scrollable; a tap adds or removes the guest. */
@Composable
private fun ContactsDialog(contacts: List<Attendee>, selected: Set<String>, onToggle: (Attendee) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, contacts) {
        contacts.filter { query.isBlank() || it.email.contains(query, ignoreCase = true) || it.displayName?.contains(query, ignoreCase = true) == true }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraLarge).padding(16.dp)) {
            Text(stringResource(R.string.browse_contacts), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.search_contacts)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            if (shown.isEmpty()) {
                Text(stringResource(R.string.no_contacts), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.email.lowercase() }) { c ->
                    val checked = c.email.lowercase() in selected
                    Row(Modifier.fillMaxWidth().clickable { onToggle(c) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { onToggle(c) })
                        Column {
                            Text(c.displayName ?: c.email, style = MaterialTheme.typography.bodyMedium)
                            if (c.displayName != null) Text(c.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(android.R.string.ok)) }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Reminders(draft: Draft, calendar: CalendarEntity?, onAdd: () -> Unit, update: ((Draft) -> Draft) -> Unit) {
    val res = LocalContext.current.resources
    val list = draft.reminders
    Column {
        if (list == null) {
            // The calendar's defaults until the user changes them.
            calendar?.defaultReminders.orEmpty().forEach { r ->
                ReminderRow(r, res, note = stringResource(R.string.calendar_defaults)) {
                    update { d -> d.copy(reminders = calendar?.defaultReminders.orEmpty() - r) }
                }
            }
        } else {
            list.sortedBy { it.minutes }.forEach { r ->
                ReminderRow(r, res) { update { d -> d.copy(reminders = d.reminders.orEmpty() - r) } }
            }
        }
        Text(
            stringResource(R.string.add_reminder),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("editor_add_reminder").fillMaxWidth().clickable {
                if (list == null) update { d -> d.copy(reminders = calendar?.defaultReminders.orEmpty()) }
                onAdd()
            }.padding(start = 56.dp, top = 12.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun ReminderRow(r: Reminder, res: android.content.res.Resources, note: String? = null, onRemove: () -> Unit) {
    val icon = when (r.method) {
        ReminderMethod.ALARM -> Icons.Outlined.Alarm
        ReminderMethod.EMAIL -> Icons.Outlined.Email
        ReminderMethod.POPUP -> Icons.Outlined.Notifications
    }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("${methodText(r, res)} · ${beforeText(r.minutes, res)}")
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The Material date picker works in UTC milliseconds.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(initial.hour, initial.minute, is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(LocalTime.of(state.hour, state.minute))
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = { TimePicker(state) },
    )
}

@Composable
private fun ZoneDialog(current: ZoneId, onDismiss: () -> Unit, onPick: (ZoneId) -> Unit) {
    val locale = Locale.getDefault()
    var query by remember { mutableStateOf("") }
    val zones = remember {
        ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .map { ZoneId.of(it) }
            .sortedBy { it.rules.getOffset(Instant.now()).totalSeconds }
    }
    val shown = remember(query) {
        zones.filter { query.isBlank() || it.id.contains(query, ignoreCase = true) || it.getDisplayName(TextStyle.FULL, locale).contains(query, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraLarge).padding(16.dp)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.search_time_zone)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.id }) { z ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            onPick(z)
                            onDismiss()
                        }.padding(vertical = 10.dp),
                    ) {
                        Text(zoneLabel(z, locale), color = if (z == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(z.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Presets as in Google Calendar, then "Custom…". */
@Composable
private fun RecurrenceDialog(draft: Draft, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        CustomRecurrenceDialog(draft.startDate, draft.allDay, draft.rrule, onDismiss, onPick)
        return
    }
    val res = LocalContext.current.resources
    val locale = Locale.getDefault()
    val day = draft.startDate
    val weekday = day.dayOfWeek
    val presets: List<Pair<String?, String>> = buildList {
        add(null to res.getString(R.string.does_not_repeat))
        add(RecurrenceRule(Frequency.DAILY).toRrule(draft.allDay) to res.getQuantityString(R.plurals.every_day, 1))
        add(RecurrenceRule(Frequency.WEEKLY, days = listOf(weekday)).toRrule(draft.allDay) to res.getString(R.string.weekly_on, weekday.getDisplayName(TextStyle.FULL, locale)))
        add(RecurrenceRule(Frequency.MONTHLY, monthDay = day.dayOfMonth).toRrule(draft.allDay) to res.getString(R.string.monthly_on_day, day.dayOfMonth))
        add(RecurrenceRule(Frequency.YEARLY).toRrule(draft.allDay) to res.getString(R.string.yearly_on, day.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM"))))
        add(
            RecurrenceRule(Frequency.WEEKLY, days = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)).toRrule(draft.allDay)
                to res.getString(R.string.weekdays),
        )
        // Keep a rule the presets do not cover.
        draft.rrule?.let { current -> if (none { it.first == current }) add(current to (describe(current, res) ?: res.getString(R.string.custom_recurrence))) }
    }
    val customLabel = res.getString(R.string.custom_ellipsis)
    ChoiceDialog(
        presets.map { it.first to it.second } + (CUSTOM to customLabel),
        draft.rrule,
        onDismiss = { if (!custom) onDismiss() },
        onPick = { if (it == CUSTOM) custom = true else onPick(it) },
        labels = true,
    )
}

private const val CUSTOM = "custom"

@Composable
private fun <T> ChoiceDialog(options: List<Pair<T, Any>>, selected: T, onDismiss: () -> Unit, onPick: (T) -> Unit, labels: Boolean = false) {
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.testTag("choice_" + value.toString()).fillMaxWidth().clickable {
                            onPick(value)
                            onDismiss() // after onPick, which may keep the dialog open ("Custom…")
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = {
                            onPick(value)
                            onDismiss() // after onPick, which may keep the dialog open ("Custom…")
                        })
                        Text(if (labels || label is String) label.toString() else stringResource(label as Int))
                    }
                }
            }
        },
    )
}

@Composable
private fun ReminderDialog(onDismiss: () -> Unit, onPick: (Reminder) -> Unit) {
    val res = LocalContext.current.resources
    var method by remember { mutableStateOf(ReminderMethod.POPUP) }
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(modifier = Modifier.testTag("reminder_ok"), enabled = custom.toIntOrNull() != null, onClick = {
                onPick(Reminder(method, custom.toInt()))
                onDismiss()
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ReminderMethod.POPUP, ReminderMethod.ALARM, ReminderMethod.EMAIL).forEach { m ->
                        FilterChip(selected = m == method, onClick = { method = m }, modifier = Modifier.testTag("reminder_method_" + m.name), label = { Text(methodText(Reminder(m, 0), res)) })
                    }
                }
                REMINDER_MINUTES.forEach { minutes ->
                    Text(
                        beforeText(minutes, res),
                        Modifier.fillMaxWidth().clickable {
                            onPick(Reminder(method, minutes))
                            onDismiss()
                        }.padding(vertical = 10.dp),
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.testTag("reminder_custom_minutes"),
                    value = custom,
                    onValueChange = { v -> custom = v.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.custom_minutes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
    )
}

@Composable
private fun ColorDialog(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val options: List<Pair<String?, Any>> = listOf<Pair<String?, Any>>(null to R.string.default_color) + EVENT_COLORS
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (hex, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onPick(hex)
                            onDismiss()
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(20.dp).background(if (hex == null) MaterialTheme.colorScheme.outline else parseColor(hex), CircleShape))
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(label as Int), color = if (hex.equals(current, ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
    )
}

/** Whether to tell the guests about a change: Google sends them the email only when asked to. */
@Composable
fun NotifyGuestsDialog(onCancel: () -> Unit, onChoose: (Boolean?) -> Unit) {
    AlertDialog(
        modifier = Modifier.exposeTestTags(),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.notify_guests_title)) },
        text = { Text(stringResource(R.string.notify_guests_text)) },
        confirmButton = { TextButton(modifier = Modifier.testTag("notify_send"), onClick = { onChoose(true) }) { Text(stringResource(R.string.notify_guests_send)) } },
        dismissButton = {
            Row {
                TextButton(modifier = Modifier.testTag("notify_cancel"), onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
                TextButton(modifier = Modifier.testTag("notify_dont"), onClick = { onChoose(false) }) { Text(stringResource(R.string.notify_guests_dont)) }
            }
        },
    )
}
