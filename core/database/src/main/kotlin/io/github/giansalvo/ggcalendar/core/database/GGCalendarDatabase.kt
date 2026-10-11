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
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GGCalendarDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun calendarDao(): CalendarDao
    abstract fun eventDao(): EventDao
    abstract fun instanceDao(): InstanceDao
    abstract fun syncMessageDao(): SyncMessageDao

    companion object {
        const val NAME = "ggcalendar.db"

        /** v2: events.list also asks for birthdays, so every calendar needs a full sync (new sync tokens). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE calendars SET sync_token = NULL")
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
