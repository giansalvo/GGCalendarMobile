package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.withTransaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.giansalvo.ggcalendar.core.database.AccountEntity
import io.github.giansalvo.ggcalendar.core.database.CalendarKind
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.InstanceRow
import io.github.giansalvo.ggcalendar.core.database.SyncMessageEntity
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.model.Diag
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class AccountWithCalendars(val email: String, val calendars: List<CalendarInfo>)

@Singleton
class CalendarRepository @Inject constructor(
    private val db: GGCalendarDatabase,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val prefs: AppPreferences,
    private val birthdays: BirthdaySync,
    private val reminders: ReminderHooks,
    private val visibility: CalendarVisibility,
) {
    val syncing: StateFlow<Boolean> get() = engine.syncing

    val accounts: Flow<List<AccountWithCalendars>> =
        combine(
            db.accountDao().observeAll().onEach { Diag.log { "room accounts emits n=${it.size} [${it.joinToString { a -> Diag.who(a.email) }}]" } },
            db.calendarDao().observeAll().onEach { Diag.log { "room calendars emits n=${it.size}" } },
        ) { accounts, calendars ->
            accounts.map { a ->
                AccountWithCalendars(
                    a.email,
                    calendars.filter { it.accountEmail == a.email }.map {
                        CalendarInfo(it.id, it.accountEmail, it.name, it.color, it.isVisible, it.canWrite, it.isPrimary)
                    },
                )
            }
        }.onEach { list -> Diag.log { "repository accounts out n=${list.size} [${list.joinToString { a -> Diag.who(a.email) + ":" + a.calendars.size }}]" } }

    /** The calendars kept on this phone only, in the order they were made. */
    val localCalendars: Flow<List<CalendarInfo>> = db.calendarDao().observeAll().map { all ->
        all.filter { it.kind == CalendarKind.LOCAL }.sortedBy { it.sortOrder }
            .map { CalendarInfo(it.id, null, it.name, it.color, it.isVisible, it.canWrite, false, local = true) }
    }

    /** The "Birthdays" calendar of the phone's contacts, when turned on. */
    val birthdayCalendar: Flow<CalendarInfo?> = db.calendarDao().observeAll().map { all ->
        all.firstOrNull { it.id == BirthdaySync.CALENDAR_ID }?.let { CalendarInfo(it.id, it.accountEmail, it.name, it.color, it.isVisible, it.canWrite, it.isPrimary) }
    }

    /**
     * After the database was dropped by the change of schema: forgets the data-related settings and leaves a message that tells
     * the user to add the Google accounts again. Does nothing when the schema was not reset.
     */
    suspend fun acknowledgeSchemaReset(context: Context) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { db.openHelper.writableDatabase }
        if (!SchemaReset.pending(context)) return
        prefs.resetDataKeys()
        db.syncMessageDao().insert(SyncMessageEntity(accountEmail = null, kind = "schema_reset", text = ""))
        SchemaReset.clear(context)
    }

    /** Rebuilds the birthdays from the contacts (or removes them when off or without the permission). */
    suspend fun syncBirthdays() = birthdays.sync()

    val messages: Flow<List<SyncMessageEntity>> = db.syncMessageDao().observeUnread()

    /** Local changes not yet sent to Google (offline, or waiting for the next sync). */
    val pendingChanges: Flow<Int> = db.outboxDao().observeCount()

    /** Visible instances overlapping the days [from, to); series are expanded further if the days need it. */
    fun instances(from: LocalDate, to: LocalDate): Flow<List<EventInstance>> = channelFlow {
        launch { engine.updateWindow(from, to) }
        db.instanceDao().observeRange(from.toEpochDay(), to.toEpochDay()).map { rows ->
            rows.map { it.toInstance() }
        }.collect {
            Diag.log { "room instances emits ${from}..${to} n=${it.size}" }
            send(it)
        }
    }

    /** Occurrences matching [query] in title, place, description or guests (visible calendars, works offline). */
    suspend fun search(query: String, limit: Int = 300): List<EventInstance> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val escaped = text.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        return db.instanceDao().search("%$escaped%", limit).map { it.toInstance() }
    }

    /** The occurrence of [eventId] starting at [beginUtc], from any calendar (a reminder notification opens it). */
    suspend fun instance(eventId: String, beginUtc: Long): EventInstance? = db.instanceDao().get(eventId, beginUtc)?.toInstance()

    private fun InstanceRow.toInstance() = EventInstance(
        eventId = eventId, calendarId = calendarId, title = title, location = location,
        colorHex = colorHex, allDay = allDay,
        begin = Instant.ofEpochMilli(beginUtc), end = Instant.ofEpochMilli(endUtc),
        startDay = LocalDate.ofEpochDay(startDay), endDay = LocalDate.ofEpochDay(endDay),
        hasMeet = hasMeet, hasAlarm = hasAlarm, isRecurring = isRecurring, isBirthday = isBirthday, canWrite = canWrite,
    )

    /** A new day or time zone: move the instance window, rebuilding the instances if needed. */
    suspend fun refreshInstances() = engine.updateWindow()

    val viewPreferences: Flow<ViewPreferences> = prefs.view

    suspend fun setLastView(view: String) = prefs.setLastView(view)

    suspend fun setHourHeight(height: Float) = prefs.setHourHeight(height)

    /** Shows or hides a calendar; its reminders and alarms follow (see [CalendarVisibility]). */
    suspend fun setCalendarVisible(id: String, visible: Boolean) {
        visibility.set(id, visible)
        reminders.changed()
    }

    /** Adds an account whose access has just been granted, then syncs it. */
    suspend fun addAccount(email: String) {
        Diag.log { "repository addAccount begin ${Diag.who(email)}" }
        db.accountDao().insert(AccountEntity(email))
        Diag.log { "repository addAccount inserted ${Diag.who(email)}" }
        scheduler.syncNow()
    }

    /**
     * Removes an account from the phone: its calendars, events and instances go (cascade), and so does whatever was queued
     * for it. Nothing is deleted on Google. The reminders are planned again without it.
     */
    suspend fun removeAccount(email: String) {
        val prefix = email.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "|"
        Diag.log { "repository removeAccount begin ${Diag.who(email)}" }
        db.withTransaction {
            db.outboxDao().discardForAccount(prefix)
            db.accountDao().delete(email)
        }
        Diag.log { "repository removeAccount done ${Diag.who(email)}" }
        reminders.changed()
    }

    suspend fun markMessageRead(id: Long) = db.syncMessageDao().markRead(id)

    fun syncNow() = scheduler.syncNow()

    fun schedulePeriodicSync() = scheduler.schedulePeriodic()
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): GGCalendarDatabase =
        // Versions 1-5 (up to the 1.0.x releases) are dropped, not migrated: see GGCalendarDatabase.OLDEST_KEPT_VERSION.
        GGCalendarDatabase.configure(Room.databaseBuilder(context, GGCalendarDatabase::class.java, GGCalendarDatabase.NAME)) { SchemaReset.mark(context) }
            .build()
            .also { db ->
                // A diag build only: says when Room tells the observers that a table changed, whether or not a flow then emits.
                if (Diag.sink != null) {
                    db.invalidationTracker.addObserver(object : InvalidationTracker.Observer("accounts", "calendars", "events", "instances") {
                        override fun onInvalidated(tables: Set<String>) = Diag.log { "room invalidated $tables" }
                    })
                }
            }
}
