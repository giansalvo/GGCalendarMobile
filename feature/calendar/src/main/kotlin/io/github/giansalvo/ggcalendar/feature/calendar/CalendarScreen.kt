package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import io.github.giansalvo.ggcalendar.core.notifications.ActiveAlarmsSheet
import io.github.giansalvo.ggcalendar.core.notifications.NotificationsSheet
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.giansalvo.ggcalendar.feature.event.ExportDialog
import io.github.giansalvo.ggcalendar.feature.event.EditorRequest
import io.github.giansalvo.ggcalendar.feature.event.EventDetailSheet
import io.github.giansalvo.ggcalendar.feature.event.EventEditorSheet
import io.github.giansalvo.ggcalendar.feature.event.NotifyGuestsDialog
import io.github.giansalvo.ggcalendar.feature.event.SeriesScopeDialog
import io.github.giansalvo.ggcalendar.feature.settings.SettingsScreen
import io.github.giansalvo.ggcalendar.feature.event.occurrence
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** An occurrence to open on arrival (the tap on a reminder notification). */
data class EventLink(val eventId: String, val beginUtc: Long)

@Composable
fun CalendarScreen(link: EventLink? = null, onLinkHandled: () -> Unit = {}, viewModel: CalendarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        if (link != null) {
            viewModel.openLink(link)
            onLinkHandled()
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var miniOpen by rememberSaveable { mutableStateOf(false) }
    var yearPickerOpen by rememberSaveable { mutableStateOf(false) }
    var notificationsOpen by rememberSaveable { mutableStateOf(false) }
    var alarmsOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    val pickAccount = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onAccountChosen(it.resultCode, it.data)
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onConsentResult(it.resultCode, it.data)
    }
    LaunchedEffect(state.consent) {
        state.consent?.let {
            viewModel.consentLaunched()
            consent.launch(IntentSenderRequest.Builder(it.intentSender).build())
        }
    }
    val okText = stringResource(android.R.string.ok)
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it, actionLabel = okText, withDismissAction = true, duration = SnackbarDuration.Indefinite)
            viewModel.dismissError()
        }
    }
    val authText = stringResource(R.string.message_auth)
    val authAction = stringResource(R.string.authorize)
    LaunchedEffect(state.message?.id) {
        val message = state.message ?: return@LaunchedEffect
        val isAuth = message.kind == "auth"
        val text = if (isAuth) authText.format(message.accountEmail) else message.text
        // Conflicts and errors stay until the user closes them: they explain what happened to an edit.
        val result = snackbar.showSnackbar(
            text,
            actionLabel = if (isAuth) authAction else okText,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed && isAuth) message.accountEmail?.let(viewModel::reauthorize)
        viewModel.dismissMessage(message.id)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            CalendarDrawer(
                state = state,
                onSelectView = {
                    viewModel.selectView(it)
                    scope.launch { drawerState.close() }
                },
                onRefresh = {
                    viewModel.refresh()
                    scope.launch { drawerState.close() }
                },
                onToggleCalendar = viewModel::toggleCalendar,
                onAddAccount = { pickAccount.launch(viewModel.chooseAccountIntent()) },
                onActiveAlarms = {
                    alarmsOpen = true
                    scope.launch { drawerState.close() }
                },
                onSettings = {
                    settingsOpen = true
                    scope.launch { drawerState.close() }
                },
                onExport = {
                    exportOpen = true
                    scope.launch { drawerState.close() }
                },
                onAbout = {
                    aboutOpen = true
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                CalendarTopBar(
                    state = state,
                    onMenu = { scope.launch { drawerState.open() } },
                    miniOpen = miniOpen,
                    onTitle = {
                        // The month + schedule view already swipes between months: the title only picks the year.
                        if (state.view == CalendarView.MONTH_LIST) {
                            yearPickerOpen = true
                            return@CalendarTopBar
                        }
                        miniOpen = !miniOpen
                        if (!miniOpen) viewModel.showMiniMonth(null)
                    },
                    onToday = viewModel::goToToday,
                    onSync = viewModel::refresh,
                    onSearch = { searchOpen = true },
                    onSettings = { settingsOpen = true },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { viewModel.openEditor(newEventRequest(state)) }, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_event))
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.busy || state.syncing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                } else {
                    Spacer(Modifier.height(2.dp))
                }
                AnimatedVisibility(miniOpen, enter = expandVertically(), exit = shrinkVertically()) {
                    MiniCalendar(
                        selected = state.date,
                        eventDays = state.eventDays,
                        onMonthChange = {
                            viewModel.showMiniMonth(it)
                            viewModel.showMonth(it)
                        },
                        onPick = {
                            viewModel.goTo(it)
                            miniOpen = false
                            viewModel.showMiniMonth(null)
                        },
                    )
                }
                key(state.firstDay) {
                when (state.view) {
                    CalendarView.SCHEDULE -> ScheduleView(
                        date = state.date,
                        from = state.scheduleFrom,
                        to = state.scheduleTo,
                        instances = state.instances,
                        onVisibleDay = viewModel::scheduleScrolled,
                        onReachStart = { viewModel.extendSchedule(past = true) },
                        onReachEnd = { viewModel.extendSchedule(past = false) },
                        onEventClick = viewModel::openEvent,
                    )
                    CalendarView.MONTH -> MonthView(
                        date = state.date,
                        instances = state.instances,
                        onMonthChange = viewModel::showMonth,
                        onDayClick = viewModel::openDay,
                    )
                    CalendarView.MONTH_LIST -> MonthListView(
                        date = state.date,
                        instances = state.instances,
                        onMonthChange = viewModel::showMonth,
                        onDayClick = viewModel::goTo,
                        onEventClick = viewModel::openEvent,
                    )
                    CalendarView.DAY, CalendarView.SIX_DAYS, CalendarView.WEEK -> key(state.view) {
                        TimeGridView(
                            date = state.date,
                            dayCount = state.view.dayCount,
                            instances = state.instances,
                            hourHeight = state.hourHeight,
                            dayStartHour = state.dayStartHour,
                            dimPast = state.dimPast,
                            onHourHeightChange = viewModel::setHourHeight,
                            onPageChange = viewModel::showDays,
                            onDayClick = viewModel::openDay,
                            onEventClick = viewModel::openEvent,
                            onCreateAt = { start -> viewModel.openEditor(EditorRequest.New(start)) },
                            pendingStart = (state.editing as? EditorRequest.New)?.start,
                            onEventMoved = viewModel::moveEvent,
                        )
                    }
                }
                }
            }
        }
    }

    state.selected?.let { instance ->
        EventDetailSheet(
            instance = instance,
            onDismiss = { viewModel.openEvent(null) },
            onEdit = { detail, shown -> viewModel.openEditor(EditorRequest.Edit(detail.event.id, shown.occurrence())) },
        )
    }
    if (yearPickerOpen) {
        val shown = YearMonth.from(state.date)
        AlertDialog(
            onDismissRequest = { yearPickerOpen = false },
            confirmButton = { TextButton(onClick = { yearPickerOpen = false }) { Text(stringResource(android.R.string.cancel)) } },
            title = { Text(stringResource(R.string.choose_year)) },
            text = {
                YearGrid(
                    current = shown.year,
                    years = (LocalDate.now().year - 99)..(LocalDate.now().year + 99),
                    onPick = { year ->
                        yearPickerOpen = false
                        viewModel.showMonth(YearMonth.of(year, shown.month))
                    },
                )
            },
        )
    }
    if (alarmsOpen) {
        ActiveAlarmsSheet(
            onDismiss = { alarmsOpen = false },
            onOpenEvent = { eventId, beginUtc -> viewModel.openLink(EventLink(eventId, beginUtc)) },
        )
    }
    if (notificationsOpen) NotificationsSheet(onDismiss = { notificationsOpen = false })
    if (settingsOpen) {
        SettingsScreen(onClose = { settingsOpen = false }, onOpenNotifications = { notificationsOpen = true })
    }
    if (exportOpen) ExportDialog(accounts = state.accounts, onDismiss = { exportOpen = false })
    if (aboutOpen) AboutSheet(onDismiss = { aboutOpen = false })
    if (searchOpen) {
        SearchScreen(search = viewModel::search, refreshKey = state.selected, onOpen = viewModel::openEvent, onClose = { searchOpen = false })
    }
    state.pendingMove?.let { move ->
        if (move.needsScope) {
            SeriesScopeDialog(deleting = false, onDismiss = { viewModel.confirmMove(null) }, onConfirm = viewModel::confirmMove)
        } else {
            NotifyGuestsDialog(onCancel = { viewModel.confirmNotify(null) }, onChoose = viewModel::confirmNotify)
        }
    }
    state.editing?.let { request ->
        EventEditorSheet(request = request, onDismiss = { viewModel.openEditor(null) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTopBar(state: CalendarUiState, miniOpen: Boolean, onMenu: () -> Unit, onTitle: () -> Unit, onToday: () -> Unit, onSync: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val locale = Locale.getDefault()
    val month = state.date.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
    val title = if (state.date.year == LocalDate.now().year) month else "$month ${state.date.year}"
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.open_menu)) }
        },
        title = {
            Row(Modifier.clickable(onClick = onTitle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title)
                Icon(
                    if (miniOpen) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.go_to_date),
                )
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
            }
            // Sync now. A badge counts the changes not yet on Google (they wait for a connection when offline).
            IconButton(onClick = onSync) {
                BadgedBox(badge = { if (state.pending > 0) Badge { Text(state.pending.toString()) } }) {
                    if (state.syncing) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                }
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
            }
            IconButton(onClick = onToday) { TodayIcon(LocalDate.now().dayOfMonth) }
            Avatar(state.accounts.firstOrNull()?.email)
            Spacer(Modifier.width(8.dp))
        },
    )
}

@Composable
private fun TodayIcon(day: Int) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(24.dp).border(2.dp, color, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(day.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun Avatar(email: String?) {
    Box(
        Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            email?.firstOrNull()?.uppercase() ?: "?",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun CalendarDrawer(
    state: CalendarUiState,
    onSelectView: (CalendarView) -> Unit,
    onRefresh: () -> Unit,
    onToggleCalendar: (String, Boolean) -> Unit,
    onAddAccount: () -> Unit,
    onActiveAlarms: () -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    onAbout: () -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text(
                stringResource(R.string.app_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 16.dp),
            )
            CalendarView.entries.forEach { view ->
                NavigationDrawerItem(
                    icon = { Icon(view.icon, contentDescription = null) },
                    label = { Text(stringResource(view.label)) },
                    selected = view == state.view,
                    onClick = { onSelectView(view) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                label = { Text(stringResource(R.string.refresh)) },
                selected = false,
                onClick = onRefresh,
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Alarm, contentDescription = null) },
                label = { Text(stringResource(R.string.active_alarms)) },
                selected = false,
                onClick = onActiveAlarms,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            state.accounts.forEach { account ->
                Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(account.email)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(account.email, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Google", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                account.calendars.forEach { CalendarRow(it, onToggleCalendar) }
            }
            state.birthdays?.let { birthdays ->
                Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Cake, contentDescription = null)
                    Text(stringResource(R.string.contacts), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                }
                CalendarRow(birthdays, onToggleCalendar)
            }
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                label = { Text(stringResource(R.string.add_google_account)) },
                selected = false,
                onClick = onAddAccount,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                label = { Text(stringResource(R.string.settings)) },
                selected = false,
                onClick = onSettings,
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                label = { Text(stringResource(R.string.export_ics)) },
                selected = false,
                onClick = onExport,
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                label = { Text(stringResource(R.string.about)) },
                selected = false,
                onClick = onAbout,
            )
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun CalendarRow(calendar: CalendarInfo, onToggle: (String, Boolean) -> Unit) {
    val color = parseColor(calendar.colorHex)
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(calendar.id, !calendar.isVisible) }.padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = calendar.isVisible,
            onCheckedChange = { onToggle(calendar.id, it) },
            colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color),
        )
        // Google names the primary calendar after the account email; show it as Google Calendar does.
        val name = if (calendar.isPrimary && calendar.name == calendar.accountEmail) stringResource(R.string.my_calendar) else calendar.name
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The floating "+": in the month + list view the event starts on the selected day, otherwise at the default time. */
private fun newEventRequest(state: CalendarUiState): EditorRequest.New {
    if (state.view != CalendarView.MONTH_LIST) return EditorRequest.New()
    val now = java.time.LocalDateTime.now()
    val hour = if (state.date == now.toLocalDate()) minOf(now.hour + 1, 23) else 9
    return EditorRequest.New(state.date.atTime(hour, 0))
}
