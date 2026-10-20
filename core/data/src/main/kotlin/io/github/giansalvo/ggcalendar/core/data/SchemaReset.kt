package io.github.giansalvo.ggcalendar.core.data

import android.content.Context

/**
 * A flag, outside the database, that says the database was dropped and made again by a change of schema (Room tells it
 * before the new one exists). The app reads it at its next start to tell the user and to forget the settings about the old data.
 */
internal object SchemaReset {
    private const val FILE = "schema"
    private const val KEY = "reset_pending"

    fun mark(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).commit()
    }

    fun pending(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, false).apply()
    }
}
