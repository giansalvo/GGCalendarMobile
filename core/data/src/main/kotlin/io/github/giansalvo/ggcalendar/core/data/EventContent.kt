package io.github.giansalvo.ggcalendar.core.data

import io.github.giansalvo.ggcalendar.core.database.EventEntity

/** Port of the desktop `EventContent`. */
object EventContent {
    /**
     * True when two versions of an event look the same to the user. Ignores what Google changes on its own: the Meet
     * link (created asynchronously), guests' replies, reminders, ids, and [ignoreGuest] (the calendar owner, whom Google
     * adds to the guest list as organizer).
     */
    fun same(a: EventEntity, b: EventEntity, ignoreGuest: String? = null): Boolean =
        a.title == b.title &&
            a.allDay == b.allDay &&
            a.startUtc == b.startUtc && a.endUtc == b.endUtc &&
            a.startDay == b.startDay && a.endDay == b.endDay &&
            a.location == b.location &&
            a.description == b.description &&
            (a.rrule ?: "") == (b.rrule ?: "") &&
            a.exdates.toSet() == b.exdates.toSet() &&
            guests(a, ignoreGuest) == guests(b, ignoreGuest) &&
            a.availability == b.availability &&
            a.visibility == b.visibility &&
            a.isCancelled == b.isCancelled &&
            sameAttachments(a, b)

    /** An unread list (null) is not a difference: Google's version is then the one that counts and nothing is sent. */
    private fun sameAttachments(a: EventEntity, b: EventEntity): Boolean {
        val x = a.attachments ?: return true
        val y = b.attachments ?: return true
        return x.map { it.url + "|" + it.title }.toSet() == y.map { it.url + "|" + it.title }.toSet()
    }

    private fun guests(e: EventEntity, ignore: String?): Set<String> =
        e.attendees.filterNot { it.email.equals(ignore, ignoreCase = true) }
            .map { it.email.lowercase() + if (it.optional) "?" else "" }
            .toSet()
}
