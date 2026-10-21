package io.github.giansalvo.ggcalendar.core.model

import java.net.URI
import java.net.URISyntaxException

/** Why an address typed or pasted for a subscription is not accepted. */
enum class UrlProblem { EMPTY, TOO_LONG, NOT_AN_ADDRESS, NOT_HTTPS, HAS_CREDENTIALS }

/** The outcome of [SubscriptionUrls.normalize]: the address to use, or why not. */
sealed interface UrlCheck {
    data class Ok(val url: String) : UrlCheck
    data class Rejected(val problem: UrlProblem) : UrlCheck
}

/**
 * The address of a subscribed calendar. It can be a secret (the private iCal address of a Google calendar carries a key), so
 * what is shown or written anywhere goes through [mask] and [redact]; the address itself is kept encrypted and appears only
 * where it is used to download.
 */
object SubscriptionUrls {
    const val MAX_LENGTH = 2048

    /**
     * Cleans an address and checks it: surrounding spaces and quotes go, `webcal://` becomes `https://`, only `https` is
     * accepted (not `http`, not a file or an app scheme), no credentials in the address, a host is needed. The scheme and the
     * host are lower-cased and the fragment is dropped; the rest is left as it is.
     */
    fun normalize(input: String): UrlCheck {
        var text = input.trim().trim('"', '\'', '<', '>').trim()
        if (text.isEmpty()) return UrlCheck.Rejected(UrlProblem.EMPTY)
        if (text.length > MAX_LENGTH) return UrlCheck.Rejected(UrlProblem.TOO_LONG)
        if (text.any { it.isWhitespace() || it.isISOControl() }) return UrlCheck.Rejected(UrlProblem.NOT_AN_ADDRESS)
        val lower = text.lowercase()
        text = when {
            lower.startsWith("webcals://") -> "https://" + text.substring("webcals://".length)
            lower.startsWith("webcal://") -> "https://" + text.substring("webcal://".length)
            else -> text
        }
        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return UrlCheck.Rejected(UrlProblem.NOT_AN_ADDRESS)
        }
        val scheme = uri.scheme?.lowercase() ?: return UrlCheck.Rejected(UrlProblem.NOT_AN_ADDRESS)
        if (scheme != "https") return UrlCheck.Rejected(UrlProblem.NOT_HTTPS)
        if (uri.rawUserInfo != null) return UrlCheck.Rejected(UrlProblem.HAS_CREDENTIALS)
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return UrlCheck.Rejected(UrlProblem.NOT_AN_ADDRESS)
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return UrlCheck.Ok("https://$host$port$path$query")
    }

    /** True when the address looks like it carries a secret: the private address of a Google calendar, a key or a long token. */
    fun isLikelySecret(url: String): Boolean {
        val uri = parse(url) ?: return false
        val path = uri.rawPath.orEmpty()
        if (path.contains("/private-") || path.contains("/private/")) return true
        val query = uri.rawQuery.orEmpty().lowercase()
        if (SECRET_KEYS.any { Regex("(^|&)$it=").containsMatchIn(query) }) return true
        return path.split('/').any { LONG_TOKEN.matches(it) }
    }

    /**
     * The address as it may be shown: the host, the name of the file and, for a secret, no key. A path segment that holds a secret
     * is masked ("private-••••••"), a query is replaced by "?…".
     */
    fun mask(url: String): String {
        val uri = parse(url) ?: return "•••"
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val file = segments.lastOrNull()?.let { maskSegment(it) }
        val secretSegment = segments.dropLast(1).firstOrNull { it.startsWith("private-") || LONG_TOKEN.matches(it) }?.let { maskSegment(it) }
        val middle = listOfNotNull(secretSegment).joinToString("/")
        val body = buildString {
            append(uri.host.orEmpty())
            if (segments.size > 1) append("/…")
            if (middle.isNotEmpty()) append("/").append(middle)
            if (file != null) append("/").append(file)
            if (!uri.rawQuery.isNullOrEmpty()) append("?…")
        }
        return "https://$body"
    }

    /** [text] with every appearance of the address, of its path and of its query replaced by the masked form. */
    fun redact(text: String, url: String): String {
        val uri = parse(url) ?: return text
        var out = text.replace(url, mask(url))
        val path = uri.rawPath.orEmpty()
        if (path.length > 1) out = out.replace(path, "/…")
        uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { out = out.replace(it, "…") }
        return out
    }

    private fun maskSegment(segment: String): String = when {
        segment.startsWith("private-") -> "private-••••••"
        LONG_TOKEN.matches(segment) -> "••••••"
        else -> segment
    }

    private fun parse(url: String): URI? = try {
        URI(url).takeIf { it.host != null }
    } catch (e: URISyntaxException) {
        null
    }

    private val SECRET_KEYS = listOf("token", "key", "secret", "auth", "sig", "signature", "password", "pwd")

    /** A path segment of 32 or more letters and digits: a key rather than a name. */
    private val LONG_TOKEN = Regex("[A-Za-z0-9_-]{32,}")
}
