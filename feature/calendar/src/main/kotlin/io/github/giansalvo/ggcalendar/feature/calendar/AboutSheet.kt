package io.github.giansalvo.ggcalendar.feature.calendar

import io.github.giansalvo.ggcalendar.core.notifications.exposeTestTags
import androidx.compose.ui.platform.testTag
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val WEBSITE = "https://profgusinu.altervista.org/GGCalendarAndroid/"
private const val SOURCE = "https://github.com/giansalvo/GGCalendarMobile"

/** Name and date of the installed build: the version comes from the package, the date from the app manifest (set by Gradle at build time). */
private data class BuildInfo(val version: String, val builtOn: LocalDate?)

private fun buildInfo(context: Context): BuildInfo {
    val pm = context.packageManager
    val info = pm.getPackageInfo(context.packageName, 0)
    val meta = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    return BuildInfo(info.versionName.orEmpty(), meta?.getString("io.github.giansalvo.ggcalendar.BUILD_DATE")?.let { runCatching { LocalDate.parse(it) }.getOrNull() })
}

/** "Information" card of the app: author, website, version and build date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info = remember { buildInfo(context) }
    val uri = LocalUriHandler.current
    val date = info.builtOn?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: "-"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.exposeTestTags().testTag("sheet_about").fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.app_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            InfoRow(stringResource(R.string.about_author), "Giansalvo Gusinu")
            InfoRow(stringResource(R.string.about_website), WEBSITE, link = { uri.openUri(WEBSITE) })
            InfoRow(stringResource(R.string.about_version), info.version)
            InfoRow(stringResource(R.string.about_build_date), date)
            InfoRow(stringResource(R.string.about_license), "GPL-3.0")
            InfoRow(stringResource(R.string.about_source), SOURCE, link = { uri.openUri(SOURCE) })
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, link: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (link != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = if (link != null) Modifier.clickable(onClick = link) else Modifier,
        )
    }
}
