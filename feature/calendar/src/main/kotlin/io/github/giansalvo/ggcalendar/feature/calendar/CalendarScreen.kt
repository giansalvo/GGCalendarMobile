package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarScreen(viewModel: CalendarViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

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
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
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
                onToggleCalendar = viewModel::toggleCalendar,
                onAddAccount = { pickAccount.launch(viewModel.chooseAccountIntent()) },
            )
        },
    ) {
        Scaffold(
            topBar = {
                CalendarTopBar(
                    state = state,
                    onMenu = { scope.launch { drawerState.open() } },
                    onToday = viewModel::goToToday,
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { /* Editor: milestone M3 */ }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_event))
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.view_placeholder, stringResource(state.view.label)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTopBar(state: CalendarUiState, onMenu: () -> Unit, onToday: () -> Unit) {
    val month = state.date.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.open_menu)) }
        },
        title = {
            // Tapping the month will open the month picker (milestone M1).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(month)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        },
        actions = {
            IconButton(onClick = { /* Search: later milestone */ }) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
            }
            IconButton(onClick = onToday) { TodayIcon(java.time.LocalDate.now().dayOfMonth) }
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
    onToggleCalendar: (String) -> Unit,
    onAddAccount: () -> Unit,
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
                onClick = { /* Sync: milestone M1 */ },
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
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                label = { Text(stringResource(R.string.add_google_account)) },
                selected = false,
                onClick = onAddAccount,
            )
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun CalendarRow(calendar: CalendarInfo, onToggle: (String) -> Unit) {
    val color = parseColor(calendar.colorHex)
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(calendar.id) }.padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Checkbox(
            checked = calendar.isVisible,
            onCheckedChange = { onToggle(calendar.id) },
            colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color),
        )
        Text(calendar.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF1A73E8))
