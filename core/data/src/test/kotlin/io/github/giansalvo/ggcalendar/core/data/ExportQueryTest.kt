package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.database.AccountEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.InstanceEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Which events an export of some days and calendars takes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExportQueryTest {
    private lateinit var db: GGCalendarDatabase

    private fun day(d: Int) = LocalDate.of(2026, 10, d).toEpochDay()

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        db.accountDao().insert(AccountEntity("me@x.com"))
        db.calendarDao().upsert(CalendarEntity("c1", "me@x.com", "me@x.com", "A", "#000", "owner"))
        db.calendarDao().upsert(CalendarEntity("c2", "me@x.com", "other", "B", "#000", "owner", isVisible = false))
        db.eventDao().upsert(
            listOf(
                EventEntity(id = "single", calendarId = "c1", title = "Single", startUtc = 1, endUtc = 2),
                EventEntity(id = "series", calendarId = "c1", title = "Series", rrule = "FREQ=DAILY", startUtc = 1, endUtc = 2),
                EventEntity(id = "moved", calendarId = "c1", title = "Moved", seriesMasterId = "series", startUtc = 1, endUtc = 2),
                EventEntity(id = "hidden", calendarId = "c2", title = "Hidden", startUtc = 1, endUtc = 2),
                EventEntity(id = "far", calendarId = "c1", title = "Far", startUtc = 1, endUtc = 2),
            ),
        )
        fun inst(id: String, d: Long) = InstanceEntity(id, d, d + 1, d, d + 1, false)
        db.instanceDao().insert(
            listOf(inst("single", day(10)), inst("series", day(9)), inst("series", day(10)), inst("moved", day(11)), inst("hidden", day(10)), inst("far", day(20))),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun ids(calendars: List<String>, from: Int, to: Int) =
        db.eventDao().exportRootIds(calendars, day(from), day(to) + 1).sorted()

    @Test
    fun aSeriesAndItsModifiedOccurrencesComeOutOnceAsTheSeries() = runTest {
        assertEquals(listOf("series", "single"), ids(listOf("c1"), 9, 12))
    }

    @Test
    fun onlyEventsWithAnOccurrenceInTheDaysAreTaken() = runTest {
        assertEquals(listOf("far"), ids(listOf("c1"), 20, 25))
        assertEquals(emptyList<String>(), ids(listOf("c1"), 13, 19))
    }

    @Test
    fun theLastDayIsIncludedAndHiddenCalendarsCount() = runTest {
        assertEquals(listOf("hidden", "series", "single"), ids(listOf("c1", "c2"), 10, 10))
    }

    @Test
    fun aModifiedOccurrenceAloneBringsItsSeries() = runTest {
        assertEquals(listOf("series"), ids(listOf("c1"), 11, 11))
    }
}
