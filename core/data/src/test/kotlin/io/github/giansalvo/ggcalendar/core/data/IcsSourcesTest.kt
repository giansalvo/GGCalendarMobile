package io.github.giansalvo.ggcalendar.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** U188-U189: how much of a file is read and in which encoding. */
class IcsSourcesTest {
    @Test fun `U188 a file over the limit is refused without reading all of it`() {
        val limit = 1000
        assertNotNull(IcsSources.readLimited(ByteArrayInputStream(ByteArray(limit)), limit))
        assertNull(IcsSources.readLimited(ByteArrayInputStream(ByteArray(limit + 1)), limit))
        // a stream that never ends is cut at the limit, not read to its end
        var read = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int {
                read++
                return 65
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                read += len
                java.util.Arrays.fill(b, off, off + len, 65)
                return len
            }
        }
        assertNull(IcsSources.readLimited(endless, limit))
        assertTrue("read $read bytes", read < limit + 40_000)
        assertEquals(10 * 1024 * 1024, IcsSources.MAX_BYTES)
    }

    @Test fun `U189 UTF-8 with and without BOM, UTF-16 and Windows-1252`() {
        val text = "BEGIN:VCALENDAR\r\nSUMMARY:Caffè e città €\r\nEND:VCALENDAR\r\n"
        assertEquals(text, IcsSources.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, IcsSources.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, IcsSources.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, IcsSources.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
        assertEquals(text, IcsSources.decode(text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, IcsSources.decode(text.toByteArray(Charsets.UTF_16BE)))
        // not valid UTF-8: the accented letters of Windows-1252
        val latin = "SUMMARY:Caffè e città\r\n"
        assertEquals(latin, IcsSources.decode(latin.toByteArray(java.nio.charset.Charset.forName("windows-1252"))))
    }
}
