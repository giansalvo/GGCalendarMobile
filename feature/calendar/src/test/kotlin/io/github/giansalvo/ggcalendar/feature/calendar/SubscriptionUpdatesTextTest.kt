package io.github.giansalvo.ggcalendar.feature.calendar

import io.github.giansalvo.ggcalendar.core.data.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** U174: the line "It updates about every ..." of the dialog that adds a public calendar says the time of the settings. */
class SubscriptionUpdatesTextTest {
    @Test
    fun wholeHoursAreSaidInHoursAndTheOthersInMinutes() {
        assertEquals(false to 15, refreshUnit(15))
        assertEquals(false to 30, refreshUnit(30))
        assertEquals(true to 1, refreshUnit(60))
        assertEquals(true to 2, refreshUnit(120))
        assertEquals(true to 6, refreshUnit(360))
        assertEquals(true to 24, refreshUnit(1440))
    }

    @Test
    fun everyChoiceOfTheSettingsHasAText() {
        AppPreferences.SUBSCRIPTION_CHOICES.forEach { minutes ->
            val (inHours, count) = refreshUnit(minutes)
            assertEquals(minutes, if (inHours) count * 60 else count)
        }
    }
}
