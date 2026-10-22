package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.EventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** U162-U166: which events of a file the days of a subscription keep. */
class SubscriptionRangeTest {
    private val rome = ZoneId.of("Europe/Rome")

    private fun day(text: String) = LocalDate.parse(text)

    private fun range(from: String?, to: String?) = SubscriptionRange(from?.let(::day), to?.let(::day))

    private fun at(text: String) = LocalDateTime.parse(text).atZone(rome).toInstant().toEpochMilli()

    private fun timed(id: String, start: String, end: String, rrule: String? = null, master: String? = null, original: String? = null) = EventEntity(
        id = id, calendarId = "c", startUtc = at(start), endUtc = at(end), timeZone = "Europe/Rome", rrule = rrule,
        seriesMasterId = master, originalStartUtc = original?.let(::at),
    )

    private fun allDay(id: String, first: String, last: String) = EventEntity(
        id = id, calendarId = "c", allDay = true, startDay = day(first).toEpochDay(), endDay = day(last).plusDays(1).toEpochDay(),
    )

    private fun kept(events: List<EventEntity>, r: SubscriptionRange) = DateRangeFilter.keep(events, r, rome).map { it.id }

    // ---- U162: single events

    @Test
    fun `without days nothing is cut`() {
        val all = listOf(timed("a", "2020-01-01T10:00", "2020-01-01T11:00"), timed("b", "2030-01-01T10:00", "2030-01-01T11:00"))
        assertSame(all, DateRangeFilter.keep(all, SubscriptionRange(), rome))
        assertTrue(SubscriptionRange().isUnlimited)
    }

    @Test
    fun `an event is kept when one of its days is inside, both ends included`() {
        val events = listOf(
            timed("before", "2026-10-09T10:00", "2026-10-09T11:00"),
            timed("first", "2026-10-10T00:00", "2026-10-10T01:00"),
            timed("middle", "2026-10-15T10:00", "2026-10-15T11:00"),
            timed("last", "2026-10-20T23:00", "2026-10-20T23:59"),
            timed("after", "2026-10-21T00:00", "2026-10-21T01:00"),
        )
        assertEquals(listOf("first", "middle", "last"), kept(events, range("2026-10-10", "2026-10-20")))
    }

    @Test
    fun `an empty side has no limit`() {
        val events = listOf(
            timed("old", "2019-05-01T10:00", "2019-05-01T11:00"),
            timed("now", "2026-10-15T10:00", "2026-10-15T11:00"),
            timed("far", "2031-05-01T10:00", "2031-05-01T11:00"),
        )
        assertEquals(listOf("now", "far"), kept(events, range("2026-10-01", null)))
        assertEquals(listOf("old", "now"), kept(events, range(null, "2026-10-31")))
    }

    @Test
    fun `an event that crosses a border is kept`() {
        val night = timed("night", "2026-10-09T22:00", "2026-10-10T02:00") // starts the day before, ends inside
        val allWeek = allDay("week", "2026-10-05", "2026-10-11") // five days before, one inside
        val after = allDay("after", "2026-10-21", "2026-10-23")
        val r = range("2026-10-10", "2026-10-20")
        assertEquals(listOf("night", "week"), kept(listOf(night, allWeek, after), r))
    }

    @Test
    fun `the days are those of the zone of the phone`() {
        // 23:30 in Rome on the 20th is still the 20th, though it is 21:30 UTC; 00:30 of the 21st is out.
        val inside = timed("in", "2026-10-20T23:30", "2026-10-20T23:45")
        val outside = timed("out", "2026-10-21T00:30", "2026-10-21T00:45")
        assertEquals(listOf("in"), kept(listOf(inside, outside), range(null, "2026-10-20")))
    }

    @Test
    fun `a range with the last day before the first is not valid`() {
        assertFalse(range("2026-10-20", "2026-10-10").isValid)
        assertTrue(range("2026-10-10", "2026-10-10").isValid)
        assertTrue(range(null, "2026-10-10").isValid)
    }

    // ---- U163: series

    @Test
    fun `a series that began before the first day and goes on is kept whole`() {
        val daily = timed("s", "2026-09-01T09:00", "2026-09-01T10:00", rrule = "FREQ=DAILY")
        assertEquals(listOf("s"), kept(listOf(daily), range("2026-10-10", "2026-10-20")))
        assertEquals(listOf("s"), kept(listOf(daily), range("2026-10-10", null)))
    }

    @Test
    fun `a series that ended before the first day or begins after the last is dropped`() {
        val ended = timed("ended", "2026-09-01T09:00", "2026-09-01T10:00", rrule = "FREQ=DAILY;COUNT=10") // up to 10 September
        val later = timed("later", "2026-11-01T09:00", "2026-11-01T10:00", rrule = "FREQ=DAILY")
        val r = range("2026-10-10", "2026-10-20")
        assertEquals(emptyList<String>(), kept(listOf(ended, later), r))
    }

    @Test
    fun `a series with only an occurrence inside the range is kept`() {
        val monthly = timed("m", "2026-01-15T09:00", "2026-01-15T10:00", rrule = "FREQ=MONTHLY") // the 15th
        assertEquals(listOf("m"), kept(listOf(monthly), range("2026-10-10", "2026-10-20")))
        assertEquals(emptyList<String>(), kept(listOf(monthly), range("2026-10-16", "2026-11-14")))
    }

    @Test
    fun `the modified occurrences follow their series`() {
        val master = timed("s", "2026-10-05T09:00", "2026-10-05T10:00", rrule = "FREQ=WEEKLY;COUNT=3") // 5, 12, 19 October
        val moved = timed("s-ex", "2026-10-13T09:00", "2026-10-13T10:00", master = "s", original = "2026-10-12T09:00")
        assertEquals(listOf("s", "s-ex"), kept(listOf(master, moved), range("2026-10-18", "2026-10-31")))
        // out of the range, the master and its exception go together
        assertEquals(emptyList<String>(), kept(listOf(master, moved), range("2026-11-01", null)))
    }

    @Test
    fun `an occurrence moved into the range keeps a series whose own days are outside`() {
        val master = timed("s", "2026-09-07T09:00", "2026-09-07T10:00", rrule = "FREQ=WEEKLY;COUNT=2") // 7 and 14 September
        val moved = timed("s-ex", "2026-10-13T09:00", "2026-10-13T10:00", master = "s", original = "2026-09-14T09:00")
        assertEquals(listOf("s", "s-ex"), kept(listOf(master, moved), range("2026-10-10", "2026-10-20")))
    }

    @Test
    fun `an all-day series is cut by its days`() {
        val birthday = EventEntity(
            id = "b", calendarId = "c", allDay = true, rrule = "FREQ=YEARLY",
            startDay = day("1990-10-15").toEpochDay(), endDay = day("1990-10-16").toEpochDay(),
        )
        assertEquals(listOf("b"), kept(listOf(birthday), range("2026-10-10", "2026-10-20")))
        assertEquals(emptyList<String>(), kept(listOf(birthday), range("2026-10-16", "2027-10-14")))
    }

    // ---- U164: the occurrences without their series (the public feeds of Google)

    @Test
    fun `an occurrence without its series is an event like any other`() {
        val a = timed("occ-1", "2026-10-12T09:00", "2026-10-12T10:00")
        val b = timed("occ-2", "2026-10-26T09:00", "2026-10-26T10:00")
        assertEquals(listOf("occ-1"), kept(listOf(a, b), range("2026-10-10", "2026-10-20")))
    }

    // ---- U165: what the drawing is cut with

    @Test
    fun `the instances of a series are cut to the days`() {
        val daily = timed("s", "2026-10-01T09:00", "2026-10-01T10:00", rrule = "FREQ=DAILY;COUNT=30")
        val window = RecurrenceExpander.Window(LocalDate.of(2026, 9, 1).atStartOfDay(rome).toInstant(), LocalDate.of(2026, 12, 1).atStartOfDay(rome).toInstant())
        val all = RecurrenceExpander.expand(daily, emptyList(), window, rome)
        val r = range("2026-10-10", "2026-10-12")
        val cut = DateRangeFilter.clip(all, r, rome)
        assertEquals(30, all.size)
        assertEquals(listOf(day("2026-10-10"), day("2026-10-11"), day("2026-10-12")).map { it.toEpochDay() }, cut.map { it.startDay })
        assertSame(all, DateRangeFilter.clip(all, SubscriptionRange(), rome))
    }

    @Test
    fun `an all-day instance with an exclusive end is cut by its last day`() {
        val e = allDay("a", "2026-10-08", "2026-10-09")
        val instance = RecurrenceExpander.single(e, rome)!!
        assertFalse(DateRangeFilter.intersects(instance, range("2026-10-10", null), rome))
        assertTrue(DateRangeFilter.intersects(instance, range("2026-10-09", null), rome))
        assertFalse(DateRangeFilter.intersects(instance, range(null, "2026-10-07"), rome))
    }
}
