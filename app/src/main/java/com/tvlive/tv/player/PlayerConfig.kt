package com.tvlive.tv.player

/**
 * TVLive playback tuning constants.
 *
 * These mirror the values iTelly-macOS ships with after the 2026-09-14
 * defect-fix pass. Keep them here (not inlined) so a `--diagnose`
 * self-test can dump them, and so users can override via settings.
 */
object PlayerConfig {

    // ---- Live tuning (HLS/RTSP live) ----
    /** Minimum buffer (ms) before the first frame can be drawn. */
    const val LIVE_MIN_BUFFER_MS = 1_500L

    /** Maximum buffer (ms) before ExoPlayer stops refilling. */
    const val LIVE_MAX_BUFFER_MS = 5_000L

    /**
     * ms of buffer required to *start* playback on a live feed. Live
     * is a continuous stream so we don't need much; 500ms gets the
     * first frame out quickly. (Previously aliased to
     * [LIVE_BUFFER_FOR_REBUFFER_MS], which was a bug: that value
     * is the *rebuffer* threshold, which on a live feed should be
     * longer than the start threshold so we don't go into a rebuffer
     * loop during normal playback.)
     */
    const val LIVE_BUFFER_FOR_PLAYBACK_MS = 500L

    /** ms of buffer required to resume after underrun (live). */
    const val LIVE_BUFFER_FOR_REBUFFER_MS = 1_000L

    /** Prefer dropping frames over pausing playback on underrun. */
    const val LIVE_DROP_FRAMES_ON_UNDERRUN = true

    // ---- VOD (mp4 / progressive) ----
    const val VOD_MIN_BUFFER_MS = 4_000L
    const val VOD_MAX_BUFFER_MS = 20_000L
    const val VOD_BUFFER_FOR_PLAYBACK_MS = 1_500L
    const val VOD_BUFFER_FOR_REBUFFER_MS = 2_000L

    // ---- Reconnect (the "fixed" defect from iTelly-macOS README) ----
    /** 1s / 2s / 4s exponential backoff. */
    val RECONNECT_BACKOFFS_MS = longArrayOf(1_000L, 2_000L, 4_000L)

    /** After 3 attempts the player surfaces a terminal error. */
    const val MAX_RECONNECT_ATTEMPTS = 3

    // ---- Watchdog ----
    /** If the playhead doesn't advance in 15s while supposedly
     *  playing, we treat it as a stall and either reconnect (live)
     *  or finish (VOD end). */
    const val STALL_WATCHDOG_MS = 15_000L

    /** Polling interval for the watchdog. */
    const val PROGRESS_POLL_MS = 1_000L

    // ---- Network ----
    const val HTTP_CONNECT_TIMEOUT_MS = 8_000
    const val HTTP_READ_TIMEOUT_MS = 8_000

    /**
     * Default User-Agent.
     *
     * We deliberately impersonate VLC: the same m3u subscriptions
     * that play fine in VLC / Kodi / TiviMate return
     * `PARSING_CONTAINER_UNSUPPORTED` when fetched with a
     * self-identified UA (`TVLive/...`). Home-IPTV gateways routinely
     * gate sub-playlist / segment fetches on a whitelist of well-known
     * client identifiers, and VLC is the most permissive of them.
     *
     * If a future upstream change forces us back to a self-identified
     * UA, add the gateway's expected identifier to a per-channel
     * `#EXTVLCOPT:http-user-agent=…` line in the source m3u — that
     * override is honoured by [ChannelAwareDataSourceFactory].
     */
    const val userAgent: String = "VLC/3.0.18 LibVLC/3.0.18"

    /**
     * Default headers applied to every HTTP request the player makes.
     *
     * Many home-IPTV gateways (the same ones that return a master
     * playlist whose body is `#EXTM3U` with `Content-Type:
     * application/json` — see StreamDiagnoser) refuse sub-playlist /
     * segment requests unless the client carries the headers it saw
     * on the master fetch. Sending `Referer` and `User-Agent` to *every*
     * request — including sub-playlist, segment, and live requests —
     * is the documented cure.
     *
     * The `Referer` slot is filled in at runtime by [PlayerController]
     * from the playlist URL that produced this channel, because the
     * server's session/token is bound to the originating playlist
     * document, not the app shell.
     *
     * Exposed as a `var` (not `val`) so [PlayerController] can mutate
     * the `Referer` entry per channel without re-allocating the map on
     * every request — [DefaultHttpDataSource.Factory.setDefaultRequestProperties]
     * copies the map on `createDataSource`, so mutating in place is safe
     * between requests but not concurrently with one.
     */
    val defaultRequestHeaders: MutableMap<String, String> = mutableMapOf(
        "User-Agent" to userAgent
    )

    // ---- UI ----
    /** D-pad LEFT / RIGHT seek step (ms). 10s matches iTelly-macOS. */
    const val SEEK_STEP_MS = 10_000L
}
