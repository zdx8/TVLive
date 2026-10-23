package com.tvlive.tv.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Accumulates D-pad number-key input into a 1-based channel index.
 *
 * UX matches the IPTV set-top-box convention: pressing 1-2-3 within
 * 3 seconds of each other jumps to the 123rd channel. After the
 * timeout (default 3000ms) the buffer is cleared so a stray press
 * doesn't latch onto the next "1" minutes later.
 *
 * The handler is injectable so unit tests can pass a no-op. Default
 * is the main looper handler.
 */
class ChannelIndexBuffer(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val handler: Handler? = Handler(Looper.getMainLooper())
) {

    private val resetRunnable = Runnable { clear() }
    private var buffer: StringBuilder = StringBuilder()
    private var lastInputAt: Long = 0L

    /**
     * Called by the activity for every number key (0-9) press.
     * Returns the new buffer contents.
     */
    fun onDigit(digit: Int): String? {
        require(digit in 0..9) { "digit must be 0-9, got $digit" }
        val now = clock()
        // Reset if the user paused longer than the timeout
        if (now - lastInputAt > timeoutMs) {
            buffer.clear()
        }
        buffer.append(digit)
        lastInputAt = now
        handler?.removeCallbacks(resetRunnable)
        handler?.postDelayed(resetRunnable, timeoutMs)
        return buffer.toString()
    }

    fun clear() {
        buffer.clear()
        lastInputAt = 0L
        handler?.removeCallbacks(resetRunnable)
    }

    fun current(): String = buffer.toString()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
    }
}
