package io.github.giansalvo.ggcalendar.core.notifications

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PermissionsIntroViewModel @Inject constructor(private val prefs: AppPreferences) : ViewModel() {
    /** True until the stored answer is read, so that the screen never flashes for someone who has already seen it. */
    val seen: StateFlow<Boolean> = prefs.permissionsIntroSeen.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun done() {
        viewModelScope.launch { prefs.setPermissionsIntroSeen() }
    }
}

private const val SPLASH_MILLIS = 3_000L

/**
 * Shown once, at the first start: the logo for three seconds, then the three permissions that alarms need and that Android does not ask for
 * with a dialog (the alarm screen over other apps, the battery, the notifications). Each row opens Android's own screen;
 * the same rows stay in Settings > Notifications and alarms. Closing it in any way counts as seen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsIntro(viewModel: PermissionsIntroViewModel = hiltViewModel()) {
    val seen by viewModel.seen.collectAsState()
    if (seen) return
    // First start: the logo for three seconds, then the permissions window over the calendar.
    var splash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(SPLASH_MILLIS)
        splash = false
    }
    if (splash) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            // The logo has a light grey background of its own: the screen takes the same grey.
            Box(Modifier.exposeTestTags().testTag("splash_logo").fillMaxSize().background(Color(0xFFE1E1E1)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.logo_splash), contentDescription = null, modifier = Modifier.fillMaxWidth(0.6f), contentScale = ContentScale.Fit)
            }
        }
        return
    }
    val context = LocalContext.current
    val checks = remember { NotificationChecks(context) }
    var status by remember { mutableStateOf(checks.status()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = checks.status() }

    ModalBottomSheet(onDismissRequest = viewModel::done, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.exposeTestTags().testTag("sheet_permissions_intro").fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.intro_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.intro_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            PermissionRow(stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_help), status.notifications) {
                context.open(checks.notificationSettings())
            }
            PermissionRow(stringResource(R.string.perm_overlay), stringResource(R.string.perm_overlay_help), status.overlay) {
                context.open(checks.overlaySettings())
            }
            PermissionRow(stringResource(R.string.perm_battery), stringResource(R.string.perm_battery_help), status.batteryUnrestricted) {
                context.open(if (status.batteryUnrestricted) checks.batteryList() else checks.batterySettings())
            }
            Text(stringResource(R.string.samsung_sleeping), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.intro_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = viewModel::done, modifier = Modifier.testTag("intro_done").fillMaxWidth()) { Text(stringResource(R.string.intro_done)) }
        }
    }
}
