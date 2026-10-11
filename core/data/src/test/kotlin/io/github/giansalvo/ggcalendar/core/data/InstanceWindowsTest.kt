package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.data.InstanceWindows.Scope
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class InstanceWindowsTest {
    private val today = LocalDate.of(2026, 10, 11)
    private val rome = "Europe/Rome"
    private val stored = InstanceWindows.default(today, rome)

    @Test
    fun firstRunComputesEverything() {
        val plan = InstanceWindows.plan(null, today, rome)
        assertEquals(Scope.ALL, plan.scope)
        assertEquals(today.minusDays(365), plan.window.from)
        assertEquals(today.plusDays(730), plan.window.to)
    }

    @Test
    fun sameDayInsideTheWindowChangesNothing() {
        val plan = InstanceWindows.plan(stored, today, rome, today.minusMonths(1), today.plusMonths(4))
        assertEquals(Scope.NONE, plan.scope)
        assertEquals(stored, plan.window)
    }

    @Test
    fun aNewDayMovesTheEndOfTheWindowForSeriesOnly() {
        val plan = InstanceWindows.plan(stored, today.plusDays(1), rome)
        assertEquals(Scope.SERIES, plan.scope)
        assertEquals(stored.from, plan.window.from)
        assertEquals(today.plusDays(731), plan.window.to)
    }

    @Test
    fun browsingBeyondTheWindowExtendsItWithAMargin() {
        val far = today.plusYears(3)
        val plan = InstanceWindows.plan(stored, today, rome, far, far.plusDays(7))
        assertEquals(Scope.SERIES, plan.scope)
        assertEquals(far.plusDays(7 + 180), plan.window.to)
        val back = today.minusYears(2)
        assertEquals(back.minusDays(180), InstanceWindows.plan(stored, today, rome, back, back.plusDays(7)).window.from)
    }

    @Test
    fun aNewTimeZoneRecomputesEverything() {
        val plan = InstanceWindows.plan(stored, today, "America/New_York")
        assertEquals(Scope.ALL, plan.scope)
        assertEquals("America/New_York", plan.window.zoneId)
    }
}
