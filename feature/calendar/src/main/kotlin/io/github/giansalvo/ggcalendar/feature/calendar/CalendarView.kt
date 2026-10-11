package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.ui.graphics.vector.ImageVector

/** [dayCount]: columns of the time grid views, 0 for the others. */
enum class CalendarView(@StringRes val label: Int, val icon: ImageVector, val dayCount: Int = 0) {
    SCHEDULE(R.string.view_schedule, Icons.Outlined.ViewAgenda),
    DAY(R.string.view_day, Icons.Outlined.CalendarViewDay, 1),
    SIX_DAYS(R.string.view_six_days, Icons.Outlined.ViewColumn, 6),
    WEEK(R.string.view_week, Icons.Outlined.CalendarViewWeek, 7),
    MONTH(R.string.view_month, Icons.Outlined.CalendarViewMonth),
    MONTH_LIST(R.string.view_month_list, Icons.Outlined.CalendarMonth),
}
