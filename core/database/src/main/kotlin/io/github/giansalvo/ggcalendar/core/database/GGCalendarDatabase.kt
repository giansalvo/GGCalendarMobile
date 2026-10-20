package io.github.giansalvo.ggcalendar.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Builder
import androidx.room.RoomDatabase.Callback
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
    version = 6,
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
         * Version 6 starts again from a clean schema (calendar kinds, local calendars and subscriptions): databases of versions 1 to 5
         * are dropped, and the accounts are added again. From here on a change of schema needs a real migration.
         */
        const val OLDEST_KEPT_VERSION = 6

        /** The schema rules of the app: versions 1-5 are dropped ([onReset] is told when that happens), later ones will migrate. */
        fun configure(builder: Builder<GGCalendarDatabase>, onReset: () -> Unit): Builder<GGCalendarDatabase> =
            builder.fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5)
                .addCallback(object : Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) = onReset()
                })
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
