package io.github.giansalvo.ggcalendar.core.model

/**
 * Diagnostic lines of a "diag" build (`-Pdiag=true`, see app/build.gradle.kts). In a normal build [sink] stays null and a call
 * costs one null check; the message is never built. Written for the side-menu bug (an account that does not show up in the menu):
 * the lines say where the list of accounts stops being updated, between the database and the screen.
 */
object Diag {
    @Volatile
    var sink: ((String) -> Unit)? = null

    inline fun log(message: () -> String) {
        val out = sink
        if (out != null) out(message())
    }

    /** An address as it goes in the log: its first three letters and its domain, enough to tell the test accounts apart. */
    fun who(email: String?): String = if (email == null) "-" else email.take(3) + "*@" + email.substringAfter('@', "?")
}
