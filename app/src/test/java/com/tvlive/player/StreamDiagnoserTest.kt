package com.tvlive.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure parts of [StreamDiagnoser].
 *
 * We do **not** test [StreamDiagnoser.diagnose] here — that one opens
 * a real socket, and the unit-test classpath can't host an HTTP
 * server. The bytes-to-display-string helpers are the part most
 * likely to regress (control characters are notoriously easy to break
 * in Java string handling), so they get full coverage.
 */
class StreamDiagnoserTest {

    // ---------- toSafeUtf8 ----------

    @Test
    fun `empty input renders as empty`() {
        assertEquals("", StreamDiagnoser.toSafeUtf8("".toByteArray(Charsets.UTF_8), 100))
    }

    @Test
    fun `ascii prints unchanged up to the limit`() {
        val bytes = "hello world".toByteArray(Charsets.UTF_8)
        assertEquals("hello world", StreamDiagnoser.toSafeUtf8(bytes, 100))
    }

    @Test
    fun `truncation appends an ellipsis marker`() {
        val bytes = "abcdefghijklmnopqrstuvwxyz".toByteArray(Charsets.UTF_8)
        val truncated = StreamDiagnoser.toSafeUtf8(bytes, 10)
        assertTrue(
            "must be at most 11 chars (10 + ellipsis), got '${truncated}'",
            truncated.length <= 11
        )
        assertTrue("must end with the ellipsis marker", truncated.endsWith("…"))
    }

    @Test
    fun `null bytes are escaped, never silently dropped`() {
        // Null bytes used to silently truncate Android TextView
        // rendering; if they come back, the user just sees a half-empty
        // error overlay.
        val bytes = byteArrayOf(0x00, 0x41, 0x00, 0x42, 0x00)
        val s = StreamDiagnoser.toSafeUtf8(bytes, 100)
        assertFalse("null byte must NOT appear verbatim", s.contains('\u0000'))
        assertTrue("ASCII letters should still be visible", s.contains('A') && s.contains('B'))
    }

    @Test
    fun `control chars become escaped backslash-u hex`() {
        val bytes = byteArrayOf(0x01, 0x1F) // smallest control chars
        val s = StreamDiagnoser.toSafeUtf8(bytes, 100)
        assertFalse(s.contains('\u0001'))
        assertTrue(s.contains("\\u0001"))
        assertTrue(s.contains("\\u001F"))
    }

    @Test
    fun `newlines are visually rendered, not as raw LF`() {
        val bytes = "line1\nline2".toByteArray(Charsets.UTF_8)
        val s = StreamDiagnoser.toSafeUtf8(bytes, 100)
        // The LF would otherwise split the TextView into two lines,
        // hiding the second one in many TV-side renders.
        assertFalse("must not contain a raw LF", s.contains('\n'))
        assertTrue("must show a return marker", s.contains('⏎'))
    }

    @Test
    fun `tabs become arrow marker`() {
        val bytes = "a\tb".toByteArray(Charsets.UTF_8)
        val s = StreamDiagnoser.toSafeUtf8(bytes, 100)
        assertFalse(s.contains('\t'))
        assertTrue(s.contains("→"))
    }

    @Test
    fun `high-bit bytes survive (latin-1 printable range)`() {
        val bytes = byteArrayOf(0xC2.toByte(), 0xA1.toByte()) // ¡ as UTF-8
        val s = StreamDiagnoser.toSafeUtf8(bytes, 100)
        assertEquals("¡", s)
    }

    @Test
    fun `cjk characters survive verbatim`() {
        // A Chinese auth-error page must stay readable on the TV
        // overlay, not degrade into \uXXXX escapes.
        val s = StreamDiagnoser.toSafeUtf8("请登录后观看".toByteArray(Charsets.UTF_8), 100)
        assertEquals("请登录后观看", s)
    }

    @Test
    fun `c1 control chars are escaped, not passed through`() {
        // U+0085 (NEL) encodes as 0xC2 0x85 in UTF-8; NEL can break
        // TextView line rendering just like a raw LF.
        val bytes = byteArrayOf(0xC2.toByte(), 0x85.toByte())
        val s = StreamDiagnoser.toSafeUtf8(bytes, 100)
        assertTrue("NEL must be escaped", s.contains("\\u0085"))
    }

    @Test
    fun `invalid UTF-8 does not throw and does not lose printable bytes`() {
        // 0xFF alone is not valid UTF-8
        val bytes = byteArrayOf(0xFF.toByte(), 0x41, 0xFE.toByte())
        val s = StreamDiagnoser.toSafeUtf8(bytes, 100)
        assertTrue("printable byte must survive", s.contains('A'))
        assertFalse("raw 0xFF must not leak", s.contains('\u0000'))
    }

    // ---------- toHexPreview ----------

    @Test
    fun `hex of empty is empty`() {
        assertEquals("", StreamDiagnoser.toHexPreview(byteArrayOf(), 64))
    }

    @Test
    fun `single byte renders as two uppercase chars`() {
        assertEquals("FF ", StreamDiagnoser.toHexPreview(byteArrayOf(0xFF.toByte()), 64))
    }

    @Test
    fun `known binary signature is recognised`() {
        // "ID3" magic number — first 3 bytes 0x49 0x44 0x33 = "I" "D" "3"
        val bytes = byteArrayOf(0x49, 0x44, 0x33)
        assertEquals("49 44 33 ", StreamDiagnoser.toHexPreview(bytes, 64))
    }

    @Test
    fun `truncation marker appears when input exceeds limit`() {
        val bytes = ByteArray(100) { idx -> idx.toByte() }
        val s = StreamDiagnoser.toHexPreview(bytes, 10)
        assertTrue("truncation note must be present", s.contains("truncated"))
        assertTrue("must mention total size", s.contains("100 bytes"))
    }

    @Test
    fun `bytes beyond the preview limit are not rendered`() {
        val bytes = ByteArray(200) { idx -> idx.toByte() }
        // 0xC8 (= 200) must NOT appear in the hex output if we
        // requested a preview of 64 bytes.
        val s = StreamDiagnoser.toHexPreview(bytes, 64)
        assertFalse("byte at index 200 must be absent", s.contains("C8 "))
    }

    @Test
    fun `hex uses no lowercase hex letters`() {
        val bytes = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)
        val s = StreamDiagnoser.toHexPreview(bytes, 64)
        assertFalse("no lowercase a-f", s.contains("a"))
        assertFalse("no lowercase b", s.contains("b"))
    }

    // ---------- Diagnosis.toDisplayString ----------

    @Test
    fun `toDisplayString includes url and status and at least one marker`() {
        val diag = StreamDiagnoser.Diagnosis(
            url = "http://example.com/test.m3u8",
            statusLine = "200 OK",
            headers = listOf("Content-Type" to "application/vnd.apple.mpegurl"),
            bodyUtf8 = "#EXTM3U",
            bodyHex = "23 45 58 54 4D 33 55 ",
            notes = listOf("ok")
        )
        val s = diag.toDisplayString()
        assertTrue(s.contains("http://example.com/test.m3u8"))
        assertTrue(s.contains("200 OK"))
        assertTrue(s.contains("Content-Type"))
        assertTrue(s.contains("#EXTM3U"))
        assertTrue(s.contains("23 45 58"))
        assertTrue(s.contains("ok"))
    }

    @Test
    fun `toDisplayString handles empty body without crashing`() {
        val diag = StreamDiagnoser.Diagnosis(
            url = "http://x",
            statusLine = "502 Bad Gateway",
            headers = emptyList(),
            bodyUtf8 = "",
            bodyHex = "",
            notes = listOf("skip")
        )
        val s = diag.toDisplayString()
        assertTrue(s.contains("502 Bad Gateway"))
        assertTrue(s.contains("<empty>"))
        assertTrue(s.contains("- skip"))
    }
}