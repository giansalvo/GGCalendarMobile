package io.github.giansalvo.ggcalendar.core.data

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.giansalvo.ggcalendar.core.database.AccountEntity
import io.github.giansalvo.ggcalendar.core.database.GGCalendarDatabase
import io.github.giansalvo.ggcalendar.core.database.SyncMessageEntity
import io.github.giansalvo.ggcalendar.core.model.CalendarInfo
import io.github.giansalvo.ggcalendar.core.model.EventInstance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
) {
    val syncing: StateFlow<Boolean> get() = engine.syncing

    val accounts: Flow<List<AccountWithCalendars>> =
        combine(db.accountDao().observeAll(), db.calendarDao().observeAll()) { accounts, calendars ->
            accounts.map { a ->
                AccountWithCalendars(
                    a.email,
                    calendars.filter { it.accountEmail == a.email }.map {
                        CalendarInfo(it.id, it.accountEmail, it.name, it.color, it.isVisible, it.canWrite, it.isPrimary)
                    },
                )
            }
        }

    val messages: Flow<List<SyncMessageEntity>> = db.syncMessageDao().observeUnread()

    /** Local changes not yet sent to Google (offline, or waiting for the next sync). */
    val pendingChanges: Flow<Int> = db.outboxDao().observeCount()

    /** Visible instances overlapping the days [from, to); series are expanded further if the days need it. */
    fun instances(from: LocalDate, to: LocalDate): Flow<List<EventInstance>> = channelFlow {
        launch { engine.updateWindow(from, to) }
        db.instanceDao().observeRange(from.toEpochDay(), to.toEpochDay()).map { rows ->
            rows.map {
                EventInstance(
                    eventId = it.eventId, calendarId = it.calendarId, title = it.title, location = it.location,
                    colorHex = it.colorHex, allDay = it.allDay,
                    begin = Instant.ofEpochMilli(it.beginUtc), end = Instant.ofEpochMilli(it.endUtc),
                    startDay = LocalDate.ofEpochDay(it.startDay), endDay = LocalDate.ofEpochDay(it.endDay),
                    hasMeet = it.hasMeet, hasAlarm = it.hasAlarm, isRecurring = it.isRecurring, isBirthday = it.isBirthday, canWrite = it.canWrite,
                )
            }
        }.collect { send(it) }
    }

    /** A new day or time zone: move the instance window, rebuilding the instances if needed. */
    suspend fun refreshInstances() = engine.updateWindow()

    val viewPreferences: Flow<ViewPreferences> = prefs.view

    suspend fun setLastView(view: String) = prefs.setLastView(view)

    suspend fun setHourHeight(height: Float) = prefs.setHourHeight(height)

    suspend fun setCalendarVisible(id: String, visible: Boolean) = db.calendarDao().setVisible(id, visible)

    /** Adds an account whose access has just been granted, then syncs it. */
    suspend fun addAccount(email: String) {
        db.accountDao().insert(AccountEntity(email))
        scheduler.syncNow()
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
        Room.databaseBuilder(context, GGCalendarDatabase::class.java, GGCalendarDatabase.NAME)
            .addMigrations(GGCalendarDatabase.MIGRATION_1_2, GGCalendarDatabase.MIGRATION_2_3, GGCalendarDatabase.MIGRATION_3_4)
            .build()
}
