package io.github.giansalvo.ggcalendar.core.notifications

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
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

private const val SPLASH_MILLIS = 5_000L

/** TEST ONLY: show the splash and the questions at every start, whatever was answered before. Must be false in a release. */
private const val ALWAYS_SHOW_FOR_TESTING = false

/** What the first start can ask for. Each one is a small window of its own, only if Android does not allow it yet. */
private enum class Ask { NOTIFICATIONS, OVERLAY, FULLSCREEN, BATTERY }

/**
 * First start: the logo for five seconds, then one small window for each permission that alarms need and that is still
 * missing (the notifications, the alarm screen over other apps and over the lock screen, the battery), each with one short line and two answers.
 * "Allow" opens Android's own screen (or its question); "Not now" goes on. Whatever the answers, it is not shown again;
 * the same options stay in Settings > Notifications and alarms.
 */
@Composable
fun PermissionsIntro(viewModel: PermissionsIntroViewModel = hiltViewModel()) {
    val seen by viewModel.seen.collectAsState()
    var closed by rememberSaveable { mutableStateOf(false) }
    if (closed || (seen && !ALWAYS_SHOW_FOR_TESTING)) return

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
    // The questions are decided once, when the splash ends: a permission granted in between does not change the list.
    val asks: List<Ask> = rememberSaveable(saver = listSaver<List<Ask>, String>(save = { l -> l.map { it.name } }, restore = { l -> l.map { Ask.valueOf(it) } })) {
        val status = checks.status()
        buildList {
            if (!status.notifications) add(Ask.NOTIFICATIONS)
            if (!status.overlay) add(Ask.OVERLAY)
            if (!status.fullScreen) add(Ask.FULLSCREEN)
            if (!status.batteryUnrestricted) add(Ask.BATTERY)
        }
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val notificationQuestion = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val ask = asks.getOrNull(index)
    if (ask == null) {
        // Nothing (more) to ask: the first start is over.
        LaunchedEffect(Unit) {
            closed = true
            viewModel.done()
        }
        return
    }
    val next = { index += 1 }
    val (title, text) = when (ask) {
        Ask.NOTIFICATIONS -> R.string.perm_notifications to R.string.intro_notifications
        Ask.OVERLAY -> R.string.perm_overlay to R.string.intro_overlay
        Ask.FULLSCREEN -> R.string.perm_fullscreen to R.string.intro_fullscreen
        Ask.BATTERY -> R.string.perm_battery to R.string.intro_battery
    }
    AlertDialog(
        modifier = Modifier.exposeTestTags().testTag("intro_" + ask.name.lowercase()),
        onDismissRequest = next,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("intro_allow"), onClick = {
                when (ask) {
                    Ask.NOTIFICATIONS ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationQuestion.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else context.open(checks.notificationSettings())
                    Ask.OVERLAY -> context.open(checks.overlaySettings())
                    Ask.FULLSCREEN -> context.open(checks.fullScreenSettings())
                    Ask.BATTERY -> context.open(checks.batterySettings())
                }
                next()
            }) { Text(stringResource(R.string.intro_allow)) }
        },
        dismissButton = { TextButton(modifier = Modifier.testTag("intro_skip"), onClick = next) { Text(stringResource(R.string.intro_skip)) } },
    )
}
