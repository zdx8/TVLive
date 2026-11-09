package com.tvlive.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [ChannelNavigation] — the index arithmetic behind the
 * drawer's quick channel-surf (wrap) and browse-while-open (clamp)
 * modes.
 *
 * Pure functions, no Android dependencies, so they run on the plain
 * JVM. These cover the two regressions that actually shipped:
 *
 *   1. UP on the first channel used to do nothing instead of wrapping
 *      to the last one.
 *   2. Auto-advance-on-error used to stop dead on the final channel
 *      because the move was clamped, so a dead last stream ended the
 *      run instead of looping back to channel 1.
 */
class ChannelNavigationTest {

    // ---------- wrapIndex (drawer closed: quick channel surf) ----------

    @Test
    fun `wrap moves forward by one`() {
        assertEquals(4, ChannelNavigation.wrapIndex(from = 3, delta = 1, size = 10))
    }

    @Test
    fun `wrap moves backward by one`() {
        assertEquals(2, ChannelNavigation.wrapIndex(from = 3, delta = -1, size = 10))
    }

    @Test
    fun `wrap goes from last to first`() {
        assertEquals(0, ChannelNavigation.wrapIndex(from = 9, delta = 1, size = 10))
    }

    @Test
    fun `wrap goes from first to last`() {
        assertEquals(9, ChannelNavigation.wrapIndex(from = 0, delta = -1, size = 10))
    }

    @Test
    fun `wrap handles deltas larger than the list`() {
        assertEquals(2, ChannelNavigation.wrapIndex(from = 0, delta = 12, size = 10))
        assertEquals(8, ChannelNavigation.wrapIndex(from = 0, delta = -12, size = 10))
    }

    @Test
    fun `wrap of a single-channel list stays on that channel`() {
        // Must NOT report a move: returning a different index would make
        // the auto-advance-on-error path recurse forever on a
        // one-channel playlist.
        assertEquals(0, ChannelNavigation.wrapIndex(from = 0, delta = 1, size = 1))
        assertEquals(0, ChannelNavigation.wrapIndex(from = 0, delta = -1, size = 1))
    }

    @Test
    fun `wrap with no current channel lands on the first row`() {
        assertEquals(0, ChannelNavigation.wrapIndex(from = -1, delta = 1, size = 10))
        assertEquals(0, ChannelNavigation.wrapIndex(from = -1, delta = -1, size = 10))
        assertEquals(0, ChannelNavigation.wrapIndex(from = 99, delta = 1, size = 10))
    }

    @Test
    fun `wrap of an empty list has nowhere to go`() {
        assertEquals(-1, ChannelNavigation.wrapIndex(from = 0, delta = 1, size = 0))
        assertEquals(-1, ChannelNavigation.wrapIndex(from = -1, delta = -1, size = 0))
    }

    // ---------- clampIndex (drawer open: browse cursor) ----------

    @Test
    fun `clamp moves forward by one`() {
        assertEquals(4, ChannelNavigation.clampIndex(from = 3, delta = 1, size = 10))
    }

    @Test
    fun `clamp stops at the last row instead of wrapping`() {
        assertEquals(9, ChannelNavigation.clampIndex(from = 9, delta = 1, size = 10))
    }

    @Test
    fun `clamp stops at the first row instead of wrapping`() {
        assertEquals(0, ChannelNavigation.clampIndex(from = 0, delta = -1, size = 10))
    }

    @Test
    fun `clamp handles deltas larger than the list`() {
        assertEquals(9, ChannelNavigation.clampIndex(from = 0, delta = 99, size = 10))
        assertEquals(0, ChannelNavigation.clampIndex(from = 9, delta = -99, size = 10))
    }

    @Test
    fun `clamp with no current position lands on the first row`() {
        assertEquals(0, ChannelNavigation.clampIndex(from = -1, delta = 1, size = 10))
        assertEquals(0, ChannelNavigation.clampIndex(from = 99, delta = -1, size = 10))
    }

    @Test
    fun `clamp of an empty list has nowhere to go`() {
        assertEquals(-1, ChannelNavigation.clampIndex(from = 0, delta = 1, size = 0))
    }

    @Test
    fun `clamp of a single-channel list reports no movement`() {
        assertEquals(0, ChannelNavigation.clampIndex(from = 0, delta = 1, size = 1))
        assertEquals(0, ChannelNavigation.clampIndex(from = 0, delta = -1, size = 1))
    }
}
