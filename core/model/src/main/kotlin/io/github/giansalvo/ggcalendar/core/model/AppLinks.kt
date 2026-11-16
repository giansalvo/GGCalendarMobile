package io.github.giansalvo.ggcalendar.core.model

/** What the home screen widgets ask the main activity (they do not depend on the app module, so the names live here). */
object AppLinks {

    /** Open the detail of an occurrence: the event id and the begin instant (epoch milliseconds). Same extras as a tapped reminder. */
    const val EXTRA_OPEN_EVENT = "open.event"
    const val EXTRA_OPEN_BEGIN = "open.begin"

    /** Open the editor on a new event; the value is the day (epoch day) it starts on, or -1 for the default day. */
    const val EXTRA_NEW_EVENT_DAY = "open.new_event_day"

    /** Open the day view of the app on a day; the value is the day (epoch day). The tap on an event of the month widget. */
    const val EXTRA_OPEN_DAY = "open.day"
}
