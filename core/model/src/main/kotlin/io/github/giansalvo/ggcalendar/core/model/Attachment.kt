package io.github.giansalvo.ggcalendar.core.model

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale

/**
 * An attachment of an event: a link, never a copy of the file. [fileId] and [iconUrl] are what Google adds to a file of Drive (or to a
 * known type); they are kept as Google sent them.
 */
@Serializable
data class EventAttachment(
    val url: String,
    val title: String = "",
    val mimeType: String? = null,
    val fileId: String? = null,
    val iconUrl: String? = null,
) {
    /** The name of the file in the address (empty for a page of Drive, where the title is the only name). */
    val fileName: String get() = AttachmentLink.fileName(url)

    /** What the person reads: the title, or the name of the file when there is no title, or the address. */
    val displayName: String get() = title.ifEmpty { fileName.ifEmpty { url } }

    /** True when the link may be opened from the app (https only). */
    val canOpen: Boolean get() = AttachmentLink.isValid(url)

    /** A file or page of Google Drive or Docs: the list says so under the name. */
    val isDrive: Boolean get() = fileId != null || AttachmentLink.host(url)?.let { it == "drive.google.com" || it == "docs.google.com" } == true
}

/** What the person typed was added, was already there, or does not fit. */
sealed interface AttachmentAdd {
    data class Added(val list: List<EventAttachment>) : AttachmentAdd
    /** The address is in the list already: nothing changes, and it is not an error. */
    data object Duplicate : AttachmentAdd
    data object Invalid : AttachmentAdd
    data object LimitReached : AttachmentAdd
}

/** The rules of the links of the attachments: https only, the limits, the name and the type of the file taken from the address. */
object AttachmentLink {
    /** Google keeps 25 and drops the others without telling. */
    const val MAX_COUNT = 25
    const val MAX_URL_LENGTH = 2048
    const val MAX_TITLE_LENGTH = 256

    private val TYPES = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
        "pdf" to "application/pdf", "txt" to "text/plain", "ics" to "text/calendar", "csv" to "text/csv", "html" to "text/html",
        "htm" to "text/html", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "zip" to "application/zip",
    )

    private const val SCHEME = "https://"

    /** The text between "https://" and the first / ? or #, when the address is a valid one; null otherwise. */
    private fun authority(url: String?): String? {
        if (url.isNullOrEmpty() || url.length > MAX_URL_LENGTH) return null
        if (url.any { it <= ' ' || it == '\u007f' || it.isWhitespace() }) return null
        if (!url.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = url.substring(SCHEME.length)
        val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it })
        if (authority.isEmpty() || '@' in authority) return null // credentials in the address
        val host = authority.substringBefore(':')
        if (host.isEmpty() || authority.startsWith('[') && !authority.contains(']')) return null
        return authority
    }

    /** True for an absolute https address without credentials, spaces or control characters, and not longer than [MAX_URL_LENGTH]. */
    fun isValid(url: String?): Boolean = authority(url) != null

    /** The host in lower case, without the port; null when the address is not valid. */
    fun host(url: String?): String? = authority(url)?.let { a ->
        (if (a.startsWith('[')) a.substring(0, a.indexOf(']') + 1) else a.substringBefore(':')).lowercase(Locale.ROOT)
    }

    /** The name of the file: the last part of the path, without the query, decoded. Empty for the pages of Google (Drive, Docs), which have no name in the address. */
    fun fileName(url: String?): String {
        val host = host(url) ?: return ""
        if (host == "drive.google.com" || host.endsWith(".google.com")) return ""
        val rest = url!!.substring(SCHEME.length)
        val path = rest.substring(authority(url)!!.length).substringBefore('#').substringBefore('?')
        val last = path.split('/').lastOrNull { it.isNotEmpty() } ?: return ""
        val decoded = runCatching { java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        return decoded.take(MAX_TITLE_LENGTH)
    }

    /** The type of the file from the extension of its name; null when it is not one of the known ones. */
    fun mimeType(url: String?): String? {
        val name = fileName(url)
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) TYPES[name.substring(dot + 1).lowercase(Locale.ROOT)] else null
    }

    /**
     * The attachment a person adds by pasting a link: the address as it was written (only the spaces around it are removed), the title as
     * typed or, when empty, the name of the file; null when the address is not valid.
     */
    fun create(url: String?, title: String? = null): EventAttachment? {
        val address = url?.trim()
        if (!isValid(address)) return null
        val name = title?.trim().orEmpty().ifEmpty { fileName(address) }.take(MAX_TITLE_LENGTH)
        return EventAttachment(address!!, name, mimeType(address))
    }

    /** Adds what was typed to [current] (a list that was read). */
    fun add(current: List<EventAttachment>, url: String?, title: String?): AttachmentAdd {
        val attachment = create(url, title) ?: return AttachmentAdd.Invalid
        if (current.any { it.url == attachment.url }) return AttachmentAdd.Duplicate
        if (current.size >= MAX_COUNT) return AttachmentAdd.LimitReached
        return AttachmentAdd.Added(current + attachment)
    }

    /** The list as the rest of the app keeps it: no repeated address, at most [MAX_COUNT]; the second element is how many were left out. */
    fun clean(list: List<EventAttachment>): Pair<List<EventAttachment>, Int> {
        val distinct = list.distinctBy { it.url }
        return distinct.take(MAX_COUNT) to (list.size - distinct.take(MAX_COUNT).size)
    }

    /** What the search compares with: the title and the name of the file (never the address), without accents or capitals. */
    fun searchText(list: List<EventAttachment>?): String? {
        if (list.isNullOrEmpty()) return null
        return list.joinToString("\n") { a ->
            normalize(if (a.fileName.isNotEmpty() && !a.fileName.equals(a.title, ignoreCase = true)) a.title + " " + a.fileName else a.title)
        }.ifBlank { null }
    }

    /** Lower case without accents: the form in which the search text is stored and the query is made. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

    /** The first attachment whose title or file name holds [query] (as the search finds it); null when none. */
    fun firstMatch(list: List<EventAttachment>?, query: String): EventAttachment? {
        val q = normalize(query.trim())
        if (q.isEmpty()) return null
        return list?.firstOrNull { a -> normalize(a.title).contains(q) || normalize(a.fileName).contains(q) }
    }
}
