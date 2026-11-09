package com.tvlive.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelIndexBufferTest {

    /**
     * The buffer takes a clock function so we don't depend on
     * SystemClock in tests. This fake increments manually.
     */
    private class FakeClock(initial: Long = 0L) {
        var now: Long = initial
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `single digit returns the buffer`() {
        val clock = FakeClock(0L)
        val buf = ChannelIndexBuffer(timeoutMs = 3_000L, clock = { clock.now }, handler = null)
        assertEquals("1", buf.onDigit(1))
        assertEquals("1", buf.current())
    }

    @Test
    fun `accumulating digits builds a multi-digit number`() {
        val clock = FakeClock(0L)
        val buf = ChannelIndexBuffer(timeoutMs = 3_000L, clock = { clock.now }, handler = null)
        assertEquals("1", buf.onDigit(1))
        assertEquals("12", buf.onDigit(2))
        assertEquals("123", buf.onDigit(3))
    }

    @Test
    fun `digits past the timeout reset the buffer`() {
        val clock = FakeClock(0L)
        val buf = ChannelIndexBuffer(timeoutMs = 3_000L, clock = { clock.now }, handler = null)
        buf.onDigit(1)
        buf.onDigit(2)
        clock.advance(3_500L)  // past the 3s timeout
        // New press should start fresh, not append to "12"
        assertEquals("5", buf.onDigit(5))
    }

    @Test
    fun `clear wipes the buffer`() {
        val clock = FakeClock(0L)
        val buf = ChannelIndexBuffer(timeoutMs = 3_000L, clock = { clock.now }, handler = null)
        buf.onDigit(9)
        buf.clear()
        assertEquals("", buf.current())
    }

    @Test
    fun `non-digit input is rejected`() {
        val clock = FakeClock(0L)
        val buf = ChannelIndexBuffer(timeoutMs = 3_000L, clock = { clock.now }, handler = null)
        try {
            buf.onDigit(10)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            buf.onDigit(-1)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
