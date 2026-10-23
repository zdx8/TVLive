package com.tvlive.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [ChannelEntity.displayName], the property the
 * channel-drawer adapter uses for its row label. The "current row
 * highlight" depends on this name being the same one the user
 * sees in the row, otherwise the highlight and the visible label
 * would disagree.
 */
class ChannelEntityDisplayNameTest {

    private fun ch(
        id: Long = 1,
        name: String = "ignored",
        tvgName: String? = null,
        url: String = "http://example.com/x"
    ) = ChannelEntity(
        id = id, playlistId = 1, name = name,
        url = url, tvgName = tvgName
    )

    @Test
    fun `tvgName wins when present`() {
        assertEquals("CCTV-1", ch(name = "CCTV-1-HD", tvgName = "CCTV-1").displayName)
    }

    @Test
    fun `falls back to name when tvgName is blank`() {
        assertEquals("CCTV-1", ch(name = "CCTV-1", tvgName = "").displayName)
    }

    @Test
    fun `falls back to URL basename when both are blank`() {
        assertEquals("stream.m3u8", ch(name = "", tvgName = null, url = "http://x/stream.m3u8").displayName)
    }

    @Test
    fun `natural sort key uses displayName so sort = what you see`() {
        val a = ch(name = "ignored", tvgName = "CCTV2", url = "x")
        val b = ch(name = "ignored", tvgName = "CCTV10", url = "x")
        // Sorted by naturalSortKey should match the order the user
        // sees in the drawer.
        val sorted = listOf(b, a).sortedBy { it.naturalSortKey }
        assertEquals("CCTV2", sorted.first().displayName)
        assertEquals("CCTV10", sorted.last().displayName)
    }
}
