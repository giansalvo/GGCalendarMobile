package io.github.giansalvo.ggcalendar.feature.calendar

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.ui.graphics.vector.ImageVector

enum class CalendarView(@StringRes val label: Int, val icon: ImageVector) {
    SCHEDULE(R.string.view_schedule, Icons.Outlined.ViewAgenda),
    DAY(R.string.view_day, Icons.Outlined.CalendarViewDay),
    SIX_DAYS(R.string.view_six_days, Icons.Outlined.ViewColumn),
    WEEK(R.string.view_week, Icons.Outlined.CalendarViewWeek),
    MONTH(R.string.view_month, Icons.Outlined.CalendarViewMonth),
}
