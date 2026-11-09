package com.tvlive.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelOptionsTest {

    @Test
    fun `fromJson handles null and blank`() {
        assertTrue(ChannelOptions.fromJson(null).isEmpty())
        assertTrue(ChannelOptions.fromJson("").isEmpty())
        assertTrue(ChannelOptions.fromJson("   ").isEmpty())
    }

    @Test
    fun `fromJson decodes whitelisted keys`() {
        val json = """["http-user-agent=Mozilla/5.0","http-referrer=https://example.com"]"""
        val opts = ChannelOptions.fromJson(json)
        val props = opts.asDefaultRequestProperties()
        assertEquals("Mozilla/5.0", props["User-Agent"])
        assertEquals("https://example.com", props["Referer"])
    }

    @Test
    fun `http-referer is normalised to Referer`() {
        // M3U sources in the wild use both spellings.
        val json = """["http-referer=https://foo.bar"]"""
        val opts = ChannelOptions.fromJson(json)
        assertEquals("https://foo.bar", opts.asDefaultRequestProperties()["Referer"])
    }

    @Test
    fun `http-proxy parses host and port`() {
        val json = """["http-proxy=192.168.1.1:8080"]"""
        val opts = ChannelOptions.fromJson(json)
        val proxy = opts.asProxy()
        assertEquals("192.168.1.1", proxy?.host)
        assertEquals(8080, proxy?.port)
    }

    @Test
    fun `http-proxy accepts scheme prefix`() {
        val json = """["http-proxy=http://10.0.0.1:3128"]"""
        val opts = ChannelOptions.fromJson(json)
        val proxy = opts.asProxy()
        assertEquals("10.0.0.1", proxy?.host)
        assertEquals(3128, proxy?.port)
    }

    @Test
    fun `http-proxy defaults to port 80 when missing`() {
        val json = """["http-proxy=proxy.local"]"""
        val opts = ChannelOptions.fromJson(json)
        val proxy = opts.asProxy()
        assertEquals("proxy.local", proxy?.host)
        assertEquals(80, proxy?.port)
    }

    @Test
    fun `invalid http-proxy returns null`() {
        val json = """["http-proxy=not:a:valid:port"]"""
        val opts = ChannelOptions.fromJson(json)
        assertNull(opts.asProxy())
    }

    @Test
    fun `malformed JSON does not crash`() {
        val opts = ChannelOptions.fromJson("not json at all")
        assertTrue(opts.isEmpty())
    }

    @Test
    fun `empty key or value is dropped`() {
        val json = """["=value","key=","no-equals-here"]"""
        val opts = ChannelOptions.fromJson(json)
        assertTrue(opts.isEmpty())
    }
}
