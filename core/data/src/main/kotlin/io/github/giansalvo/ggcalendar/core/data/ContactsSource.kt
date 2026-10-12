package io.github.giansalvo.ggcalendar.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.Data
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.giansalvo.ggcalendar.core.model.Attendee
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Email addresses of the phone's contacts, for the editor's guest suggestions (optional READ_CONTACTS). */
@Singleton
class ContactsSource @Inject constructor(@ApplicationContext private val context: Context) {
    val granted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** The birthdays saved in the contacts (with or without the year of birth); empty without the permission. */
    suspend fun birthdays(): List<ContactBirthday> {
        if (!granted) return emptyList()
        return withContext(Dispatchers.IO) {
            val out = mutableListOf<ContactBirthday>()
            runCatching {
                context.contentResolver.query(
                    Data.CONTENT_URI,
                    arrayOf(Data.CONTACT_ID, Data.DISPLAY_NAME_PRIMARY, Event.START_DATE),
                    "${Data.MIMETYPE} = ? AND ${Event.TYPE} = ?",
                    arrayOf(Event.CONTENT_ITEM_TYPE, Event.TYPE_BIRTHDAY.toString()),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val day = Birthdays.parse(c.getString(2)) ?: continue
                        val name = c.getString(1)?.trim().orEmpty()
                        if (name.isNotEmpty()) out += ContactBirthday(c.getString(0).orEmpty(), name, day)
                    }
                }
            }
            out
        }
    }

    suspend fun emails(): List<Attendee> {
        if (!granted) return emptyList()
        return withContext(Dispatchers.IO) {
            val out = mutableListOf<Attendee>()
            runCatching {
                context.contentResolver.query(
                    Email.CONTENT_URI,
                    arrayOf(Email.DISPLAY_NAME_PRIMARY, Email.ADDRESS),
                    null,
                    null,
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val address = c.getString(1)?.trim().orEmpty()
                        if ('@' in address) out += Attendee(address, displayName = c.getString(0)?.takeIf { it.isNotBlank() && it != address })
                    }
                }
            }
            out
        }
    }
}
