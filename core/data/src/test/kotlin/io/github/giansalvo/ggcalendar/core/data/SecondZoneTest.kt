package io.github.giansalvo.ggcalendar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** U175-U186: the hours of the second time zone of the grids, line by line. */
class SecondZoneTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val newYork = ZoneId.of("America/New_York")

    private fun rows(day: String, second: ZoneId) = SecondZones.rows(LocalDate.parse(day), rome, second)

    private fun List<ZoneRow>.times() = map { it.time.toString() }

    @Test fun `U175 summer, Rome to Tokyo is seven hours ahead`() {
        val r = rows("2026-07-15", tokyo)
        assertEquals(24, r.size)
        assertEquals("07:00", r[0].time.toString())
        assertEquals("19:00", r[12].time.toString())
        assertEquals(LocalDate.parse("2026-07-15"), r[0].date)
        assertEquals(LocalDate.parse("2026-07-16"), r[17].date) // 17:00 in Rome is 00:00 of the next day there
    }

    @Test fun `U176 half hours, Rome winter to India`() {
        val r = rows("2026-01-15", ZoneId.of("Asia/Kolkata"))
        assertEquals("04:30", r[0].time.toString())
        assertEquals("05:30", r[1].time.toString())
        assertEquals("03:30", r[23].time.toString())
    }

    @Test fun `U177 quarters of an hour, Nepal`() {
        val r = rows("2026-01-15", ZoneId.of("Asia/Kathmandu"))
        assertEquals("04:45", r[0].time.toString())
        assertEquals("05:45", r[1].time.toString())
    }

    @Test fun `U178 the day the phone moves its clock forward, the lines follow the real time`() {
        // Rome 2026-03-29: 02:00 does not exist, 03:00 is the next hour. Tokyo does not change.
        val r = rows("2026-03-29", tokyo)
        assertEquals("08:00", r[0].time.toString()) // 00:00 CET is 23:00Z, 08:00 in Tokyo
        assertEquals("09:00", r[1].time.toString())
        assertEquals("10:00", r[2].time.toString()) // the line of 02:00 is the first valid instant, 03:00 CEST
        assertEquals("10:00", r[3].time.toString())
        assertEquals("11:00", r[4].time.toString())
        assertEquals("06:00", r[23].time.toString()) // 23:00 CEST is 21:00Z, 06:00 of the next day in Tokyo
    }

    @Test fun `U179 the two zones change their clocks on different days`() {
        // New York is on summer time from 8 March, Rome from 29 March: in between the gap is 5 hours, not 6.
        assertEquals("18:00", rows("2026-03-01", newYork)[0].time.toString())
        assertEquals("19:00", rows("2026-03-10", newYork)[0].time.toString())
        assertEquals("18:00", rows("2026-04-10", newYork)[0].time.toString()) // both on summer time again: 6 hours
    }

    @Test fun `U180 the day Rome changes inside the week, the hours after the change are those of that day`() {
        // Rome 2026-03-29, New York already on summer time: 00:00 is 19:00 (CET, 5 hours); from 03:00 CEST the gap is 6 hours again.
        val r = rows("2026-03-29", newYork)
        assertEquals("19:00", r[0].time.toString())
        assertEquals("21:00", r[3].time.toString()) // 03:00 CEST = 01:00Z = 21:00 EDT: two real hours after the first line, three lines later
        assertEquals("22:00", r[4].time.toString())
    }

    @Test fun `U181 the second zone changes its clock in the middle of the day, an hour is repeated`() {
        // New York 2026-11-01 02:00 EDT becomes 01:00 EST at 06:00Z; Rome (CET) is at 07:00. The lines of 06:00 and 07:00 both read 01:00.
        val r = rows("2026-11-01", newYork)
        assertEquals("19:00", r[0].time.toString())
        assertEquals("01:00", r[6].time.toString())
        assertEquals("01:00", r[7].time.toString())
        assertEquals("02:00", r[8].time.toString())
    }

    @Test fun `U182 a day is written on the first line and where the date changes`() {
        val r = rows("2026-01-15", newYork) // 6 hours behind
        assertEquals(LocalDate.parse("2026-01-14"), r[0].date)
        val marked = r.withIndex().filter { it.value.showDate }.map { it.index }
        assertEquals(listOf(0, 6), marked)
        assertEquals(LocalDate.parse("2026-01-15"), r[6].date)
        assertEquals(LocalTime.MIDNIGHT, r[6].time)
    }

    @Test fun `U183 two days have the same hours or not`() {
        assertTrue(SecondZones.sameHours(rows("2026-03-02", newYork), rows("2026-03-06", newYork)))
        assertFalse(SecondZones.sameHours(rows("2026-03-02", newYork), rows("2026-03-10", newYork)))
        assertFalse(SecondZones.sameHours(rows("2026-03-27", newYork), rows("2026-03-29", newYork)))
        assertFalse(SecondZones.sameHours(rows("2026-10-30", newYork), rows("2026-11-02", newYork)))
    }

    @Test fun `U184 the name of the column is cut to six characters as people see them`() {
        assertEquals("abcdef", SecondZones.clampLabel("  abcdefghi "))
        assertEquals("Tokyo", SecondZones.clampLabel("Tokyo"))
        assertEquals("", SecondZones.clampLabel("   "))
        val emoji = "\uD83D\uDE00"
        assertEquals(emoji.repeat(6), SecondZones.clampLabel(emoji.repeat(9)))
    }

    @Test fun `U185 the name offered first`() {
        assertEquals("Tokyo", SecondZones.defaultLabel("Asia/Tokyo"))
        assertEquals("NY", SecondZones.defaultLabel("America/New_York"))
        assertEquals("LA", SecondZones.defaultLabel("America/Los_Angeles"))
        assertEquals("BA", SecondZones.defaultLabel("America/Argentina/Buenos_Aires"))
        assertEquals("Kolkat", SecondZones.defaultLabel("Asia/Kolkata"))
        assertEquals("London", SecondZones.defaultLabel("Europe/London"))
        assertEquals("Tokyo", SecondZones.labelOf("Asia/Tokyo", "  "))
        assertEquals("Tokyo", SecondZones.labelOf("Asia/Tokyo", null))
        assertEquals("Casa", SecondZones.labelOf("Asia/Tokyo", "Casa"))
        assertEquals("abcdef", SecondZones.labelOf("Asia/Tokyo", "abcdefgh"))
    }

    @Test fun `U186 the zone to draw is null when off, unknown or the same as the phone`() {
        val on = ViewPreferences(secondZoneEnabled = true, secondZoneId = "Asia/Tokyo", secondZoneLabel = "Tk")
        assertEquals(SecondZone("Asia/Tokyo", "Tk"), SecondZones.from(on, rome))
        assertEquals("Tokyo", SecondZones.from(on.copy(secondZoneLabel = null), rome)?.label)
        assertNull(SecondZones.from(on.copy(secondZoneEnabled = false), rome))
        assertNull(SecondZones.from(on.copy(secondZoneId = null), rome))
        assertNull(SecondZones.from(on.copy(secondZoneId = "Nowhere/Land"), rome))
        assertNull(SecondZones.from(on.copy(secondZoneId = "Europe/Rome"), rome))
        assertNotNull(SecondZones.from(on, ZoneId.of("Europe/Berlin")))
    }

    @Test fun `U187 two clock changes in opposite directions in one week, two hours of difference between the days`() {
        // Sunday 29 March 2026 Rome moves forward (CET to CEST); Saturday 4 April Norfolk Island moves back (UTC+12 to UTC+11) at 15:00Z.
        // Week Sunday 29 March - Saturday 4 April: the gap between the two columns goes from 10 hours to 9 and then to 8.
        val norfolk = ZoneId.of("Pacific/Norfolk")
        val before = rows("2026-03-28", norfolk) // Rome CET (+1), Norfolk +12: 00:00 is 11:00
        val rome = rows("2026-03-30", norfolk) // Rome CEST (+2), Norfolk +12: 00:00 is 10:00
        val saturday = rows("2026-04-04", norfolk)
        assertEquals("11:00", before[0].time.toString())
        assertEquals("10:00", rome[0].time.toString())
        assertEquals("10:00", saturday[0].time.toString())
        // the lines up to 16:00 (14:00Z) are still at +12; the line of 17:00 (15:00Z) is at +11 and reads the same hour again
        assertEquals("02:00", saturday[16].time.toString())
        assertEquals("02:00", saturday[17].time.toString())
        assertEquals("03:00", saturday[18].time.toString())
        assertFalse(SecondZones.sameHours(rome, saturday))
        assertFalse(SecondZones.sameHours(before, rome))
        // 23:00 on the Saturday is 21:00Z, 08:00 on the Sunday with +11: eight hours of difference instead of ten
        assertEquals("08:00", saturday[23].time.toString())
        assertEquals(LocalDate.parse("2026-04-05"), saturday[23].date)
    }
}
