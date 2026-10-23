package io.github.giansalvo.ggcalendar.core.model

/**
 * Diagnostic lines. They go to two places that are off by default: [sink] (Logcat, set in a "diag" build, `-Pdiag=true`, see
 * app/build.gradle.kts) and [file] (the log file of the Advanced setting "Diagnostic log"). When both are null a call costs two
 * null checks; the message is never built. Written for the side-menu bug (an account that does not show up in the menu) and
 * extended for the reminders of a hidden calendar: the lines say where the data stops being what the user expects.
 */
object Diag {
    @Volatile
    var sink: ((String) -> Unit)? = null

    /** The log file, set while the Diagnostic log option is on. */
    @Volatile
    var file: FileLog? = null

    /** The screen that is in front (set by the UI), and whether the app is in front at all. */
    @Volatile
    var screen: String = "calendar"

    @Volatile
    var foreground: Boolean = false

    /** The "window" column of a line of the file: the screen in front, or `background` (a sync, an alarm without a screen). */
    val window: String get() = if (foreground) screen else BACKGROUND

    const val BACKGROUND = "background"

    inline fun log(message: () -> String) {
        val out = sink
        val log = file
        if (out != null || log != null) {
            val text = message()
            out?.invoke(text)
            log?.write(window, Thread.currentThread().name, text)
        }
    }

    /** A moment as it goes in the log, in the zone of the phone: the date and the time, to the second. */
    fun time(millis: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))

    /** An address as it goes in the log: its first three letters and its domain, enough to tell the test accounts apart. */
    fun who(email: String?): String = if (email == null) "-" else email.substringBefore('@').take(3) + "*@" + email.substringAfter('@', "?")
}
