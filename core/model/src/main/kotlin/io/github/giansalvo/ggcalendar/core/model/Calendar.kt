package io.github.giansalvo.ggcalendar.core.model

/** A Google account linked to the app. */
data class Account(val email: String)

/**
 * A calendar shown in the side menu. Google calendars have id "{email}|{googleCalendarId}", as in the desktop app;
 * local calendars (milestone M6) will have [accountEmail] null.
 */
data class CalendarInfo(
    val id: String,
    val accountEmail: String?,
    val name: String,
    val colorHex: String,
    val isVisible: Boolean = true,
    val canWrite: Boolean = true,
    val isPrimary: Boolean = false,
    /** A calendar kept on this phone only (no account, nothing sent to Google). */
    val local: Boolean = false,
) {
    companion object {
        fun googleId(email: String, remoteId: String) = "$email|$remoteId"
    }
}
