package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import io.github.giansalvo.ggcalendar.core.auth.GoogleAuthorizer
import io.github.giansalvo.ggcalendar.core.database.AccountEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarEntity
import io.github.giansalvo.ggcalendar.core.database.EventEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.OutboxEntity
import io.github.giansalvo.ggcalendar.core.network.GoogleCalendarApi
import io.github.giansalvo.ggcalendar.core.network.GoogleEventMapper
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The version a first edit of an occurrence starts from (issue 13): what the series gives, kept as the `base` of the queued change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OccurrenceBaseTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val me = "me@x.com"
    private val calendarId = "$me|$me"
    private lateinit var db: GGCalendarDatabase
    private lateinit var editor: SeriesEditor
    private lateinit var writer: EventWriter

    private fun ms(text: String) = LocalDateTime.parse(text).atZone(rome).toInstant().toEpochMilli()

    private val master = EventEntity(
        id = "m", calendarId = calendarId, remoteId = "g1", etag = "\"m1\"", title = "Lezione", location = "Aula", description = "Note",
        rrule = "FREQ=WEEKLY;BYDAY=MO", startUtc = ms("2026-10-05T10:00"), endUtc = ms("2026-10-05T11:00"), timeZone = "Europe/Rome",
        exdates = listOf("2026-10-26T09:00:00Z"),
    )
    private val occ = Occurrence(ms("2026-10-19T10:00"), ms("2026-10-19T11:00"), LocalDate.parse("2026-10-19").toEpochDay(), allDay = false)

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(context, GGCalendarDatabase::class.java).allowMainThreadQueries().build()
        val api = GoogleCalendarApi(OkHttpClient())
        val engine = SyncEngine(db, api, GoogleAuthorizer(context), AppPreferences(context), OutboxPusher(context, db, api))
        writer = EventWriter(db, engine, SyncScheduler(context), ReminderHooks(emptySet()))
        editor = SeriesEditor(db, writer)
        db.accountDao().insert(AccountEntity(me))
        db.calendarDao().upsert(CalendarEntity(calendarId, me, me, "Me", "#1A73E8", "owner"))
        db.eventDao().upsert(listOf(master))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun theOccurrenceOfATimedSeriesIsTheSeriesAtThatDay() {
        val o = SeriesOps.occurrenceOf(master, occ)
        assertEquals("Lezione", o.title)
        assertEquals("Aula", o.location)
        assertEquals("Note", o.description)
        assertEquals(occ.beginUtc, o.startUtc)
        assertEquals(occ.endUtc, o.endUtc)
        assertNull(o.rrule)
        assertTrue(o.exdates.isEmpty())
        assertEquals("m", o.seriesMasterId)
        assertEquals(occ.beginUtc, o.originalStartUtc)
        assertNull(o.remoteId)
        assertNull(o.etag)
    }

    @Test
    fun theOccurrenceOfAnAllDaySeriesKeepsTheLengthInDays() {
        val allDay = master.copy(allDay = true, startUtc = null, endUtc = null, startDay = LocalDate.parse("2026-10-05").toEpochDay(), endDay = LocalDate.parse("2026-10-07").toEpochDay())
        val day = LocalDate.parse("2026-10-19").toEpochDay()
        val o = SeriesOps.occurrenceOf(allDay, Occurrence(0, 0, day, allDay = true))
        assertTrue(o.allDay)
        assertEquals(day, o.startDay)
        assertEquals(day + 2, o.endDay) // two days, as the series
        assertNull(o.startUtc)
        assertNull(o.originalStartUtc)
        assertEquals(day, o.originalStartDay)
    }

    @Test
    fun onlyThisEventQueuesTheEditWithTheOccurrenceAsBase() = runTest {
        editor.save("m", occ, master.copy(title = "Spostata"), SeriesScope.THIS)

        val item = db.outboxDao().getAll().single()
        assertEquals(OutboxEntity.UPDATE, item.operation)
        assertEquals("Spostata", OutboxPusher.JSON.decodeFromString<EventEntity>(item.payload!!).title)
        val base = OutboxPusher.JSON.decodeFromString<EventEntity>(item.base!!)
        assertEquals("Lezione", base.title) // what the person saw, not what they typed
        assertEquals(occ.beginUtc, base.startUtc)
        assertNull(base.rrule)
    }

    @Test
    fun aSecondEditWhileTheFirstWaitsKeepsTheFirstBase() = runTest {
        editor.save("m", occ, master.copy(title = "Spostata"), SeriesScope.THIS)
        val first = db.outboxDao().getAll().single()
        val exception = db.eventDao().get(first.eventId)!!

        writer.save(exception.copy(title = "Terza versione"))

        val queue = db.outboxDao().getAll()
        assertEquals(2, queue.size)
        assertEquals(first.base, queue[1].base)
    }

    @Test
    fun theOtherScopesAreUnchanged() = runTest {
        editor.save("m", occ, master.copy(title = "Tutti"), SeriesScope.ALL)

        val item = db.outboxDao().getAll().single()
        // the master has its own id and etag: its base is the row as last synced, as for any event
        assertEquals("Lezione", OutboxPusher.JSON.decodeFromString<EventEntity>(item.base!!).title)
        assertEquals("g1", OutboxPusher.JSON.decodeFromString<EventEntity>(item.base!!).remoteId)
    }
}

/**
 * No false conflicts: the instance that Google gives for an occation nobody touched must be the same, for the comparison of [EventContent],
 * as the occurrence that the app builds from the series. The instances here are written as Google writes them (offset of the day, the zone of
 * the series, the fields of the series repeated); the emulator cases check the same with the real Google.
 */
class OccurrenceCoherenceTest {
    private val rome = ZoneId.of("Europe/Rome")
    private val calendarId = "me@x.com|me@x.com"
    private val offset = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private fun entity(json: String, id: String, masterId: String?) =
        GoogleEventMapper.fromGoogle(Json.parseToJsonElement(json).jsonObject)!!.toEntity(id, calendarId, masterId, emptyList())

    private val rich = ""","description":"Note","location":"Aula 3",
        "attendees":[{"email":"a@x.com","responseStatus":"accepted"},{"email":"b@x.com","optional":true,"responseStatus":"needsAction"},
                     {"email":"me@x.com","organizer":true,"self":true,"responseStatus":"accepted"}],
        "reminders":{"useDefault":false,"overrides":[{"method":"popup","minutes":10},{"method":"email","minutes":1440}]},
        "attachments":[{"fileUrl":"https://example.com/a.pdf","title":"a.pdf","mimeType":"application/pdf"}]"""

    private fun check(masterJson: String, instanceJson: (Occurrence, Boolean) -> String, from: String, to: String, count: Int) {
        val master = entity(masterJson, "m", null)
        val window = RecurrenceExpander.Window(
            LocalDate.parse(from).atStartOfDay(rome).toInstant(), LocalDate.parse(to).atStartOfDay(rome).toInstant(),
        )
        val occurrences = RecurrenceExpander.expand(master, emptyList(), window, rome)
        assertEquals(count, occurrences.size)
        for (i in occurrences) {
            val occ = Occurrence(i.beginUtc, i.endUtc, i.startDay, i.allDay)
            val instance = entity(instanceJson(occ, master.allDay), "x", "m")
            val base = SeriesOps.occurrenceOf(master, occ)
            assertTrue("the instance of ${Instant.ofEpochMilli(i.beginUtc)} differs from the occurrence of the series", EventContent.same(instance, base, ignoreGuest = "me@x.com"))
        }
    }

    private fun timedInstance(occ: Occurrence, @Suppress("UNUSED_PARAMETER") allDay: Boolean): String {
        val start = Instant.ofEpochMilli(occ.beginUtc).atZone(rome).format(offset)
        val end = Instant.ofEpochMilli(occ.endUtc).atZone(rome).format(offset)
        return """{"id":"g1_x","etag":"\"i\"","summary":"Lezione","recurringEventId":"g1",
            "originalStartTime":{"dateTime":"$start","timeZone":"Europe/Rome"},
            "start":{"dateTime":"$start","timeZone":"Europe/Rome"},"end":{"dateTime":"$end","timeZone":"Europe/Rome"}$rich}"""
    }

    @Test
    fun aWeeklyTimedSeriesAcrossTheEndOfDaylightSavingTime() {
        // Sundays 10:00 Rome from 11 October; the clocks go back on 25 October.
        val master = """{"id":"g1","etag":"\"m\"","summary":"Lezione","recurrence":["RRULE:FREQ=WEEKLY;COUNT=6"],
            "start":{"dateTime":"2026-10-11T10:00:00+02:00","timeZone":"Europe/Rome"},"end":{"dateTime":"2026-10-11T11:00:00+02:00","timeZone":"Europe/Rome"}$rich}"""
        check(master, ::timedInstance, "2026-10-01", "2026-12-31", 6)
    }

    @Test
    fun aDailySeriesInAnotherZoneThanThePhone() {
        val master = """{"id":"g1","etag":"\"m\"","summary":"Lezione","recurrence":["RRULE:FREQ=DAILY;COUNT=5"],
            "start":{"dateTime":"2026-10-23T09:30:00-04:00","timeZone":"America/New_York"},"end":{"dateTime":"2026-10-23T10:30:00-04:00","timeZone":"America/New_York"}$rich}"""
        val ny = ZoneId.of("America/New_York")
        check(
            master,
            { occ, _ ->
                val start = Instant.ofEpochMilli(occ.beginUtc).atZone(ny).format(offset)
                val end = Instant.ofEpochMilli(occ.endUtc).atZone(ny).format(offset)
                """{"id":"g1_x","etag":"\"i\"","summary":"Lezione","recurringEventId":"g1",
                    "originalStartTime":{"dateTime":"$start","timeZone":"America/New_York"},
                    "start":{"dateTime":"$start","timeZone":"America/New_York"},"end":{"dateTime":"$end","timeZone":"America/New_York"}$rich}"""
            },
            "2026-10-01", "2026-12-31", 5,
        )
    }

    @Test
    fun anAllDaySeriesOfTwoDays() {
        val master = """{"id":"g1","etag":"\"m\"","summary":"Gita","recurrence":["RRULE:FREQ=WEEKLY;COUNT=4"],
            "start":{"date":"2026-10-10"},"end":{"date":"2026-10-12"}$rich}"""
        check(
            master,
            { occ, _ ->
                val day = LocalDate.ofEpochDay(occ.startDay)
                """{"id":"g1_x","etag":"\"i\"","summary":"Gita","recurringEventId":"g1","originalStartTime":{"date":"$day"},
                    "start":{"date":"$day"},"end":{"date":"${day.plusDays(2)}"}$rich}"""
            },
            "2026-10-01", "2026-12-31", 4,
        )
    }

    @Test
    fun aSeriesWithoutAnyOptionalField() {
        val master = """{"id":"g1","etag":"\"m\"","summary":"Semplice","recurrence":["RRULE:FREQ=WEEKLY;COUNT=3"],
            "start":{"dateTime":"2026-10-12T08:00:00+02:00","timeZone":"Europe/Rome"},"end":{"dateTime":"2026-10-12T08:30:00+02:00","timeZone":"Europe/Rome"}}"""
        check(
            master,
            { occ, _ ->
                val start = Instant.ofEpochMilli(occ.beginUtc).atZone(rome).format(offset)
                val end = Instant.ofEpochMilli(occ.endUtc).atZone(rome).format(offset)
                """{"id":"g1_x","etag":"\"i\"","summary":"Semplice","recurringEventId":"g1",
                    "originalStartTime":{"dateTime":"$start","timeZone":"Europe/Rome"},
                    "start":{"dateTime":"$start","timeZone":"Europe/Rome"},"end":{"dateTime":"$end","timeZone":"Europe/Rome"}}"""
            },
            "2026-10-01", "2026-12-31", 3,
        )
    }

    @Test
    fun aDifferentTitleIsADifference() {
        val master = entity(
            """{"id":"g1","etag":"\"m\"","summary":"Lezione","recurrence":["RRULE:FREQ=WEEKLY;COUNT=2"],
                "start":{"dateTime":"2026-10-12T08:00:00+02:00","timeZone":"Europe/Rome"},"end":{"dateTime":"2026-10-12T09:00:00+02:00","timeZone":"Europe/Rome"}$rich}""",
            "m", null,
        )
        val i = RecurrenceExpander.expand(master, emptyList(), RecurrenceExpander.Window(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-12-01T00:00:00Z")), rome).first()
        val occ = Occurrence(i.beginUtc, i.endUtc, i.startDay, i.allDay)
        val renamed = entity(timedInstance(occ, false).replace("\"summary\":\"Lezione\"", "\"summary\":\"Altro\""), "x", "m")
        assertTrue(!EventContent.same(renamed, SeriesOps.occurrenceOf(master, occ), ignoreGuest = "me@x.com"))
    }
}
