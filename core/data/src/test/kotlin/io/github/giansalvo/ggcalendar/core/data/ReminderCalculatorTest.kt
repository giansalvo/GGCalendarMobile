package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.ReminderRow
import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.Reminder
import io.github.giansalvo.ggcalendar.core.model.ReminderMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReminderCalculatorTest {
    private val start = 1_791_448_200_000L // 2026-10-08 10:30 in Rome
    private val minute = 60_000L

    private fun row(
        vararg reminders: Reminder,
        id: String = "1",
        begin: Long = start,
        end: Long = start + 60 * minute,
        own: Boolean = true,
        defaults: List<Reminder> = emptyList(),
        hasAlarm: Boolean = false,
        uid: String? = null,
        account: String = "me@x.com",
        organizer: String? = null,
        attendees: List<Attendee> = emptyList(),
    ) = ReminderRow(
        eventId = id, beginUtc = begin, endUtc = end, allDay = false, title = "T", location = "", description = "", meetLink = null,
        iCalUid = uid, reminders = if (own) reminders.toList() else null, hasAlarm = hasAlarm, organizerEmail = organizer,
        attendees = attendees, defaultReminders = defaults, accountEmail = account, calendarRemoteId = account,
    )

    private fun popup(m: Int) = Reminder(ReminderMethod.POPUP, m)
    private fun alarm(m: Int) = Reminder(ReminderMethod.ALARM, m)
    private fun email(m: Int) = Reminder(ReminderMethod.EMAIL, m)

    @Test
    fun returnsThePopupReminderInsideTheWindow() {
        val due = ReminderCalculator.due(listOf(row(popup(30))), start - 31 * minute, start - 29 * minute)
        assertEquals(1, due.size)
        assertEquals(start - 30 * minute, due[0].fireAt)
        assertEquals("1|$start|POPUP|30", due[0].key)
    }

    @Test
    fun ignoresEmailAndOutOfWindowReminders() {
        assertTrue(ReminderCalculator.due(listOf(row(email(30), popup(5))), start - 31 * minute, start - 29 * minute).isEmpty())
    }

    @Test
    fun windowExcludesItsStartAndIncludesItsEnd() {
        val r = listOf(row(popup(10)))
        assertTrue(ReminderCalculator.due(r, start - 10 * minute, start).isEmpty())
        assertEquals(1, ReminderCalculator.due(r, start - 11 * minute, start - 10 * minute).size)
    }

    @Test
    fun endedEventsAreSkipped() {
        // A reminder after the start ("missed" while the phone was off) only counts while the event is on.
        val r = listOf(row(popup(0)))
        assertEquals(1, ReminderCalculator.due(r, start - minute, start + 30 * minute).size)
        assertTrue(ReminderCalculator.due(r, start + 60 * minute, start + 90 * minute).isEmpty())
    }

    @Test
    fun calendarDefaultsApplyWhenTheEventHasNoReminders() {
        val r = row(own = false, defaults = listOf(popup(10), email(60)))
        assertEquals(listOf(popup(10)), ReminderCalculator.remindersOf(r))
        assertEquals(listOf(alarm(10)), ReminderCalculator.remindersOf(r.copy(hasAlarm = true)))
        // An explicit empty list means no reminders at all.
        assertTrue(ReminderCalculator.remindersOf(row(defaults = listOf(popup(10)))).isEmpty())
    }

    @Test
    fun alarmsKeepTheirMethod() {
        val due = ReminderCalculator.due(listOf(row(alarm(15), popup(5))), start - 60 * minute, start)
        assertEquals(listOf(ReminderMethod.ALARM, ReminderMethod.POPUP), due.map { it.reminder.method })
    }

    @Test
    fun theSameOccurrenceInTwoAccountsFiresOnce() {
        val mine = row(popup(10), id = "a", uid = "u1", account = "me@x.com", organizer = "me@x.com")
        val invite = row(alarm(10), id = "b", uid = "u1", account = "other@y.com", organizer = "me@x.com")
        val due = ReminderCalculator.due(listOf(mine, invite), start - 60 * minute, start)
        assertEquals(1, due.size)
        assertEquals("b", due[0].row.eventId) // the copy with the alarm wins
        // Without alarms the organizer's copy wins.
        val due2 = ReminderCalculator.due(listOf(invite.copy(reminders = listOf(popup(10))), mine), start - 60 * minute, start)
        assertEquals("a", due2.single().row.eventId)
        // Other occurrences of the same series are separate.
        val next = mine.copy(beginUtc = start + 1440 * minute, endUtc = start + 1500 * minute)
        assertEquals(2, ReminderCalculator.due(listOf(mine, next), start - 60 * minute, start + 1440 * minute).size)
    }

    @Test
    fun declinedInvitationsDoNotRing() {
        val r = row(popup(10), attendees = listOf(Attendee("ME@x.com", response = "declined")))
        assertTrue(ReminderCalculator.due(listOf(r), start - 60 * minute, start).isEmpty())
    }

    @Test
    fun allDayRemindersCountFromLocalMidnight() {
        val zone = ZoneId.of("Europe/Rome")
        val midnight = LocalDate.of(2026, 10, 8).atStartOfDay(zone).toInstant().toEpochMilli()
        // "The day before at 17:00" in Google is 420 minutes before the start.
        val r = row(popup(420), begin = midnight, end = midnight + 1440 * minute).copy(allDay = true)
        val due = ReminderCalculator.due(listOf(r), midnight - 1440 * minute, midnight)
        assertEquals(LocalDate.of(2026, 10, 7).atTime(17, 0).atZone(zone).toInstant().toEpochMilli(), due.single().fireAt)
    }
}
