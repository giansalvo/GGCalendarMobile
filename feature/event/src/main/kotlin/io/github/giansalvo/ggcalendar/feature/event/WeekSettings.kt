package io.github.giansalvo.ggcalendar.feature.event

import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The first day of the week, shared by the views, the mini calendar and the editor. It follows the language of the phone
 * until the user chooses one in the settings; the calendar screen sets it as soon as the choice changes.
 */
object WeekSettings {
    @Volatile
    var firstDay: DayOfWeek = localeDefault()

    fun localeDefault(): DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
}
