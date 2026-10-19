package io.github.giansalvo.ggcalendar.core.notifications

/** Actions and extras shared by the notifications, the receiver and the app's main activity. */
object Reminders {
    const val ACTION_FIRE = "io.github.giansalvo.ggcalendar.REMINDER_FIRE"
    const val ACTION_SNOOZE = "io.github.giansalvo.ggcalendar.REMINDER_SNOOZE"
    const val ACTION_DISMISS = "io.github.giansalvo.ggcalendar.REMINDER_DISMISS"

    const val EXTRA_SNOOZE_MINUTES = "snooze.minutes"

    /** Extras of the intent that opens the app on an occurrence (the tap on a notification). */
    const val EXTRA_OPEN_EVENT = "open.event"
    const val EXTRA_OPEN_BEGIN = "open.begin"

    /** Reminders firing within this time are registered with AlarmManager; a daily worker moves the horizon. */
    const val HORIZON_MILLIS = 48L * 60 * 60 * 1000

    /** Google accepts reminders up to four weeks before an event. */
    const val MAX_MINUTES_BEFORE = io.github.giansalvo.ggcalendar.core.data.ReminderCalculator.MAX_MINUTES_BEFORE

    val SNOOZE_CHOICES = listOf(5, 10, 15, 30, 60)
}
