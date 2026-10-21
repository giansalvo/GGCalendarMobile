package io.github.giansalvo.ggcalendar.core.network

import io.github.giansalvo.ggcalendar.core.model.SubscriptionUrls
import io.github.giansalvo.ggcalendar.core.model.UrlCheck
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

/** Why a download of an iCal address did not give a calendar. No case carries the address: it can be a secret. */
enum class FetchFailure {
    /** The address is not accepted (not https, credentials, no host). */
    BAD_ADDRESS,
    NO_NETWORK,
    TIMEOUT,
    /** The certificate or the secure connection is not trusted. */
    TLS,
    /** 404 or 410: the calendar is not there any more (no longer public, address changed). */
    NOT_FOUND,
    /** 401 or 403: the address is not allowed (private calendar made private again). */
    FORBIDDEN,
    /** 429 or a 5xx: the server has a problem, try again later. */
    SERVER,
    OTHER_HTTP,
    TOO_MANY_REDIRECTS,
    /** A redirect to an address that is not https. */
    REDIRECT_NOT_HTTPS,
    TOO_LARGE,
    /** The answer is not a calendar (a web page, for example). */
    NOT_CALENDAR,
    EMPTY,
}

sealed interface FetchResult {
    /** [body] is the calendar text; [sha256] its hash in hexadecimal; [etag] and [lastModified] for the next conditional request. */
    class Fetched(val body: String, val etag: String?, val lastModified: String?, val sha256: String, val bytes: Int) : FetchResult {
        // The body is the calendar; never print it.
        override fun toString() = "Fetched(bytes=$bytes)"
    }

    /** The copy the caller has is still the current one (304). */
    data object NotModified : FetchResult

    /** [detail] is a short text for the logs, with the address and anything that can hold a key already taken out. */
    data class Failed(val reason: FetchFailure, val httpStatus: Int? = null, val detail: String? = null) : FetchResult
}

/**
 * Downloads an iCal address (a subscription or a one-time import). The address can be a secret, so: only https is accepted, also
 * for every redirect (at most [maxRedirects]); nothing is sent but a plain request (no cookies, no credentials); the size is
 * limited while reading, not after; the time is limited; no failure carries the address.
 */
class IcsFetcher(
    client: OkHttpClient,
    private val maxBytes: Int = MAX_BYTES,
    private val maxRedirects: Int = MAX_REDIRECTS,
    callTimeoutSeconds: Long = CALL_TIMEOUT_SECONDS,
) {
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    /** [etag] and [lastModified] from an earlier [FetchResult.Fetched]: if the copy is still current the answer is [FetchResult.NotModified]. */
    suspend fun fetch(address: String, etag: String? = null, lastModified: String? = null): FetchResult {
        var url = when (val check = SubscriptionUrls.normalize(address)) {
            is UrlCheck.Ok -> check.url
            is UrlCheck.Rejected -> return FetchResult.Failed(FetchFailure.BAD_ADDRESS, detail = check.problem.name)
        }
        var hops = 0
        while (true) {
            val request = Request.Builder().url(url)
                .header("Accept", "text/calendar, text/plain;q=0.8, */*;q=0.1")
                .header("User-Agent", USER_AGENT)
                .apply {
                    if (hops == 0) {
                        etag?.let { header("If-None-Match", it) }
                        lastModified?.let { header("If-Modified-Since", it) }
                    }
                }.build()
            val response = try {
                call(http.newCall(request))
            } catch (e: IOException) {
                return failure(e, address)
            }
            response.use { r ->
                when {
                    r.code == 304 -> return FetchResult.NotModified
                    r.isRedirect -> {
                        if (++hops > maxRedirects) return FetchResult.Failed(FetchFailure.TOO_MANY_REDIRECTS)
                        val location = r.header("Location") ?: return FetchResult.Failed(FetchFailure.OTHER_HTTP, r.code)
                        val target = r.request.url.resolve(location)?.toString() ?: return FetchResult.Failed(FetchFailure.OTHER_HTTP, r.code)
                        url = when (val check = SubscriptionUrls.normalize(target)) {
                            is UrlCheck.Ok -> check.url
                            is UrlCheck.Rejected -> return FetchResult.Failed(FetchFailure.REDIRECT_NOT_HTTPS, r.code)
                        }
                    }
                    r.isSuccessful -> return read(r, address)
                    r.code == 404 || r.code == 410 -> return FetchResult.Failed(FetchFailure.NOT_FOUND, r.code)
                    r.code == 401 || r.code == 403 -> return FetchResult.Failed(FetchFailure.FORBIDDEN, r.code)
                    r.code == 429 || r.code >= 500 -> return FetchResult.Failed(FetchFailure.SERVER, r.code)
                    else -> return FetchResult.Failed(FetchFailure.OTHER_HTTP, r.code)
                }
            }
        }
    }

    private fun read(response: Response, address: String): FetchResult {
        val body = response.body
        val declared = body.contentLength()
        if (declared > maxBytes) return FetchResult.Failed(FetchFailure.TOO_LARGE, response.code)
        val bytes = try {
            readLimited(body.byteStream(), maxBytes)
        } catch (e: TooLarge) {
            return FetchResult.Failed(FetchFailure.TOO_LARGE, response.code)
        } catch (e: IOException) {
            return failure(e, address)
        }
        if (bytes.isEmpty()) return FetchResult.Failed(FetchFailure.EMPTY, response.code)
        val text = decode(bytes)
        if (!text.trimStart().startsWith("BEGIN:VCALENDAR", ignoreCase = true)) return FetchResult.Failed(FetchFailure.NOT_CALENDAR, response.code)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return FetchResult.Fetched(text, response.header("ETag"), response.header("Last-Modified"), hash, bytes.size)
    }

    private class TooLarge : IOException()

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw TooLarge()
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** iCalendar is UTF-8; a byte order mark is dropped and a byte that is not valid becomes the replacement character. */
    private fun decode(bytes: ByteArray): String {
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    private fun failure(e: IOException, address: String): FetchResult.Failed {
        // With several addresses for a host OkHttp reports the first failure and keeps the others as suppressed ones: a secure
        // connection that failed on the second address is still a TLS problem.
        val all = generateSequence(listOf<Throwable>(e)) { level -> level.flatMap { listOfNotNull(it.cause) + it.suppressed }.takeIf { it.isNotEmpty() } }
            .take(4).flatten().toList()
        val reason = when {
            all.any { it is SSLException } -> FetchFailure.TLS
            e is UnknownHostException || e is ConnectException -> FetchFailure.NO_NETWORK
            e is InterruptedIOException -> FetchFailure.TIMEOUT
            else -> FetchFailure.NO_NETWORK
        }
        val detail = SubscriptionUrls.redact(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), address)
        return FetchResult.Failed(reason, detail = detail.take(200))
    }

    /** A call that stops when the coroutine is cancelled. */
    private suspend fun call(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val CALL_TIMEOUT_SECONDS = 60L
        const val USER_AGENT = "GGCalendar"
    }
}
