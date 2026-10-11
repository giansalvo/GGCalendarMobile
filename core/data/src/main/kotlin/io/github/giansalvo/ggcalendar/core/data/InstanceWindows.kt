package io.github.giansalvo.ggcalendar.core.data

import java.time.LocalDate

/**
 * Decides when the `instances` table must be rebuilt. Single events have their instance whatever the window; series
 * are expanded only inside it. The day columns depend on the time zone, so a new zone rebuilds everything.
 */
object InstanceWindows {
    enum class Scope { NONE, SERIES, ALL }

    data class Plan(val window: InstanceWindow, val scope: Scope)

    /** Margin added around a requested period that falls outside the window, so that browsing on extends it rarely. */
    private const val MARGIN_DAYS = 180L

    fun default(today: LocalDate, zoneId: String) = InstanceWindow(today.minusDays(365), today.plusDays(730), zoneId, today)

    /**
     * The window to use given the [stored] one, today, the device zone and optionally a period [from, to) that a view
     * wants to show. The window only grows (at least one year back and two ahead of today), so the instances already
     * computed stay valid.
     */
    fun plan(stored: InstanceWindow?, today: LocalDate, zoneId: String, from: LocalDate? = null, to: LocalDate? = null): Plan {
        val base = default(today, zoneId)
        if (stored == null || stored.zoneId != zoneId) return Plan(base.extendedTo(from, to), Scope.ALL)
        val window = stored
            .copy(from = minOf(stored.from, base.from), to = maxOf(stored.to, base.to), computedOn = today)
            .extendedTo(from, to)
        val moved = window.from != stored.from || window.to != stored.to
        return Plan(window, if (moved) Scope.SERIES else Scope.NONE)
    }

    private fun InstanceWindow.extendedTo(from: LocalDate?, to: LocalDate?): InstanceWindow = copy(
        from = if (from != null && from < this.from) from.minusDays(MARGIN_DAYS) else this.from,
        to = if (to != null && to > this.to) to.plusDays(MARGIN_DAYS) else this.to,
    )
}
