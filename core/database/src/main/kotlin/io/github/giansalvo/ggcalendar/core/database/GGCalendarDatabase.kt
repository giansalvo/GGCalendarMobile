package io.github.giansalvo.ggcalendar.core.database

import androidx.room.Database
import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Builder
import androidx.room.RoomDatabase.Callback
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.giansalvo.ggcalendar.core.model.Attendee
import io.github.giansalvo.ggcalendar.core.model.EventAttachment
import io.github.giansalvo.ggcalendar.core.model.Reminder
import kotlinx.serialization.json.Json

@Database(
    entities = [
        AccountEntity::class,
        CalendarEntity::class,
        EventEntity::class,
        InstanceEntity::class,
        SyncMessageEntity::class,
        OutboxEntity::class,
        ReminderFiredEntity::class,
        ReminderSnoozedEntity::class,
    ],
    version = GGCalendarDatabase.CURRENT_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GGCalendarDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun calendarDao(): CalendarDao
    abstract fun eventDao(): EventDao
    abstract fun instanceDao(): InstanceDao
    abstract fun syncMessageDao(): SyncMessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        const val NAME = "ggcalendar.db"

        /**
         * Version 7 starts again from a clean schema (calendar kinds, local calendars, subscriptions with their days): databases of
         * versions 1 to 6 are dropped, and the accounts are added again. The choice is not to write migrations before the 1.0.2 is out.
         */
        const val OLDEST_KEPT_VERSION = 7

        /** The version of the schema now (8: the attachments of the events, with a real migration from 7). */
        const val CURRENT_VERSION = 8

        /**
         * Version 8: the attachments of the events. The column is null (not read) for the events of Google, which come back with them: the
         * sync tokens are cleared, so the next pull of each Google calendar is a full one. The events of the other calendars have no
         * attachments to read: an empty list.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN attachments TEXT")
                db.execSQL("ALTER TABLE events ADD COLUMN attachment_text TEXT")
                db.execSQL("UPDATE events SET attachments = '[]' WHERE calendar_id IN (SELECT id FROM calendars WHERE kind != 'google')")
                db.execSQL("UPDATE calendars SET sync_token = NULL WHERE kind = 'google'")
            }
        }

        /** The schema rules of the app: versions 1-6 are dropped ([onReset] is told when that happens). */
        fun configure(builder: Builder<GGCalendarDatabase>, onReset: () -> Unit): Builder<GGCalendarDatabase> =
            builder.fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6)
                .addMigrations(MIGRATION_7_8)
                .addCallback(object : Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) = onReset()
                })
    }
}

/** The tables that screens observe through flows: the list of what the app shows is built from them. */
val OBSERVED_TABLES = arrayOf("accounts", "calendars", "events", "instances", "outbox", "sync_messages")

/**
 * Keeps Room's change tracking switched on for [OBSERVED_TABLES] for as long as the database lives.
 *
 * Room installs the triggers that tell it a table changed when the first observer of that table arrives and removes them after the
 * last one leaves; a flow that is collected only while a screen is shown comes and goes all the time. A write made in that gap
 * (a sync or the recomputation of the instances, on a background thread) is not announced, and the screen that opens afterwards
 * keeps an old list: a new event was found by the search (a plain query) but not drawn in the views (flows), and the counter of
 * the changes waiting to be sent stayed at its old value. Found on 21/10/2026: the full run failed at T144 and T145 on the API 34
 * and 36 emulators with the normal build and passed with the diagnostic build, the only difference being this observer.
 * [onInvalidated] is called on each change (the diagnostic build logs it).
 */
fun GGCalendarDatabase.keepObservingTables(onInvalidated: (Set<String>) -> Unit = {}): InvalidationTracker.Observer {
    val observer = object : InvalidationTracker.Observer(OBSERVED_TABLES) {
        override fun onInvalidated(tables: Set<String>) = onInvalidated(tables)
    }
    invalidationTracker.addObserver(observer)
    return observer
}

/** Lists are stored as JSON (only queried columns are real columns). */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun remindersToJson(value: List<Reminder>?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun remindersFromJson(value: String?): List<Reminder>? = value?.let { json.decodeFromString(it) }

    @TypeConverter
    fun attendeesToJson(value: List<Attendee>): String = json.encodeToString(value)

    @TypeConverter
    fun attendeesFromJson(value: String): List<Attendee> = json.decodeFromString(value)

    @TypeConverter
    fun attachmentsToJson(value: List<EventAttachment>?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun attachmentsFromJson(value: String?): List<EventAttachment>? = value?.let { json.decodeFromString(it) }

    @TypeConverter
    fun stringsToJson(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun stringsFromJson(value: String): List<String> = json.decodeFromString(value)
}
