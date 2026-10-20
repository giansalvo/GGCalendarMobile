package io.github.giansalvo.ggcalendar.feature.calendar

import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags
import androidx.compose.ui.platform.testTag
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
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import io.github.giansalvo.ggcalendar.core.notifications.ActiveAlarmsSheet
import io.github.giansalvo.ggcalendar.core.notifications.PermissionsIntro
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.model.Diag
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
    // Diag build only (see Diag): what the screen sees of the accounts, and when its lifecycle moves.
    LaunchedEffect(state.instances) { Diag.log { "ui instances n=${state.instances.size} view=${state.view} date=${state.date}" } }
    LaunchedEffect(state.editing) { Diag.log { "ui editing=${state.editing?.javaClass?.simpleName}" } }
    LaunchedEffect(state.accounts) {
        Diag.log { "ui accounts=${state.accounts.size} [${state.accounts.joinToString { a -> Diag.who(a.email) + ":" + a.calendars.size }}]" }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> Diag.log { "ui lifecycle $event" } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(link) {
        if (link != null) {
            viewModel.openLink(link)
            onLinkHandled()
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(drawerState) { snapshotFlow { drawerState.currentValue }.collect { Diag.log { "ui drawer=$it" } } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var miniOpen by rememberSaveable { mutableStateOf(false) }
    var yearPickerOpen by rememberSaveable { mutableStateOf(false) }
    var alarmsOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    // The local calendar being made (empty text) or changed (its id), and the one being deleted.
    var editingLocal by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingLocal by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    val pickAccount = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Diag.log { "ui pickAccount result=${it.resultCode}" }
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
    // While Google is asked about a new account: a line that says so; it goes away by itself when the account is in.
    val addingEmail = state.addingAccount
    val addingText = stringResource(R.string.adding_account, addingEmail.orEmpty())
    LaunchedEffect(addingEmail) {
        if (addingEmail != null) snackbar.showSnackbar(addingText, duration = SnackbarDuration.Indefinite)
    }
    val timeoutEmail = state.accountTimeout
    val timeoutText = stringResource(R.string.account_timeout, timeoutEmail.orEmpty())
    val retryText = stringResource(R.string.retry)
    LaunchedEffect(timeoutEmail) {
        if (timeoutEmail != null) {
            val result = snackbar.showSnackbar(timeoutText, actionLabel = retryText, withDismissAction = true, duration = SnackbarDuration.Indefinite)
            viewModel.dismissAccountTimeout()
            if (result == SnackbarResult.ActionPerformed) viewModel.reauthorize(timeoutEmail)
        }
    }
    val noCalendarText = stringResource(R.string.message_no_calendar)
    LaunchedEffect(state.noCalendar) {
        if (state.noCalendar) {
            snackbar.showSnackbar(noCalendarText, withDismissAction = true)
            viewModel.dismissNoCalendar()
        }
    }
    val authText = stringResource(R.string.message_auth)
    val authAction = stringResource(R.string.authorize)
    val missingText = stringResource(R.string.message_account_missing)
    val schemaResetText = stringResource(R.string.message_schema_reset)
    val syncErrorText = stringResource(R.string.message_sync_error)
    val addLabel = stringResource(R.string.account_add_action)
    val closeLabel = stringResource(R.string.message_close)
    LaunchedEffect(state.message?.id) {
        val message = state.message ?: return@LaunchedEffect
        val isAuth = message.kind == "auth"
        // The sentence is made here, in the language of the app now, from what the sync stored (a stored sentence keeps its language).
        val text = when (message.kind) {
            "auth" -> authText.format(message.accountEmail)
            "account_missing" -> missingText.format(message.accountEmail)
            "sync_error" -> syncErrorText.format(message.accountEmail, message.text)
            "schema_reset" -> schemaResetText
            else -> message.text
        }
        // Conflicts and errors stay until the user closes them: they explain what happened to an edit.
        val result = if (message.kind == "account_missing") {
            // The account is in the app but no longer on the phone: "Add" opens the list of the accounts of Android right away.
            snackbar.showSnackbar(AddOrCloseVisuals(text, addLabel, closeLabel))
        } else {
            snackbar.showSnackbar(
                text,
                actionLabel = if (isAuth) authAction else okText,
                // An error or conflict is closed with OK alone; the "authorize again" message keeps the X, its other button not being a way out.
                withDismissAction = isAuth,
                duration = SnackbarDuration.Indefinite,
            )
        }
        if (result == SnackbarResult.ActionPerformed && isAuth) message.accountEmail?.let(viewModel::reauthorize)
        if (result == SnackbarResult.ActionPerformed && message.kind == "account_missing") pickAccount.launch(viewModel.chooseAccountIntent())
        viewModel.dismissMessage(message.id)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = Modifier.exposeTestTags(),
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
                onRetryAccount = viewModel::reauthorize,
                onRemoveAccount = { removing = it },
                onNewLocal = { editingLocal = "" },
                onEditLocal = { editingLocal = it },
                onDeleteLocal = { deletingLocal = it },
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
                FloatingActionButton(onClick = { viewModel.openEditor(newEventRequest(state)) }, modifier = Modifier.testTag("fab_new_event"), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_event))
                }
            },
            snackbarHost = {
                SnackbarHost(snackbar, Modifier.testTag("snackbar")) { data ->
                    val visuals = data.visuals
                    if (visuals is AddOrCloseVisuals) {
                        // Two buttons under the sentence: Close (or the X of the host) and Add.
                        Snackbar(
                            actionOnNewLine = true,
                            action = {
                                Row {
                                    TextButton(modifier = Modifier.testTag("snackbar_close"), onClick = { data.dismiss() }) { Text(visuals.closeLabel) }
                                    TextButton(modifier = Modifier.testTag("snackbar_add"), onClick = { data.performAction() }) { Text(visuals.addLabel) }
                                }
                            },
                        ) { Text(visuals.message) }
                    } else {
                        Snackbar(data)
                    }
                }
            },
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
                        dimPast = state.dimPast,
                    )
                    CalendarView.MONTH -> MonthView(
                        date = state.date,
                        instances = state.instances,
                        onMonthChange = viewModel::showMonth,
                        onDayClick = viewModel::openDay,
                        dimPast = state.dimPast,
                    )
                    CalendarView.MONTH_LIST -> MonthListView(
                        date = state.date,
                        instances = state.instances,
                        onMonthChange = viewModel::showMonth,
                        onDayClick = viewModel::goTo,
                        onEventClick = viewModel::openEvent,
                        dimPast = state.dimPast,
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
    PermissionsIntro()
    if (settingsOpen) {
        SettingsScreen(onClose = { settingsOpen = false })
    }
    if (exportOpen) ExportDialog(accounts = state.accounts, locals = state.localCalendars, onDismiss = { exportOpen = false })
    editingLocal?.let { id ->
        val current = state.localCalendars.firstOrNull { it.id == id }
        LocalCalendarDialog(
            initial = current,
            onConfirm = { name, color ->
                if (current == null) viewModel.createLocalCalendar(name, color) else viewModel.updateLocalCalendar(current.id, name, color)
                editingLocal = null
            },
            onDismiss = { editingLocal = null },
        )
    }
    deletingLocal?.let { id ->
        val current = state.localCalendars.firstOrNull { it.id == id }
        if (current == null) deletingLocal = null
        else DeleteLocalCalendarDialog(current, onConfirm = { viewModel.deleteLocalCalendar(id); deletingLocal = null }, onDismiss = { deletingLocal = null })
    }
    removing?.let { email ->
        // Off by default: the account leaves this app only. On, the access of the app is also taken away from the Google account.
        var revokeEverywhere by remember(email) { mutableStateOf(false) }
        AlertDialog(
            modifier = Modifier.exposeTestTags(),
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_account_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.remove_account_text, email))
                    Row(
                        Modifier.testTag("account_remove_revoke").fillMaxWidth().padding(top = 12.dp).clickable { revokeEverywhere = !revokeEverywhere },
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(checked = revokeEverywhere, onCheckedChange = { revokeEverywhere = it })
                        Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
                            Text(stringResource(R.string.remove_account_revoke), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.remove_account_revoke_hint, email),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(modifier = Modifier.testTag("account_remove_confirm"), onClick = {
                    removing = null
                    viewModel.removeAccount(email, revokeEverywhere)
                }) { Text(stringResource(R.string.remove_account_confirm)) }
            },
            dismissButton = { TextButton(modifier = Modifier.testTag("account_remove_cancel"), onClick = { removing = null }) { Text(stringResource(R.string.remove_account_cancel)) } },
        )
    }
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
            IconButton(onClick = onMenu, modifier = Modifier.testTag("btn_menu")) { Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.open_menu)) }
        },
        title = {
            Row(Modifier.testTag("btn_title").clickable(onClick = onTitle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title)
                Icon(
                    if (miniOpen) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                    contentDescription = stringResource(R.string.go_to_date),
                )
            }
        },
        actions = {
            IconButton(onClick = onSearch, modifier = Modifier.testTag("btn_search")) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
            }
            // Sync now. A badge counts the changes not yet on Google (they wait for a connection when offline).
            IconButton(onClick = onSync, modifier = Modifier.testTag("btn_refresh")) {
                BadgedBox(badge = { if (state.pending > 0) Badge { Text(state.pending.toString()) } }) {
                    if (state.syncing) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                }
            }
            IconButton(onClick = onSettings, modifier = Modifier.testTag("btn_settings")) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
            }
            IconButton(onClick = onToday, modifier = Modifier.testTag("btn_today")) { TodayIcon(LocalDate.now().dayOfMonth) }
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
    onRetryAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onNewLocal: () -> Unit,
    onEditLocal: (String) -> Unit,
    onDeleteLocal: (String) -> Unit,
    onActiveAlarms: () -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    onAbout: () -> Unit,
) {
    LaunchedEffect(state.accounts) {
        Diag.log { "drawer composed accounts=${state.accounts.size} [${state.accounts.joinToString { a -> Diag.who(a.email) + ":" + a.calendars.size }}]" }
    }
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
                    modifier = Modifier.testTag("drawer_view_" + view.name),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                label = { Text(stringResource(R.string.refresh)) },
                selected = false,
                onClick = onRefresh,
                modifier = Modifier.testTag("drawer_refresh"),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Alarm, contentDescription = null) },
                label = { Text(stringResource(R.string.active_alarms)) },
                selected = false,
                onClick = onActiveAlarms,
                modifier = Modifier.testTag("drawer_alarms"),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            state.accounts.forEach { account ->
                Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(account.email)
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(account.email, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Google", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onRemoveAccount(account.email) }, modifier = Modifier.testTag("drawer_account_remove")) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = stringResource(R.string.remove_account_cd, account.email))
                    }
                }
                account.calendars.forEach { CalendarRow(it, onToggleCalendar) }
            }
            LocalCalendarsSection(state.localCalendars, onToggleCalendar, onNewLocal, onEditLocal, onDeleteLocal)
            state.birthdays?.let { birthdays ->
                Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Cake, contentDescription = null)
                    Text(stringResource(R.string.contacts), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                }
                CalendarRow(birthdays, onToggleCalendar)
            }
            // The snackbar below the drawer would be hidden by it: the same news is told here, where the user is looking.
            state.addingAccount?.let { email ->
                Row(Modifier.testTag("drawer_adding").padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.adding_account, email), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.accountTimeout?.let { email ->
                Row(Modifier.testTag("drawer_timeout").padding(16.dp, 4.dp, 4.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.account_timeout, email), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(modifier = Modifier.testTag("drawer_timeout_retry"), onClick = { onRetryAccount(email) }) { Text(stringResource(R.string.retry)) }
                }
            }
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                label = { Text(stringResource(R.string.add_google_account)) },
                selected = false,
                onClick = onAddAccount,
                modifier = Modifier.testTag("drawer_add_account"),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                label = { Text(stringResource(R.string.settings)) },
                selected = false,
                onClick = onSettings,
                modifier = Modifier.testTag("drawer_settings"),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                label = { Text(stringResource(R.string.export_ics)) },
                selected = false,
                onClick = onExport,
                modifier = Modifier.testTag("drawer_export"),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                label = { Text(stringResource(R.string.about)) },
                selected = false,
                onClick = onAbout,
                modifier = Modifier.testTag("drawer_about"),
            )
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
internal fun CalendarRow(calendar: CalendarInfo, onToggle: (String, Boolean) -> Unit, trailing: @Composable (() -> Unit)? = null) {
    val color = parseColor(calendar.colorHex)
    Row(
        Modifier.testTag("calendar_row").fillMaxWidth().clickable { onToggle(calendar.id, !calendar.isVisible) }.padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = calendar.isVisible,
            onCheckedChange = { onToggle(calendar.id, it) },
            colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color),
        )
        // Google names the primary calendar after the account email; show it as Google Calendar does.
        val name = if (calendar.isPrimary && calendar.name == calendar.accountEmail) stringResource(R.string.my_calendar) else calendar.name
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        trailing?.invoke()
    }
}

/** The floating "+": in the month + list view the event starts on the selected day, otherwise at the default time. */
private fun newEventRequest(state: CalendarUiState): EditorRequest.New {
    if (state.view != CalendarView.MONTH_LIST) return EditorRequest.New()
    val now = java.time.LocalDateTime.now()
    val hour = if (state.date == now.toLocalDate()) minOf(now.hour + 1, 23) else 9
    return EditorRequest.New(state.date.atTime(hour, 0))
}

/** A message with two answers: [addLabel] (the snackbar's action) and [closeLabel]. It stays until one is chosen. */
private class AddOrCloseVisuals(override val message: String, val addLabel: String, val closeLabel: String) : SnackbarVisuals {
    override val actionLabel: String get() = addLabel
    override val withDismissAction: Boolean get() = false
    override val duration: SnackbarDuration get() = SnackbarDuration.Indefinite
}
