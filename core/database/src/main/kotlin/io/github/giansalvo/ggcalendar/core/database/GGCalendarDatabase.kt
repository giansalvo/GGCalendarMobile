package io.github.giansalvo.ggcalendar.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.giansalvo.ggcalendar.core.model.Attendee
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
    version = 5,
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

        /** v2: events.list also asks for birthdays, so every calendar needs a full sync (new sync tokens). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE calendars SET sync_token = NULL")
            }
        }

        /** v3: events.event_type (birthday icon); a full sync fills it for the events already stored. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN event_type TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("UPDATE calendars SET sync_token = NULL")
            }
        }

        /** v4: outbox of the local changes waiting to be sent to Google (milestone M3). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`event_id` TEXT NOT NULL, `calendar_id` TEXT NOT NULL, `operation` TEXT NOT NULL, `payload` TEXT, " +
                        "`base` TEXT, `send_updates` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, `last_error` TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_event_id` ON `outbox` (`event_id`)")
            }
        }

        /** v5: reminders already shown and snoozed (milestone M4). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminder_fired` (`key` TEXT NOT NULL, `fire_at` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reminder_snoozed` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`fire_at` INTEGER NOT NULL, `event_id` TEXT NOT NULL, `begin_utc` INTEGER NOT NULL, " +
                        "`method` TEXT NOT NULL, `minutes` INTEGER NOT NULL)",
                )
            }
        }
    }
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
    fun stringsToJson(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun stringsFromJson(value: String): List<String> = json.decodeFromString(value)
}
