package io.github.giansalvo.ggcalendar.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Download of an iCal address: only https and nothing that can leak the address (U136), no failure or log line that carries it (U137).
 * The server is a local one with its own certificate that the test client trusts.
 */
class IcsFetcherTest {
    private val server = MockWebServer()
    private lateinit var client: OkHttpClient

    private val calendar = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:a@x\r\nSUMMARY:One\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    @Before
    fun setUp() {
        val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        server.useHttps(serverCerts.sslSocketFactory())
        server.start()
        client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()
    }

    @After
    fun tearDown() = server.close()

    private fun fetcher(maxBytes: Int = IcsFetcher.MAX_BYTES, timeoutSeconds: Long = 60) =
        IcsFetcher(client, maxBytes = maxBytes, callTimeoutSeconds = timeoutSeconds)

    private fun reply(code: Int = 200, body: String = calendar, vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        server.enqueue(builder.build())
    }

    private fun failed(result: FetchResult) = result as FetchResult.Failed

    // ---- U136

    @Test
    fun `a calendar is read with its hash, its ETag and its date`() = runTest {
        reply(headers = arrayOf("ETag" to "\"v1\"", "Last-Modified" to "Tue, 20 Oct 2026 10:00:00 GMT"))
        val result = fetcher().fetch(server.url("/cal.ics").toString()) as FetchResult.Fetched
        assertEquals(calendar, result.body)
        assertEquals("\"v1\"", result.etag)
        assertEquals("Tue, 20 Oct 2026 10:00:00 GMT", result.lastModified)
        assertEquals(64, result.sha256.length)
        val sent = server.takeRequest()
        assertTrue(sent.headers["Accept"]!!.startsWith("text/calendar"))
        assertEquals(IcsFetcher.USER_AGENT, sent.headers["User-Agent"])
        assertNull(sent.headers["Authorization"])
        assertNull(sent.headers["Cookie"])
    }

    @Test
    fun `the same content always has the same hash`() = runTest {
        reply()
        reply()
        val a = fetcher().fetch(server.url("/cal.ics").toString()) as FetchResult.Fetched
        val b = fetcher().fetch(server.url("/cal.ics").toString()) as FetchResult.Fetched
        assertEquals(a.sha256, b.sha256)
    }

    @Test
    fun `the cached version is asked for and a 304 says it is still current`() = runTest {
        server.enqueue(MockResponse.Builder().code(304).build())
        val result = fetcher().fetch(server.url("/cal.ics").toString(), etag = "\"v1\"", lastModified = "Tue, 20 Oct 2026 10:00:00 GMT")
        assertEquals(FetchResult.NotModified, result)
        val sent = server.takeRequest()
        assertEquals("\"v1\"", sent.headers["If-None-Match"])
        assertEquals("Tue, 20 Oct 2026 10:00:00 GMT", sent.headers["If-Modified-Since"])
    }

    @Test
    fun `webcal is read as https and http is refused without any request`() = runTest {
        reply()
        val webcal = server.url("/cal.ics").toString().replaceFirst("https:", "webcal:")
        assertTrue(fetcher().fetch(webcal) is FetchResult.Fetched)
        val http = server.url("/cal.ics").toString().replaceFirst("https:", "http:")
        assertEquals(FetchFailure.BAD_ADDRESS, failed(fetcher().fetch(http)).reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an https redirect is followed and one to http is refused`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/real.ics").toString()).build())
        reply()
        assertTrue(fetcher().fetch(server.url("/old.ics").toString()) is FetchResult.Fetched)
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "http://localhost/cal.ics").build())
        val refused = failed(fetcher().fetch(server.url("/old.ics").toString()))
        assertEquals(FetchFailure.REDIRECT_NOT_HTTPS, refused.reason)
    }

    @Test
    fun `a chain of redirects stops after five`() = runTest {
        repeat(6) { server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/next$it.ics").toString()).build()) }
        assertEquals(FetchFailure.TOO_MANY_REDIRECTS, failed(fetcher().fetch(server.url("/a.ics").toString())).reason)
    }

    @Test
    fun `a file over the limit is refused while it is read`() = runTest {
        reply(body = calendar + "X-FILL:" + "a".repeat(5000) + "\r\n")
        assertEquals(FetchFailure.TOO_LARGE, failed(fetcher(maxBytes = 1024).fetch(server.url("/big.ics").toString())).reason)
    }

    @Test
    fun `a slow answer ends with a timeout`() = runTest {
        server.enqueue(MockResponse.Builder().body(calendar).bodyDelay(4, TimeUnit.SECONDS).build())
        assertEquals(FetchFailure.TIMEOUT, failed(fetcher(timeoutSeconds = 1).fetch(server.url("/slow.ics").toString())).reason)
    }

    @Test
    fun `a page that is not a calendar, an empty answer and the byte order mark`() = runTest {
        reply(body = "<html><body>Sign in</body></html>")
        assertEquals(FetchFailure.NOT_CALENDAR, failed(fetcher().fetch(server.url("/login").toString())).reason)
        reply(body = "")
        assertEquals(FetchFailure.EMPTY, failed(fetcher().fetch(server.url("/empty.ics").toString())).reason)
        val withBom = MockResponse.Builder().body(okio.Buffer().write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())).writeUtf8(calendar)).build()
        server.enqueue(withBom)
        val result = fetcher().fetch(server.url("/bom.ics").toString()) as FetchResult.Fetched
        assertEquals(calendar, result.body)
    }

    @Test
    fun `the answers of the server are told apart`() = runTest {
        val expected = mapOf(404 to FetchFailure.NOT_FOUND, 410 to FetchFailure.NOT_FOUND, 401 to FetchFailure.FORBIDDEN, 403 to FetchFailure.FORBIDDEN,
            429 to FetchFailure.SERVER, 500 to FetchFailure.SERVER, 503 to FetchFailure.SERVER, 418 to FetchFailure.OTHER_HTTP)
        expected.forEach { (code, reason) ->
            reply(code = code, body = "no")
            val result = failed(fetcher().fetch(server.url("/cal.ics").toString()))
            assertEquals("code $code", reason, result.reason)
            assertEquals(code, result.httpStatus)
        }
    }

    @Test
    fun `a certificate that is not trusted ends in a TLS failure`() = runTest {
        reply()
        val result = failed(IcsFetcher(OkHttpClient()).fetch(server.url("/cal.ics").toString()))
        assertEquals(result.toString(), FetchFailure.TLS, result.reason)
    }

    // ---- U137

    @Test
    fun `no failure and no text of the fetcher carries the secret address`() = runTest {
        val secret = "0123456789abcdef0123456789abcdef"
        val address = server.url("/calendar/ical/someone%40example.org/private-$secret/basic.ics").toString()
        // a certificate error and an answer that is not a calendar: the two paths that print something
        val tls = failed(IcsFetcher(OkHttpClient()).fetch(address))
        reply(body = "<html>$address</html>")
        val page = failed(fetcher().fetch(address))
        listOf(tls, page).forEach { result ->
            val text = result.toString() + " " + result.detail.orEmpty()
            assertFalse(text, text.contains(secret))
            assertFalse(text, text.contains("someone"))
        }
        reply()
        val fetched = fetcher().fetch(address) as FetchResult.Fetched
        assertFalse(fetched.toString().contains(secret))
        assertFalse(fetched.toString().contains("BEGIN:VCALENDAR"))
    }

    @Test
    fun `an address with credentials is refused before any request`() = runTest {
        val result = failed(fetcher().fetch("https://user:pass@localhost:${server.port}/cal.ics"))
        assertEquals(FetchFailure.BAD_ADDRESS, result.reason)
        assertFalse(result.toString().contains("pass"))
        assertEquals(0, server.requestCount)
    }
}
