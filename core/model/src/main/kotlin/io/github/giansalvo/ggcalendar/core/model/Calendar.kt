package io.github.giansalvo.ggcalendar.core.model

/** A Google account linked to the app. */
data class Account(val email: String)

/** A subscription as the side menu shows it: the calendar and how the last download went (times in epoch milliseconds). */
data class SubscriptionInfo(
    val calendar: CalendarInfo,
    val fetchedAt: Long?,
    val attemptedAt: Long?,
    /** The name of a SubscriptionProblem, or null when the last attempt worked. */
    val error: String?,
)

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
    /** A calendar read from an address, never changed from the app. */
    val subscription: Boolean = false,
) {
    companion object {
        fun googleId(email: String, remoteId: String) = "$email|$remoteId"
    }
}
