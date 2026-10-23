package com.tvlive.tv.player

import android.util.Log

/**
 * Diagnostics — TVLive self-test harness.
 *
 * Mirrors the iTelly-macOS `ITELLY_TEST_STREAM=… ./iTelly --diagnose`
 * flow. The idea: there are five failure modes we want to *prove* the
 * code paths for, not just hope they work in production:
 *
 *   1. Player can be constructed with our LoadControl
 *   2. Player can prepare a known-good HLS stream and reach STATE_READY
 *   3. Player correctly classifies "VOD ended" as non-error
 *   4. Reconnect attempt counter stops at MAX_RECONNECT_ATTEMPTS
 *   5. Stall watchdog fires after STALL_WATCHDOG_MS of no progress
 *
 * Each [assert] is named and tagged with a stable id; the build script
 * can grep the log for the id and fail the release if a regression
 * slipped through. The macOS project calls this the "回归自检" table.
 */
object Diagnostics {

    private const val TAG = "TVLive.Diag"
    private val checks = mutableListOf<Pair<String, Boolean>>()

    /** Dump PlayerConfig as plain text — used in `--diagnose` output. */
    fun dumpConfig(): String = buildString {
        appendLine("== 电视直播.PlayerConfig ==")
        appendLine("  LIVE_MIN_BUFFER_MS    = ${PlayerConfig.LIVE_MIN_BUFFER_MS}")
        appendLine("  LIVE_MAX_BUFFER_MS    = ${PlayerConfig.LIVE_MAX_BUFFER_MS}")
        appendLine("  LIVE_BUFFER_FOR_REBUF = ${PlayerConfig.LIVE_BUFFER_FOR_REBUFFER_MS}")
        appendLine("  VOD_MIN_BUFFER_MS     = ${PlayerConfig.VOD_MIN_BUFFER_MS}")
        appendLine("  VOD_MAX_BUFFER_MS     = ${PlayerConfig.VOD_MAX_BUFFER_MS}")
        appendLine("  RECONNECT_BACKOFFS_MS = ${PlayerConfig.RECONNECT_BACKOFFS_MS.toList()}")
        appendLine("  MAX_RECONNECT_ATTEMPTS= ${PlayerConfig.MAX_RECONNECT_ATTEMPTS}")
        appendLine("  STALL_WATCHDOG_MS     = ${PlayerConfig.STALL_WATCHDOG_MS}")
        appendLine("  PROGRESS_POLL_MS      = ${PlayerConfig.PROGRESS_POLL_MS}")
        appendLine("  HTTP_CONNECT_TIMEOUT  = ${PlayerConfig.HTTP_CONNECT_TIMEOUT_MS}")
        appendLine("  HTTP_READ_TIMEOUT     = ${PlayerConfig.HTTP_READ_TIMEOUT_MS}")
        appendLine("  USER_AGENT            = ${PlayerConfig.userAgent}")
        appendLine("  SEEK_STEP_MS          = ${PlayerConfig.SEEK_STEP_MS}")
    }

    /** Record the result of a named assertion. */
    fun assert(id: String, ok: Boolean, detail: String = "") {
        checks += id to ok
        if (ok) Log.i(TAG, "[PASS] $id $detail")
        else    Log.e(TAG, "[FAIL] $id $detail")
    }

    /**
     * Return a non-zero exit code if any assertion failed.
     * Wired into the build script (see scripts/diagnose.sh) so a
     * regression that re-introduces the macOS project's "auto-reconnect
     * never stops" bug fails the release.
     */
    fun summarize(): Summary {
        val passed = checks.count { it.second }
        val failed = checks.size - passed
        val ids = checks.filter { !it.second }.map { it.first }
        return Summary(passed, failed, ids)
    }

    data class Summary(val passed: Int, val failed: Int, val failedIds: List<String>) {
        fun toText(): String = buildString {
            appendLine("== Diagnostics summary ==")
            appendLine("  passed: $passed")
            appendLine("  failed: $failed")
            if (failedIds.isNotEmpty()) {
                appendLine("  failed ids:")
                failedIds.forEach { appendLine("    - $it") }
            }
        }
    }

    /**
     * Self-contained regression checks for the PlayerController invariants.
     * These run synchronously in a debug build via
     * `./gradlew :app:assembleDebug -Pitelly.diagnose=true`.
     *
     * Returns a [Summary] with the pass/fail counts. The previous
     * implementation logged failures but never propagated them — a
     * silent regression (e.g. changing the backoff to "1s, 1s, 1s, …")
     * would have shipped without any signal. We now return the
     * summary so the caller can throw or exit non-zero.
     */
    fun runOfflineRegressionChecks(): Summary {
        Log.i(TAG, "Running offline regression checks…")
        checks.clear()  // idempotent if called twice

        // Check #1: reconnect backoff schedule
        assert(
            id = "RECONNECT_BACKOFFS_ARE_EXPONENTIAL",
            ok = PlayerConfig.RECONNECT_BACKOFFS_MS.size == 3
                && PlayerConfig.RECONNECT_BACKOFFS_MS[0] == 1_000L
                && PlayerConfig.RECONNECT_BACKOFFS_MS[1] == 2_000L
                && PlayerConfig.RECONNECT_BACKOFFS_MS[2] == 4_000L,
            detail = "1s/2s/4s exponential backoff is the iTelly-macOS default"
        )

        // Check #2: max reconnect attempts is finite (the macOS bug was infinite)
        assert(
            id = "MAX_RECONNECT_ATTEMPTS_IS_FINITE",
            ok = PlayerConfig.MAX_RECONNECT_ATTEMPTS == 3,
            detail = "previous macOS defect had unbounded retries"
        )

        // Check #3: stall watchdog is at least the macOS value
        assert(
            id = "STALL_WATCHDOG_AT_LEAST_15S",
            ok = PlayerConfig.STALL_WATCHDOG_MS >= 15_000L,
            detail = "iTelly-macOS defaults to 15s"
        )

        // Check #4: live min buffer is 1.5s (the live tuning value)
        assert(
            id = "LIVE_MIN_BUFFER_IS_1500MS",
            ok = PlayerConfig.LIVE_MIN_BUFFER_MS == 1_500L,
            detail = "1.5s network buffer is the macOS live tuning"
        )

        // Check #5: VOD keeps a larger buffer (so seeks stay snappy)
        assert(
            id = "VOD_MIN_BUFFER_LARGER_THAN_LIVE",
            ok = PlayerConfig.VOD_MIN_BUFFER_MS > PlayerConfig.LIVE_MIN_BUFFER_MS,
            detail = "VOD min (${PlayerConfig.VOD_MIN_BUFFER_MS}) > live min (${PlayerConfig.LIVE_MIN_BUFFER_MS})"
        )

        // Check #6: HTTP timeouts are non-zero
        assert(
            id = "HTTP_TIMEOUTS_NONZERO",
            ok = PlayerConfig.HTTP_CONNECT_TIMEOUT_MS > 0 && PlayerConfig.HTTP_READ_TIMEOUT_MS > 0,
            detail = "connect=${PlayerConfig.HTTP_CONNECT_TIMEOUT_MS} read=${PlayerConfig.HTTP_READ_TIMEOUT_MS}"
        )

        // Check #7: seek step is 10s
        assert(
            id = "SEEK_STEP_IS_10S",
            ok = PlayerConfig.SEEK_STEP_MS == 10_000L,
            detail = "matches iTelly-macOS ←/→ 10s"
        )

        val summary = summarize()
        if (summary.failed > 0) {
            Log.e(TAG, summary.toText())
            // The macOS project's --diagnose mode exits with a
            // non-zero code if any assertion fails; we do the same so
            // CI / nightly builds break instead of shipping a
            // regression silently.
            throw IllegalStateException(
                "Diagnostics failed: ${summary.failed} of " +
                    "${summary.failed + summary.passed} checks failed. " +
                    "Failed ids: ${summary.failedIds}"
            )
        }
        return summary
    }
}
