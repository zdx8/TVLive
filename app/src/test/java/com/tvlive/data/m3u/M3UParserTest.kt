package com.tvlive.tv.data.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [M3UParser]. Mirrors the iTelly-macOS parser test coverage
 * (all the weird corner cases the macOS code has comments about).
 */
class M3UParserTest {

    private val parser = M3UParser()

    // ---- parseExtInf: comma-inside-attribute handling ----

    @Test
    fun `parses basic EXTINF with attributes and title`() {
        val extInf = parser.parseExtInf(
            """-1 tvg-id="abc" tvg-name="BBC One" group-title="UK",BBC One"""
        )
        assertEquals("BBC One", extInf.title)
        assertEquals("abc", extInf.attributes["tvg-id"])
        assertEquals("BBC One", extInf.attributes["tvg-name"])
        assertEquals("UK", extInf.attributes["group-title"])
    }

    @Test
    fun `handles comma inside quoted attribute value`() {
        // Logo URL contains a comma — the comma after `group-title="UK,Live"`
        // must not be treated as the title separator.
        val extInf = parser.parseExtInf(
            """-1 tvg-logo="https://example.com/a,b.png" group-title="UK,Live",Channel"""
        )
        assertEquals("Channel", extInf.title)
        assertEquals("https://example.com/a,b.png", extInf.attributes["tvg-logo"])
        assertEquals("UK,Live", extInf.attributes["group-title"])
    }

    @Test
    fun `returns null title when there is no title after the comma`() {
        val extInf = parser.parseExtInf("""-1 tvg-id="abc" ,""")
        assertNull(extInf.title)
    }

    @Test
    fun `handles title with no attributes`() {
        val extInf = parser.parseExtInf("""-1,Just a Title""")
        assertEquals("Just a Title", extInf.title)
        assertTrue(extInf.attributes.isEmpty())
    }

    // ---- parse: end-to-end ----

    @Test
    fun `parses a minimal two-channel M3U`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" group-title="News",Channel A
            http://example.com/a.m3u8
            #EXTINF:-1 group-title="Sports",Channel B
            http://example.com/b.m3u8
        """.trimIndent()
        val parsed = parser.parse(text)
        assertEquals(2, parsed.channelCount)
        assertEquals("Channel A", parsed.channels[0].name)
        assertEquals("News", parsed.channels[0].groupTitle)
        assertEquals("a", parsed.channels[0].tvgId)
        assertEquals("Channel B", parsed.channels[1].name)
        assertEquals(listOf("News", "Sports"), parsed.groups)
    }

    @Test
    fun `falls back to URL basename when no name is set`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,NoName
            http://server.example.com/stream.m3u8
        """.trimIndent()
        val parsed = parser.parse(text)
        assertEquals(1, parsed.channelCount)
        // NoName is the title here so it's used first; if we drop the title:
        val text2 = """
            #EXTM3U
            http://server.example.com/stream.m3u8
        """.trimIndent()
        val parsed2 = parser.parse(text2)
        assertEquals("stream.m3u8", parsed2.channels[0].name)
    }

    @Test
    fun `recognizes all supported URL schemes`() {
        for (scheme in M3UParser.SUPPORTED_SCHEMES) {
            val text = "#EXTM3U\n#EXTINF:-1,Test\n$scheme://example.com/x"
            val parsed = parser.parse(text)
            assertEquals(
                "scheme $scheme should be recognized",
                1, parsed.channelCount
            )
        }
    }

    @Test
    fun `rejects lines without a recognized scheme`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Bad
            not-a-url-at-all
        """.trimIndent()
        val parsed = parser.parse(text)
        assertEquals(0, parsed.channelCount)
    }

    @Test
    fun `EXTVLCOPT whitelisted keys are kept`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Channel
            #EXTVLCOPT:http-user-agent=Foo/1.0
            #EXTVLCOPT:network-caching=1500
            http://example.com/x
        """.trimIndent()
        val parsed = parser.parse(text)
        val opts = parsed.channels[0].options
        assertTrue(opts.any { it.startsWith("http-user-agent=Foo/1.0") })
        assertTrue(opts.any { it == "network-caching=1500" })
    }

    @Test
    fun `EXTVLCOPT unknown keys are dropped`() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Channel
            #EXTVLCOPT:inputstream.adaptive.manifest_type=mpd
            http://example.com/x
        """.trimIndent()
        val parsed = parser.parse(text)
        val opts = parsed.channels[0].options
        assertTrue(
            "Kodi-specific keys should not leak through",
            opts.none { it.contains("inputstream") }
        )
    }

    @Test
    fun `EXTGRP overrides group-title from EXTINF`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 group-title="Original",Channel
            #EXTGRP:Overridden
            http://example.com/x
        """.trimIndent()
        val parsed = parser.parse(text)
        // The macOS parser keeps the most recent group; we do the same.
        assertEquals("Overridden", parsed.channels[0].groupTitle)
    }

    // ---- decode: encoding fallback ----

    @Test
    fun `decode accepts plain UTF-8`() {
        val bytes = "Hello, world".toByteArray(Charsets.UTF_8)
        assertEquals("Hello, world", parser.decode(bytes))
    }

    @Test
    fun `decode handles UTF-8 BOM`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = "BOM test".toByteArray(Charsets.UTF_8)
        val text = parser.decode(bom + body)
        assertNotNull(text)
        assertEquals("BOM test", text)
    }

    @Test
    fun `decode rejects pure binary via looksLikeText`() {
        // A bunch of NUL bytes — looksLikeText returns false, decode returns null.
        val bytes = ByteArray(256) { 0x00 }
        assertNull(parser.decode(bytes))
    }
}
