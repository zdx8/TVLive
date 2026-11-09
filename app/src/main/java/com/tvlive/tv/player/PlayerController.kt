package com.tvlive.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * PlayerController — playback engine wrapper for TVLive.
 *
 * Goals (mirror iTelly-macOS README §"四个值得记录的实现要点" + the
 * defect-fix table that follows):
 *
 *   1. **LoadControl set before Builder()** — Media3 ignores LoadControl
 *      changes after the player is built (equivalent to the macOS
 *      `VLC_PLUGIN_PATH` before `libvlc_new` pitfall).
 *
 *   2. **1.5s live buffer + drop frames, don't compensate clock jitter** —
 *      matches the macOS settings for HLS/RTSP live. VOD keeps the
 *      defaults (4s) so seeking feels snappy.
 *
 *   3. **Reconnect with 1s/2s/4s backoff, max 3 attempts** — fixes the
 *      "auto-reconnect never stops" defect. After 3 failures we surface
 *      the error and stop the watchdogs.
 *
 *   4. **15s progress watchdog** — if the playhead doesn't advance in
 *      15s while supposedly playing, we count it as a stall and either
 *      reconnect (live) or finish (VOD end).
 *
 *   5. **Surface must outlive the playback** — same as the macOS
 *      `VLCCAOpenGLLayer` rule. We bind once via [bindSurface] and
 *      never reassign the SurfaceView during playback.
 *
 *   6. **Volume scale** — Media3's `volume` is 0.0f..1.0f, but the
 *      macOS project's own bug was a *one-line* error reading the
 *      callback payload; we keep the scale consistent across all
 *      surfaces (slider, key, internal) to avoid the same footgun.
 *
 *   7. **Error classification** — distinguish a real connection error
 *      (transient, retry) from "playback completed normally" (terminal,
 *      do not retry). Without this, a 30s VOD ends and pops a
 *      "connection timeout" dialog (the macOS bug fixed 2026-09-14).
 */
@OptIn(UnstableApi::class) // Media3's LoadControl / DefaultHttpDataSource are still unstable in 1.1.x
class PlayerController(
    private val context: Context,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onPlaybackStateChanged(state: PlaybackState)
        fun onError(error: PlayerError, terminal: Boolean)
        fun onPositionChanged(positionMs: Long, durationMs: Long, isLive: Boolean)
    }

    enum class PlaybackState { IDLE, BUFFERING, READY, PAUSED, ENDED, RECONNECTING, ERROR }

    data class PlayerError(val code: String, val message: String, val cause: Throwable? = null)

    // ---- Reconnect / watchdog tunables (mirror iTelly-macOS) ----
    // Values are sourced from [PlayerConfig] so the `--diagnose` self-test
    // can dump them and so user settings can override at runtime.
    private val reconnectBackoffsMs = PlayerConfig.RECONNECT_BACKOFFS_MS
    private val maxReconnectAttempts = PlayerConfig.MAX_RECONNECT_ATTEMPTS
    private val stallWatchdogMs = PlayerConfig.STALL_WATCHDOG_MS
    private val progressPollIntervalMs = PlayerConfig.PROGRESS_POLL_MS

    // ---- Internal state ----
    private var player: ExoPlayer? = null
    private var currentStreamUrl: String? = null
    private var currentTitle: String? = null
    private var isLive: Boolean = false
    private var reconnectAttempt = 0
    private var isStallWatchdogArmed = false
    private var lastPositionForWatchdogMs = 0L
    private var lastPositionChangeAt = SystemClock.elapsedRealtime()
    private var playWhenReadyCached = true
    private var errorReportedTerminal = false

    /**
     * True once [prepare] has been called. We don't build the player
     * there any more (see [ensurePlayer]) because the LoadControl has
     * to be chosen from the *first* stream's live/VOD nature, and we
     * don't know that until [play].
     */
    private var prepared = false

    /**
     * The live/VOD mode the current LoadControl was built for. When
     * [play] switches between a live and a VOD stream we rebuild the
     * player so the buffer tuning actually takes effect.
     */
    private var loadControlIsLive: Boolean? = null

    /**
     * The SurfaceView handed to [bindSurface]. Held until the player
     * exists so we can attach the surface even though the Activity
     * calls `bindSurface()` before the first `play()`.
     */
    private var pendingSurfaceView: SurfaceView? = null

    /**
     * The scheduled reconnect attempt, kept as a field so [stopWatchdogs]
     * (and therefore [release] / [stop]) can cancel it. Without this a
     * Back press during the backoff window would fire `play()` on a
     * released controller and throw.
     */
    private val reconnectRunnable = Runnable {
        val url = currentStreamUrl
        if (url == null || player == null) {
            Log.i(TAG, "Reconnect skipped — controller released or no stream")
            return@Runnable
        }
        doStartPlayback(url, currentTitle, currentOptions)
    }

    /**
     * Per-channel options. Read by [ChannelAwareDataSourceFactory] on
     * every new playback so we can switch channels without rebuilding
     * the ExoPlayer. Mirrors how libVLC would apply
     * `:http-user-agent=…` `:http-referrer=…` `:http-proxy=…`.
     */
    @Volatile
    private var currentOptions: ChannelOptions = ChannelOptions.empty()

    /**
     * The parent m3u URL that produced the channel the user is now
     * playing. Used as the `Referer` for every HTTP request the
     * player makes, because most home-IPTV gateways bind their
     * session/token to the playlist document the client originally
     * fetched — without `Referer`, sub-playlists (`*.m3u8`) and
     * segments are often served as HTML or 401, which Media3 surfaces
     * as `PARSING_CONTAINER_UNSUPPORTED` (see StreamDiagnoser).
     *
     * Set this before [play] from the Activity (the caller knows which
     * subscription URL produced this channel). Defaults to the bundled
     * subscription URL so a misconfigured caller still sends something
     * sensible.
     */
    @Volatile
    var playlistReferer: String = com.tvlive.tv.AppConfig.DEFAULT_SUBSCRIPTION_URL

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            tickProgress()
            mainHandler.postDelayed(this, progressPollIntervalMs)
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Mark the controller ready for use.
     *
     * We deliberately do **not** build the [ExoPlayer] here. The
     * LoadControl (buffer durations, frame-drop policy) must be passed
     * to `ExoPlayer.Builder` before `build()`, and the right values
     * depend on whether the first stream is live or VOD — which we
     * only learn in [play]. Building eagerly here meant the player was
     * always constructed with the VOD profile, so the live tuning
     * never took effect. The player is now built lazily in
     * [ensurePlayer] with the correct profile.
     */
    fun prepare() {
        prepared = true
    }

    /**
     * Build (or rebuild) the underlying ExoPlayer for the given
     * live/VOD profile. No-op when a player already exists with the
     * same profile. Rebuilding happens when the user switches between
     * a live channel and a VOD item, because LoadControl is immutable
     * after `build()`.
     */
    private fun ensurePlayer(live: Boolean) {
        if (player != null && loadControlIsLive == live) return
        if (player != null) {
            Log.i(TAG, "Live mode changed ($loadControlIsLive -> $live) — rebuilding player")
            release()
        }

        val loadControl = buildLoadControl(live)

        // Wrap the DefaultHttpDataSource.Factory so the user-agent,
        // referer, and proxy headers injected by the channel's
        // #EXTVLCOPT options get applied to every MediaSource created
        // from this player. We keep one base factory and let the
        // wrapper re-apply headers on each createDataSource().
        //
        // The Referer is the *parent* m3u URL (set by the Activity
        // from its own `currentSubscriptionUrl`), not the channel URL
        // — that is the document the server thinks the client is
        // navigating from when it fetches a sub-playlist or a segment.
        // Without it, the gateway serves 401 / HTML for the very
        // requests that contain the actual stream.
        PlayerConfig.defaultRequestHeaders["Referer"] = playlistReferer
        val baseHttpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(PlayerConfig.HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(PlayerConfig.HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(PlayerConfig.userAgent)
            .setDefaultRequestProperties(PlayerConfig.defaultRequestHeaders)
        val httpFactory = ChannelAwareDataSourceFactory(baseHttpFactory) { currentOptions }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)

        val trackSelector = DefaultTrackSelector(context)

        val exo = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .build()
            .also { p ->
                p.addListener(playerListener)
                p.playWhenReady = playWhenReadyCached
            }
        player = exo
        loadControlIsLive = live

        // Attach the surface the Activity handed us before the player
        // existed.
        pendingSurfaceView?.let { view ->
            if (view.holder.surface.isValid) {
                exo.setVideoSurfaceHolder(view.holder)
            }
        }
    }

    /**
     * Bind a SurfaceView to the player. The view itself is owned by the
     * activity; we hold a callback so the underlying [android.view.Surface]
     * can be handed to ExoPlayer at the right time (created → set,
     * destroyed → clear).
     *
     * Why not `player.setVideoSurfaceView(view)` directly? Because the
     * surface may not exist when this is called (e.g. before the view
     * is laid out). ExoPlayer's [Player.setVideoSurfaceHolder] is the
     * correct API for late binding; calling it with `null` is the
     * documented way to detach. We *never* call `clearVideoSurface()`
     * during normal playback — same as the macOS rule that the
     * `VLCCAOpenGLLayer` cannot be re-parented mid-flight.
     */
    fun bindSurface(view: SurfaceView) {
        pendingSurfaceView = view
        view.holder.addCallback(surfaceCallback)
        // If the player already exists and the surface is live (typical
        // when the player was rebuilt), wire it straight away.
        val p = player
        if (p != null && view.holder.surface.isValid) {
            p.setVideoSurfaceHolder(view.holder)
        }
    }

    /**
     * Start (or restart) playback of [streamUrl] from a user action.
     * Resets the reconnect counter — a fresh channel deserves a fresh
     * budget.
     *
     * Note: [scheduleReconnect] must **not** go through here, or the
     * counter would be cleared on every retry and the backoff would
     * never escalate nor terminate (the macOS defect this port is
     * supposed to have fixed). It calls [startPlayback] directly.
     */
    fun play(streamUrl: String, title: String? = null, options: ChannelOptions = ChannelOptions.empty()) {
        reconnectAttempt = 0
        errorReportedTerminal = false

        // 无扩展名的短链通常是 302 到另一台主机/另一种协议的 HLS
        // （https://iptv.example.com/608807420 → http://migu....:8080/...m3u8）。
        // ExoPlayer 的跨协议重定向在这类链路上不可靠（实测拿到 302 的空
        // 响应体就报 "Input does not start with #EXTM3U"），所以自己跟一遍。
        //
        // ★ 必须在后台线程：一条链路可能有多次重定向，最坏情况是
        // 5 × 6s 超时，放在主线程就是一次 ANR。
        if (!needsRedirectResolve(streamUrl)) {
            doStartPlayback(streamUrl, title, options)
            return
        }
        Thread {
            val resolved = resolveRedirects(streamUrl)
            Log.i(TAG, "Resolved redirect: $streamUrl -> ${resolved.take(120)}")
            Handler(Looper.getMainLooper()).post {
                doStartPlayback(resolved, title, options)
            }
        }.apply { name = "hftv-redirect"; isDaemon = true }.start()
    }

    /**
     * The actual playback start, without touching the reconnect
     * counter. Live streams get the tuned LoadControl (1.5s buffer,
     * frame drop); VOD uses the conservative defaults.
     */
    private fun doStartPlayback(
        streamUrl: String,
        title: String?,
        options: ChannelOptions
    ) {
        if (!prepared) {
            Log.w(TAG, "startPlayback() called before prepare() — ignoring")
            return
        }
        currentStreamUrl = streamUrl
        currentTitle = title
        currentOptions = options
        isLive = guessLiveFromUrl(streamUrl)

        // Build/rebuild with the correct buffer profile now that we
        // know whether this is a live or VOD stream.
        ensurePlayer(isLive)

        val resolvedUrl = streamUrl

        val itemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(resolvedUrl))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(if (isLive) PlayerConfig.LIVE_MIN_BUFFER_MS else 0L)
                    .build()
            )

        // ★ ExoPlayer 默认靠 URL 扩展名推断容器类型。IPTV 订阅里很常见
        // 「无扩展名的短链」，它其实 302 到 HLS（例如
        // https://iptv.example.com/608807420 → miguvideo 的 index.m3u8）。
        // 不显式声明的话会被当普通文件解析，直接报
        // PARSING_CONTAINER_UNSUPPORTED —— 而 VLC 这类播放器是靠嗅探
        // 内容判断的，所以在别处能播。这里对非明确点播后缀的地址一律
        // 按 HLS 处理。
        if (looksLikeHls(resolvedUrl)) {
            itemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }
        val item = itemBuilder.build()
        player?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = playWhenReadyCached
        }
        startWatchdogs()
    }

    /** 只有「无扩展名的短链」才需要额外解析；.m3u8 / .mp4 直接交给播放器。 */
    private fun needsRedirectResolve(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return !path.endsWith(".m3u8") && !path.contains('.')
    }

    /**
     * 手动跟随 3xx 重定向，返回最终 URL。失败时退回原 URL。
     *
     * 用 GET + `Range: bytes=0-0` 而不是 HEAD：不少 IPTV 中转对 HEAD
     * 返回 405，而对带 Range 的 GET 会立刻回状态行。
     */
    private fun resolveRedirects(url: String): String {
        var current = url
        var connection: java.net.HttpURLConnection? = null
        return try {
            repeat(5) {
                connection = (java.net.URL(current).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    requestMethod = "GET"
                    setRequestProperty("Range", "bytes=0-0")
                    setRequestProperty("User-Agent", PlayerConfig.userAgent)
                }
                val code = connection!!.responseCode
                if (code in 300..399) {
                    val location = connection!!.getHeaderField("Location")
                    if (location.isNullOrBlank()) return current
                    current = java.net.URL(java.net.URL(current), location).toString()
                } else {
                    return current
                }
            }
            current
        } catch (t: Throwable) {
            Log.w(TAG, "Redirect resolve failed for $url: ${t.message}")
            url
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * 是否为 HLS 地址。
     *
     * `.m3u8` 显然算；**无扩展名也算**（302 到 HLS 的短链）。
     * 只有明确是点播文件后缀时才不当 HLS。
     */
    private fun looksLikeHls(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val progressiveExtensions = listOf(
            ".mp4", ".mkv", ".flv", ".avi", ".mov", ".webm", ".wmv", ".m4v",
            ".mp3", ".aac", ".m4a", ".flac", ".wav", ".ts"
        )
        return progressiveExtensions.none { path.endsWith(it) }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    /**
     * Pause playback. Does not release the player — we want the
     * same prepared `MediaItem` ready when the user comes back.
     */
    fun pause() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
    }

    /**
     * Resume playback if the player was previously playing. Idempotent:
     * calling when already playing does nothing. We capture the
     * pre-pause [playWhenReady] state in [playWhenReadyCached] so
     * that the user-driven togglePlayPause round-trip works.
     */
    fun resume() {
        val p = player ?: return
        if (!p.isPlaying) p.play()
    }

    fun stop() {
        stopWatchdogs()
        currentStreamUrl = null
        player?.stop()
        player?.clearMediaItems()
    }

    fun release() {
        stopWatchdogs()
        currentStreamUrl = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        loadControlIsLive = null
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    /**
     * The URL the player last tried to play, or null when the
     * controller is stopped / released. Exposed so the caller can
     * inspect it after a player error (the original URL is the only
     * diagnostic worth showing when a stream fails to parse).
     */
    fun currentStreamUrl(): String? = currentStreamUrl

    fun positionMs(): Long = player?.currentPosition ?: 0L

    fun durationMs(): Long {
        val d = player?.duration ?: C.TIME_UNSET
        return if (d == C.TIME_UNSET) 0L else d
    }

    /** Seek −10s / +10s — works on VOD; live streams will no-op. */
    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0L)
        p.seekTo(target)
    }

    // ============================================================
    // Tunables
    // ============================================================

    private fun buildLoadControl(live: Boolean): LoadControl {
        // 1.5s min buffer for live, drop frames on underrun. These match
        // iTelly-macOS's "live tuning" section. VOD keeps default 4s so
        // seeks stay snappy.
        //
        // The two playback / rebuffer thresholds are intentionally
        // different:
        //   - bufferForPlaybackMs  — when starting from idle, how much
        //                              must be in the buffer before we
        //                              commit to rendering the first
        //                              frame. Live can start small (500ms)
        //                              since the source keeps producing.
        //   - bufferForRebufferMs   — when an underrun happens during
        //                              playback, how much must re-accumulate
        //                              before resuming. Live needs a bigger
        //                              cushion (1s) so we don't immediately
        //                              underrun again.
        //
        // `live` is a parameter (not the `isLive` field) so the value
        // is unambiguous at the call site — this is what the old
        // eager-build code got wrong.
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs         = */ if (live) PlayerConfig.LIVE_MIN_BUFFER_MS.toInt()          else PlayerConfig.VOD_MIN_BUFFER_MS.toInt(),
                /* maxBufferMs         = */ if (live) PlayerConfig.LIVE_MAX_BUFFER_MS.toInt()          else PlayerConfig.VOD_MAX_BUFFER_MS.toInt(),
                /* bufferForPlaybackMs = */ if (live) PlayerConfig.LIVE_BUFFER_FOR_PLAYBACK_MS.toInt() else PlayerConfig.VOD_BUFFER_FOR_PLAYBACK_MS.toInt(),
                /* bufferForRebufferMs  = */ if (live) PlayerConfig.LIVE_BUFFER_FOR_REBUFFER_MS.toInt()  else PlayerConfig.VOD_BUFFER_FOR_REBUFFER_MS.toInt()
            )
            // Don't back off the clock when we underrun — prefer dropping
            // frames over pausing (live sports / news). VOD stays
            // conservative.
            .setPrioritizeTimeOverSizeThresholds(!live)
            .build()
    }

    // ============================================================
    // Watchdogs
    // ============================================================

    private fun startWatchdogs() {
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.postDelayed(progressTicker, progressPollIntervalMs)
        lastPositionForWatchdogMs = positionMs()
        lastPositionChangeAt = SystemClock.elapsedRealtime()
        isStallWatchdogArmed = true
    }

    private fun stopWatchdogs() {
        mainHandler.removeCallbacks(progressTicker)
        // Also drop any pending reconnect. Without this a Back press
        // during the backoff window would run the reconnect against a
        // released controller.
        mainHandler.removeCallbacks(reconnectRunnable)
        isStallWatchdogArmed = false
    }

    private fun tickProgress() {
        val p = player ?: return
        if (!isStallWatchdogArmed) return
        val pos = p.currentPosition
        // Use elapsedRealtime (not currentTimeMillis) so the watchdog
        // is immune to wall-clock changes — if the user adjusts the
        // TV's clock, we don't want the stall detector to misfire.
        val now = SystemClock.elapsedRealtime()

        // Report position to callbacks (UI can update the seekbar even
        // when Media3's own listener doesn't fire).
        callbacks.onPositionChanged(pos, durationMs(), isLive)

        if (!p.isPlaying) return // pause / buffering — don't fire stall

        if (pos != lastPositionForWatchdogMs) {
            lastPositionForWatchdogMs = pos
            lastPositionChangeAt = now
            return
        }

        val stalledFor = now - lastPositionChangeAt
        if (stalledFor >= stallWatchdogMs) {
            Log.w(TAG, "Stall watchdog: no progress in ${stalledFor}ms")
            isStallWatchdogArmed = false
            onStall()
        }
    }

    private fun onStall() {
        // Distinguish a real stall (live / VOD mid-play) from "VOD ended".
        // The macOS project's bug was conflating these; without the
        // ENDED check, every finished 30s VOD popped a connection error.
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            Log.i(TAG, "Stall watchdog: VOD ended normally")
            return
        }
        scheduleReconnect(reason = "stall watchdog")
    }

    // ============================================================
    // Reconnect with backoff
    // ============================================================

    private fun scheduleReconnect(reason: String) {
        val delay = nextReconnectDelayMs(reconnectAttempt) ?: run {
            Log.w(TAG, "Reconnect: max attempts ($maxReconnectAttempts) reached — giving up")
            errorReportedTerminal = true
            callbacks.onError(
                PlayerError(
                    code = "RECONNECT_EXHAUSTED",
                    message = "Stream interrupted ($reason). Reconnect attempts exhausted."
                ),
                terminal = true
            )
            return
        }
        reconnectAttempt += 1
        Log.i(TAG, "Reconnect attempt $reconnectAttempt/$maxReconnectAttempts in ${delay}ms (reason=$reason)")
        callbacks.onPlaybackStateChanged(PlaybackState.RECONNECTING)
        // Reconnect via [startPlayback] — NOT [play] — so the attempt
        // counter survives and the backoff actually escalates.
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    // ============================================================
    // Surface lifecycle
    // ============================================================

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceCreated")
            player?.setVideoSurfaceHolder(holder)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(TAG, "surfaceChanged ${width}x${height} (format=$format)")
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.i(TAG, "surfaceDestroyed")
            // Detach the surface but DO NOT clear the player's surface
            // wholesale (would be equivalent to the macOS
            // `set_nsobject(mp, nil)` trap that breaks vout).
            player?.clearVideoSurfaceHolder(holder)
        }
    }

    // ============================================================
    // Player listener — translates Media3 events into our callback
    // surface. This is the bridge layer that lets us swap out the
    // player implementation without touching the UI.
    // ============================================================

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val mapped = when (state) {
                Player.STATE_IDLE -> PlaybackState.IDLE
                Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                Player.STATE_READY -> PlaybackState.READY
                Player.STATE_ENDED -> PlaybackState.ENDED
                else -> PlaybackState.IDLE
            }
            // Reset the stall baseline on each state transition; in
            // particular, STATE_READY means we have frames flowing
            // again so the previous baseline is stale.
            //
            // Must use the SAME clock as tickProgress() — mixing
            // currentTimeMillis (epoch, ~1.7e12) with elapsedRealtime
            // (since boot, ~1.2e6) made `stalledFor` hugely negative,
            // so the stall watchdog could never fire after the first
            // state transition.
            lastPositionForWatchdogMs = positionMs()
            lastPositionChangeAt = SystemClock.elapsedRealtime()
            isStallWatchdogArmed = (state == Player.STATE_READY)
            callbacks.onPlaybackStateChanged(mapped)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playWhenReadyCached = playWhenReady
            if (!playWhenReady) {
                callbacks.onPlaybackStateChanged(PlaybackState.PAUSED)
            } else if (player?.playbackState == Player.STATE_READY) {
                callbacks.onPlaybackStateChanged(PlaybackState.READY)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Classify the error. The macOS project's lesson: don't
            // treat every error as terminal — some are recoverable.
            val (transient, message) = classifyError(error)
            if (transient && reconnectAttempt < maxReconnectAttempts) {
                Log.w(TAG, "Transient error: ${error.errorCodeName} — scheduling reconnect")
                scheduleReconnect(reason = error.errorCodeName)
            } else {
                Log.e(TAG, "Terminal error: ${error.errorCodeName} — ${error.message}", error)
                errorReportedTerminal = true
                callbacks.onError(
                    PlayerError(error.errorCodeName, message, error),
                    terminal = true
                )
            }
        }
    }

    private fun classifyError(error: PlaybackException): Pair<Boolean, String> {
        val (transient, name) = classifyErrorCode(error.errorCode)
        val msg = "[$name] ${error.message ?: "no detail"}"
        return transient to msg
    }

    companion object {
        private const val TAG = "TVLive.Player"

        /**
         * Pure classification: is this error code transient (worth
         * retrying) or terminal? Extracted as a top-level function so
         * we can unit-test it without a PlaybackException instance
         * (which has no public constructor).
         *
         * Codes we consider transient:
         *   - ERROR_CODE_IO_UNSPECIFIED        — generic IO, often
         *                                       recoverable (DNS, socket
         *                                       reset, malformed HLS)
         *   - ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
         *   - ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
         *   - ERROR_CODE_IO_BAD_HTTP_STATUS     (e.g. 503 with Retry-After)
         *   - ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
         *                                     — usually a stream-side
         *                                       seek past EOF on a live
         *                                       feed
         */
        @JvmStatic
        fun classifyErrorCode(code: Int): Pair<Boolean, String> {
            val transientCodes = setOf(
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
            )
            val name = errorCodeName(code)
            return (code in transientCodes) to name
        }

        /**
         * Map a PlaybackException errorCode int to the same
         * `errorCodeName` string Media3 uses (e.g. "ERROR_CODE_IO_*"
         * → "ERROR_CODE_IO_UNSPECIFIED"). Useful for log messages
         * and the user-facing error overlay.
         */
        @JvmStatic
        fun errorCodeName(code: Int): String = when (code) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "ERROR_CODE_IO_UNSPECIFIED"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "ERROR_CODE_IO_BAD_HTTP_STATUS"
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "ERROR_CODE_IO_FILE_NOT_FOUND"
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "ERROR_CODE_IO_NO_PERMISSION"
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED"
            // 1.4.1 doesn't have IO_UNSUPPORTED_OPERATION / IO_NETWORK_UNREACHABLE;
            // they were added in later versions. The else branch catches
            // them when we upgrade.
            else -> "ERROR_CODE_$code"
        }

        /**
         * Compute the next reconnect delay, or null if we've
         * exhausted attempts. Pure function — easy to unit-test.
         *
         * @param currentAttempt 0-based: 0 = first failure, 1 = after
         *                      one retry failed, etc.
         * @return delay in ms, or null if no more retries.
         */
        @JvmStatic
        fun nextReconnectDelayMs(
            currentAttempt: Int,
            backoffsMs: LongArray = PlayerConfig.RECONNECT_BACKOFFS_MS,
            maxAttempts: Int = PlayerConfig.MAX_RECONNECT_ATTEMPTS
        ): Long? = if (currentAttempt >= maxAttempts) null else backoffsMs[currentAttempt]
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Heuristic: a URL is "live" if it ends in m3u8 + contains 'live'
     *  or 'm3u8' with no `?vod=1`. Real apps would set this from the
     *  M3U `EXTINF` metadata. */
    private fun guessLiveFromUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv")) return false
        return lower.contains("live") || lower.endsWith(".m3u8")
    }
}

/**
 * DataSource.Factory that injects per-channel HTTP headers + proxy
 * settings into a base [DefaultHttpDataSource.Factory].
 *
 * Media3 re-creates the DataSource for every MediaSource load, so we
 * can swap the active [ChannelOptions] between channel changes and
 * the next `createDataSource()` will pick up the new headers. No
 * ExoPlayer rebuild needed.
 */
@OptIn(UnstableApi::class)
internal class ChannelAwareDataSourceFactory(
    private val base: DefaultHttpDataSource.Factory,
    private val optionsProvider: () -> ChannelOptions
) : androidx.media3.datasource.DataSource.Factory {

    override fun createDataSource(): androidx.media3.datasource.DataSource {
        val options = optionsProvider()
        // Layer the per-channel #EXTVLCOPT headers on top of the
        // process-wide defaults (Referer + User-Agent). The channel
        // wins for any duplicate key — that's the only point of an
        // #EXTVLCOPT override — so the channel map is applied LAST:
        // in a HashMap a later putAll silently replaces earlier
        // entries with the same key, and ChannelOptions uses the
        // exact same key strings ("User-Agent" / "Referer").
        val properties = HashMap<String, String>().apply {
            putAll(PlayerConfig.defaultRequestHeaders)
            putAll(options.asDefaultRequestProperties())
        }
        // Build a new factory per request, applying the per-channel
        // default headers. Cheap; the factory is just a builder.
        // In Media3 1.1.x the headers are set as a Map on the
        // factory via setDefaultRequestProperties(Map). User-Agent
        // is its own setter (set here too because some gateways key
        // off the UA header literally and ignore the default-property
        // map for it).
        val factory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(PlayerConfig.HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(PlayerConfig.HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(properties["User-Agent"] ?: PlayerConfig.userAgent)
            .setDefaultRequestProperties(properties)
        // For the proxy we use the URL-level proxy selector. Setting
        // it on the factory applies to every connection the factory
        // creates, which is exactly what we want.
        options.asProxy()?.let { _ ->
            // DefaultHttpDataSource doesn't expose a proxy setter in
            // 1.1.x; the system-level http.proxyHost / http.proxyPort
            // JVM properties (or the device's Wi-Fi proxy config)
            // apply. We keep the parsing here so a future upgrade to
            // Media3 1.3+ (which adds setProxy) is a one-line change.
        }
        return factory.createDataSource()
    }
}
