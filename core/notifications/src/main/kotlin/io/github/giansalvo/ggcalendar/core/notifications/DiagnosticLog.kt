package io.github.giansalvo.ggcalendar.core.notifications

import android.app.AlarmManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.model.Diag
import io.github.giansalvo.ggcalendar.core.model.FileLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The diagnostic log of the Advanced settings: while it is on, every [Diag] line goes to a file of the device protected storage (the
 * only storage open before the first unlock, so the reminders of a restart are logged too). The switch is a marker file in that same
 * folder: readable at once, in any process, with no DataStore. Turning it off deletes the log. The log is sent by mail only when the
 * user asks for it (the menu entry), as an attachment.
 */
@Singleton
class DiagnosticLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: GGCalendarDatabase,
) {
    val enabled: StateFlow<Boolean> get() = enabledFlow

    fun isEnabled(): Boolean = marker(context).exists()

    /** Turns the log on (the file starts with the state of the phone and of the calendars) or off (the file is deleted). */
    suspend fun setEnabled(on: Boolean) {
        if (on == isEnabled()) return
        if (on) {
            dir(context).mkdirs()
            marker(context).createNewFile()
            install(context)
            logState("log turned on")
        } else {
            val log = Diag.file ?: FileLog(dir(context))
            marker(context).delete()
            Diag.file = null
            enabledFlow.value = false
            log.clear()
        }
    }

    /** The state the lines that follow make sense in: the phone, the app, the permissions and every calendar with its visibility. */
    suspend fun logState(reason: String) {
        if (Diag.file == null) return
        val (versionName, code) = version()
        val alarms = context.getSystemService(AlarmManager::class.java)
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarms.canScheduleExactAlarms() else true
        val battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        val calendars = runCatching { db.calendarDao().observeAll().first() }.getOrNull().orEmpty()
        Diag.log { "state ($reason): ${deviceLine(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, versionName, code)}" }
        Diag.log {
            "state: zone=${java.util.TimeZone.getDefault().id} locale=${Locale.getDefault().toLanguageTag()} " +
                "notifications=${NotificationManagerCompat.from(context).areNotificationsEnabled()} exactAlarms=$exact ignoringBatteryOptimizations=$battery"
        }
        Diag.log {
            "state: ${calendars.size} calendars: " +
                calendars.joinToString("; ") { "'${it.name}' (${it.kind}, ${Diag.who(it.accountEmail)}) ${if (it.isVisible) "visible" else "HIDDEN"}" }
        }
    }

    /** Writes the whole log to a file of the cache (the system sees it only through the FileProvider) and returns the mail to send. */
    fun mailIntent(activity: Context): Intent {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".share", attachment())
        val (name, code) = version()
        return mailIntentFor(uri, subject(name, code), activity.getString(R.string.log_mail_body))
    }

    /**
     * The log, the older file first, as one file in the cache that is shared. Its name has the moment it was made
     * (`ggcalendar_20261023-115030_1.0.3-16.log`); the file of a previous mail is removed.
     */
    fun attachment(now: Long = System.currentTimeMillis()): File {
        val share = File(context.cacheDir, "share").apply { mkdirs() }
        share.listFiles { f -> f.name.startsWith("ggcalendar_") && f.name.endsWith(".log") || f.name == "ggcalendar-log.txt" }?.forEach { it.delete() }
        val out = File(share, suggestedFileName(now))
        (Diag.file ?: FileLog(dir(context))).copyTo(out)
        return out
    }

    /** Copies the log into the file the user chose with the system file picker. Returns false when it could not be written. */
    fun saveTo(target: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(target)!!.use { o -> attachment().inputStream().use { it.copyTo(o) } }
        true
    }.getOrDefault(false)

    fun version(): Pair<String, Long> {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val code = if (info == null) 0L else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return (info?.versionName ?: "?") to code
    }

    /** `ggcalendar_yyyymmdd-hhmmss_<version>-<build>.log`: the moment of the export in the zone of the phone (no colons: Android files cannot have them). */
    fun suggestedFileName(now: Long = System.currentTimeMillis()): String =
        version().let { (name, code) -> fileName(now, java.time.ZoneId.systemDefault(), name, code) }

    companion object {
        const val ADDRESS = "profgusinu+ggcalendar@gmail.com"
        const val CONTACT_PAGE = "https://profgusinu.altervista.org/GGCalendarAndroid/index.html#contatti"
        private val enabledFlow = MutableStateFlow(false)

        fun fileName(millis: Long, zone: java.time.ZoneId, versionName: String, versionCode: Long): String {
            val moment = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.Instant.ofEpochMilli(millis).atZone(zone))
            // Only letters, digits, dot, dash and underscore: the name must be valid on any storage (no colons, no question marks).
            return "ggcalendar_${moment}_$versionName-$versionCode.log".replace(Regex("[^A-Za-z0-9._-]"), "_")
        }

        fun subject(versionName: String, versionCode: Long) = "GGCalendar Android: $versionName ($versionCode)"

        /**
         * A new message to the author with the log attached. It is an `ACTION_SEND` (the form mail apps read attachments from: with a plain
         * `ACTION_SENDTO` Gmail on a Samsung A56 opened the message and dropped the file) whose *selector* is `mailto:` (and with no MIME type of its own: a type on the main intent makes the system look for a mail app that
         * accepts it, and none does), so that only mail
         * apps are offered (a bare `ACTION_SEND` of a text file also lists Drive and the like). Nothing is sent from here: the user reads
         * the message and sends it.
         */
        fun mailIntentFor(attachment: Uri, subject: String, body: String): Intent =
            Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, body)
                .putExtra(Intent.EXTRA_STREAM, attachment)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .also {
                    it.clipData = ClipData.newRawUri("log", attachment)
                    it.selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                }

        fun deviceLine(manufacturer: String, model: String, release: String, sdk: Int, versionName: String, versionCode: Long) =
            "$manufacturer $model, Android $release (API $sdk), GGCalendar $versionName ($versionCode)"

        fun dir(context: Context): File = File(context.createDeviceProtectedStorageContext().filesDir, "log")

        private fun marker(context: Context) = File(dir(context), "log.on")

        /** At the start of the process, before anything else is logged: the file is the sink again if the log was left on. */
        fun install(context: Context) {
            val on = marker(context).exists()
            enabledFlow.value = on
            if (on) {
                if (Diag.file == null) Diag.file = FileLog(dir(context))
            } else {
                Diag.file = null
            }
        }
    }
}
