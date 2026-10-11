package io.github.giansalvo.ggcalendar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RecurrenceRuleTest {
    private val rome = ZoneId.of("Europe/Rome")

    @Test
    fun parsesAndWritesWeeklyDays() {
        val rule = RecurrenceRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=TH,MO;COUNT=5", rome)!!
        assertEquals(Frequency.WEEKLY, rule.frequency)
        assertEquals(2, rule.interval)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), rule.orderedDays())
        assertEquals(5, rule.count)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;COUNT=5", rule.toRrule(allDay = false, zone = rome))
    }

    @Test
    fun nthWeekdayAndMonthDay() {
        assertEquals(-1 to DayOfWeek.FRIDAY, RecurrenceRule.parse("FREQ=MONTHLY;BYDAY=-1FR")!!.nthWeekday)
        assertEquals(15, RecurrenceRule.parse("FREQ=MONTHLY;BYMONTHDAY=15")!!.monthDay)
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", RecurrenceRule(Frequency.MONTHLY, nthWeekday = 2 to DayOfWeek.TUESDAY).toRrule(false))
        // An n-th weekday makes no sense in a weekly rule.
        assertNull(RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=2TU"))
    }

    @Test
    fun untilIsTheLastLocalDay() {
        val timed = RecurrenceRule(Frequency.DAILY, until = LocalDate.of(2026, 10, 31)).toRrule(allDay = false, zone = rome)
        // 31 October 23:59:59 in Rome (UTC+1 after the DST change) is 22:59:59 UTC.
        assertEquals("FREQ=DAILY;UNTIL=20261031T225959Z", timed)
        assertEquals(LocalDate.of(2026, 10, 31), RecurrenceRule.parse(timed.removePrefix(""), rome)!!.until)
        assertEquals("FREQ=DAILY;UNTIL=20261031", RecurrenceRule(Frequency.DAILY, until = LocalDate.of(2026, 10, 31)).toRrule(allDay = true))
    }

    @Test
    fun unsupportedPartsOrBothEndsAreRejected() {
        assertNull(RecurrenceRule.parse("FREQ=DAILY;BYHOUR=9"))
        assertNull(RecurrenceRule.parse("FREQ=DAILY;COUNT=3;UNTIL=20261031"))
        assertNull(RecurrenceRule.parse("INTERVAL=2"))
        assertNull(RecurrenceRule.parse(null))
    }

    @Test
    fun splittingASeriesReplacesItsEnd() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=3", RecurrenceRule.withCount("FREQ=WEEKLY;UNTIL=20270101;BYDAY=MO", 3))
        assertEquals(
            "FREQ=DAILY;UNTIL=20261018T215959Z",
            RecurrenceRule.withUntil("FREQ=DAILY;COUNT=10", Instant.parse("2026-10-18T21:59:59Z"), allDay = false),
        )
        assertEquals("FREQ=DAILY;UNTIL=20261018", RecurrenceRule.withUntil("FREQ=DAILY", Instant.parse("2026-10-18T10:00:00Z"), allDay = true, zone = rome))
    }
}
