package com.tvlive.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests that mirror `Diagnostics.runOfflineRegressionChecks`.
 *
 * The Diagnostics class logs to Android Log (which is not available
 * in plain JUnit), so we re-state the same invariants here as proper
 * assertions. `scripts/diagnose.sh` will also dump the live values;
 * these tests are the typed counterpart.
 */
class PlayerConfigTest {

    @Test
    fun `reconnect backoffs are 1s 2s 4s exponential`() {
        val backoffs = PlayerConfig.RECONNECT_BACKOFFS_MS
        assertEquals(3, backoffs.size)
        assertEquals(1_000L, backoffs[0])
        assertEquals(2_000L, backoffs[1])
        assertEquals(4_000L, backoffs[2])
    }

    @Test
    fun `max reconnect attempts is 3 (matches iTelly-macOS fix)`() {
        // The macOS project's 2026-09-14 defect-fix table calls this out:
        //   "退避恒 1 秒、**永不停止** (实测 70 秒重连 69 次)"
        // TVLive must NOT regress to unbounded retries.
        assertEquals(3, PlayerConfig.MAX_RECONNECT_ATTEMPTS)
    }

    @Test
    fun `stall watchdog is at least 15 seconds`() {
        assertTrue(
            "STALL_WATCHDOG_MS must be >= 15000 (iTelly-macOS default)",
            PlayerConfig.STALL_WATCHDOG_MS >= 15_000L
        )
    }

    @Test
    fun `live tuning uses 1_5s min buffer`() {
        assertEquals(1_500L, PlayerConfig.LIVE_MIN_BUFFER_MS)
    }

    @Test
    fun `VOD min buffer is larger than live min buffer`() {
        assertTrue(
            "VOD min (${PlayerConfig.VOD_MIN_BUFFER_MS}) > live min (${PlayerConfig.LIVE_MIN_BUFFER_MS})",
            PlayerConfig.VOD_MIN_BUFFER_MS > PlayerConfig.LIVE_MIN_BUFFER_MS
        )
    }

    @Test
    fun `HTTP timeouts are non-zero`() {
        assertTrue(PlayerConfig.HTTP_CONNECT_TIMEOUT_MS > 0)
        assertTrue(PlayerConfig.HTTP_READ_TIMEOUT_MS > 0)
    }

    @Test
    fun `seek step is 10 seconds`() {
        // matches iTelly-macOS ⌘← / ⌘→
        assertEquals(10_000L, PlayerConfig.SEEK_STEP_MS)
    }
}
