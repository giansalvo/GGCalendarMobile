package io.github.giansalvo.ggcalendar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.MonthDay

class BirthdaysTest {
    @Test
    fun readsTheFormatsOfTheContactsProvider() {
        assertEquals(MonthDay.of(5, 17), Birthdays.parse("1980-05-17"))
        assertEquals(MonthDay.of(5, 17), Birthdays.parse("--05-17"))
        assertEquals(MonthDay.of(5, 17), Birthdays.parse("19800517"))
        assertEquals(MonthDay.of(2, 29), Birthdays.parse("1992-02-29"))
    }

    @Test
    fun ignoresWhatIsNotADate() {
        assertNull(Birthdays.parse(null))
        assertNull(Birthdays.parse(""))
        assertNull(Birthdays.parse("compleanno"))
        assertNull(Birthdays.parse("1980-13-40"))
    }

    private fun c(id: String, name: String, m: Int, d: Int) = ContactBirthday(id, name, MonthDay.of(m, d))

    @Test
    fun aBirthdayAlreadyComingFromGoogleIsNotAddedAgain() {
        val google = listOf(MonthDay.of(3, 4) to "Compleanno di Anna Rossi", MonthDay.of(7, 1) to "Babbo")
        val contacts = listOf(c("1", "Anna Rossi", 3, 4), c("2", "Babbo", 7, 1), c("3", "Luca Bianchi", 3, 4), c("4", "Anna Rossi", 9, 9))
        // Same name and day: duplicate. Same day, another name: kept. Same name, another day: kept.
        assertEquals(listOf("3", "4"), Birthdays.withoutDuplicates(contacts, google).map { it.contactId })
    }

    @Test
    fun aContactWithSeveralBirthdayRowsCountsOnce() {
        val contacts = listOf(c("1", "Anna", 3, 4), c("1", "Anna", 3, 4))
        assertEquals(1, Birthdays.withoutDuplicates(contacts, emptyList()).size)
    }
}
