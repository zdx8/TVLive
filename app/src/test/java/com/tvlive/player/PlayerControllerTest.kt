package com.tvlive.tv.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlayerController]'s pure-function companion
 * surface. These cover the high-stakes playback logic that
 * previously had zero coverage: error classification, reconnect
 * backoff, and error code name resolution.
 *
 * The companion functions are tested directly so we don't need to
 * construct an ExoPlayer (which has no public constructor and
 * requires an Android runtime).
 */
class PlayerControllerTest {

    // ---- classifyErrorCode: transient vs terminal ----

    @Test
    fun `IO_UNSPECIFIED is transient`() {
        val (transient, _) = PlayerController.classifyErrorCode(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
        assertTrue("IO_UNSPECIFIED must be transient", transient)
    }

    @Test
    fun `IO_NETWORK_CONNECTION_FAILED is transient`() {
        val (transient, _) = PlayerController.classifyErrorCode(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )
        assertTrue(transient)
    }

    @Test
    fun `IO_NETWORK_CONNECTION_TIMEOUT is transient`() {
        val (transient, _) = PlayerController.classifyErrorCode(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )
        assertTrue(transient)
    }

    @Test
    fun `IO_BAD_HTTP_STATUS is transient for 5xx retry semantics`() {
        // A 503 with Retry-After is exactly the kind of error we
        // want to retry — but only for the 5xx family. The decision
        // is made upstream; PlayerController just retries on
        // BAD_HTTP_STATUS.
        val (transient, _) = PlayerController.classifyErrorCode(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        assertTrue(transient)
    }

    @Test
    fun `IO_FILE_NOT_FOUND is terminal`() {
        // A 404 / file-not-found won't fix itself with retries.
        val (transient, _) = PlayerController.classifyErrorCode(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        )
        assertFalse(transient)
    }

    @Test
    fun `IO_NO_PERMISSION is terminal`() {
        val (transient, _) = PlayerController.classifyErrorCode(
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION
        )
        assertFalse(transient)
    }

    // ---- errorCodeName: human-readable name ----

    @Test
    fun `errorCodeName returns the canonical name for known codes`() {
        assertEquals(
            "ERROR_CODE_IO_UNSPECIFIED",
            PlayerController.errorCodeName(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        )
        assertEquals(
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            PlayerController.errorCodeName(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        )
    }

    @Test
    fun `errorCodeName returns generic string for unknown codes`() {
        // This is a defensive fallback: PlaybackException reserves
        // a range of error codes, but custom data sources may set
        // arbitrary ints. We never want a crash from name lookup.
        assertEquals("ERROR_CODE_99999", PlayerController.errorCodeName(99999))
    }

    // ---- nextReconnectDelayMs: backoff schedule ----

    @Test
    fun `nextReconnectDelayMs returns the configured backoff for the first attempt`() {
        val backoffs = longArrayOf(1_000L, 2_000L, 4_000L)
        assertEquals(1_000L, PlayerController.nextReconnectDelayMs(0, backoffs))
    }

    @Test
    fun `nextReconnectDelayMs returns the configured backoff for the middle attempt`() {
        val backoffs = longArrayOf(1_000L, 2_000L, 4_000L)
        assertEquals(2_000L, PlayerController.nextReconnectDelayMs(1, backoffs))
    }

    @Test
    fun `nextReconnectDelayMs returns null when attempts are exhausted`() {
        val backoffs = longArrayOf(1_000L, 2_000L, 4_000L)
        // The macOS settings max at 3 attempts (0..2), so 3 is past
        // the end.
        assertNull(PlayerController.nextReconnectDelayMs(3, backoffs))
        // And 99 should also be null.
        assertNull(PlayerController.nextReconnectDelayMs(99, backoffs))
    }

    @Test
    fun `nextReconnectDelayMs default uses the production 1s-2s-4s schedule`() {
        // Sanity check: production defaults are 1s, 2s, 4s.
        assertNotNull(PlayerController.nextReconnectDelayMs(0))
        assertNotNull(PlayerController.nextReconnectDelayMs(2))
        // The iTelly-macOS defect was "auto-reconnect never stops" —
        // verify our schedule actually stops at the configured max.
        val max = PlayerController.nextReconnectDelayMs(99)
        assertNull("Schedule must terminate at attempt > max", max)
    }
}
