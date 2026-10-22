package io.github.giansalvo.ggcalendar.core.data

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** How a file of calendar text is read: at most [MAX_BYTES], in whatever encoding it was written. */
object IcsSources {
    /** The longest file that is read (the same tetto as a downloaded one). */
    const val MAX_BYTES = 10 * 1024 * 1024

    /** The bytes of [input], or null when there are more than [max] (the rest is not read). */
    fun readLimited(input: InputStream, max: Int = MAX_BYTES): ByteArray? {
        val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** UTF-8 (with or without the BOM) and UTF-16 (BOM, or the zeros of ASCII text); a file that is not valid UTF-8 is read as Windows-1252. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        if (bytes.size >= 4 && bytes[0] != 0.toByte() && bytes[1] == 0.toByte() && bytes[2] != 0.toByte() && bytes[3] == 0.toByte()) return String(bytes, Charsets.UTF_16LE)
        if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] != 0.toByte() && bytes[2] == 0.toByte() && bytes[3] != 0.toByte()) return String(bytes, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }
}
