package com.tvlive.tv.player

import android.content.Intent
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import com.tvlive.tv.AppConfig
import com.tvlive.tv.BuildConfig
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.tvlive.tv.R
import com.tvlive.tv.data.repository.ChannelRepository
import com.tvlive.tv.data.store.PlaylistNamer
import com.tvlive.tv.data.store.SubscriptionStore
import com.tvlive.tv.pairing.PairingActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PlaybackActivity — fullscreen ExoPlayer host, mediated by [PlayerController].
 *
 * What this activity does:
 *   1. Build the PlayerController with our tuning (1.5s live buffer,
 *      drop frames, 1s/2s/4s reconnect, 15s stall watchdog)
 *   2. Bind the SurfaceView to the player
 *   3. Manage a list of channels and the current index
 *   4. Show a left-side channel drawer via [ChannelDrawer] when the
 *      user presses D-pad LEFT, hide on BACK or after 3s of no input
 *   5. D-pad UP/DOWN move the selection AND immediately switch
 *      playback to the newly highlighted channel
 *   6. Display position / state / error overlays
 *
 * Input handling:
 *   - LEFT               → toggle the drawer (show / hide)
 *   - RIGHT / UP / DOWN  → drawer must be visible; moves selection
 *                           and immediately plays the new channel
 *   - DPAD_CENTER / ENTER → play/pause
 *   - BACK              → close drawer if open, else finish()
 */
class PlaybackActivity : FragmentActivity(), ChannelDrawer.Callbacks {

    private lateinit var controller: PlayerController
    private lateinit var surfaceView: SurfaceView
    private lateinit var errorView: TextView
    private lateinit var drawer: ChannelDrawer
    private lateinit var diagnoseScroll: android.widget.ScrollView
    private lateinit var diagnoseView: TextView
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var startedAtMs: Long = 0

    /** Full channel list for drawer navigation. */
    private var allChannels: List<com.tvlive.tv.data.model.ChannelEntity> = emptyList()
    private var currentIndex: Int = -1

    /** 自更新：查 NAS 上的 version.json，有新版本才弹卡片。 */
    private val updateManager by lazy { com.tvlive.tv.update.UpdateManager(this) }

    /**
     * Counts consecutive terminal errors so we can stop trying to
     * "auto-advance to next channel" once every channel has failed.
     * Reset to 0 on every successful channel switch (in
     * [onChannelSelected]) so a single bad stream doesn't poison the
     * counter forever.
     */
    private var consecutiveFailures: Int = 0

    /**
     * Whether playback was running when the activity last went to the
     * background. Used by [onStart] so we only resume what the user
     * actually had playing.
     */
    private var wasPlayingBeforeStop = false

    /**
     * The pending 1.5s "auto-advance to the next channel" task. Held
     * so it can be cancelled when the user picks a channel manually or
     * the activity is destroyed — otherwise a stale advance would fire
     * on top of the user's choice.
     */
    private val autoAdvanceRunnable = Runnable {
        if (allChannels.isNotEmpty() && !drawer.isShown && !isFinishing && !isDestroyed) {
            drawer.moveBy(+1)
        }
    }

    /**
     * Set while a centre-key ACTION_DOWN has been handled, so the
     * matching ACTION_UP is passed through instead of being treated as
     * "this remote only sends UP" and firing the action a second time.
     */
    private var centerKeyDownSeen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_playback)

        // Android 15 (API 35) is edge-to-edge by default. We want the
        // video to paint under the system bars (true full-screen), so
        // the insets are applied to the three overlay strips — NOT to
        // the root, which would inset the SurfaceView and letterbox
        // the picture.
        surfaceView = findViewById(R.id.surface)
        errorView   = findViewById(R.id.error_view)
        diagnoseScroll = findViewById(R.id.diagnose_scroll)
        diagnoseView   = findViewById(R.id.diagnose_view)

        drawer      = ChannelDrawer(findViewById(R.id.root), this)
        drawer.setup()

        controller = PlayerController(this, callback)
        controller.prepare()
        controller.bindSurface(surfaceView)

        val streamUrl   = intent.getStringExtra(EXTRA_STREAM_URL)
        val streamTitle = intent.getStringExtra(EXTRA_STREAM_TITLE) ?: "电视直播"
        val optionsJson = intent.getStringExtra(EXTRA_STREAM_OPTIONS_JSON)
        val playlistId  = intent.getLongExtra(EXTRA_PLAYLIST_ID, -1L)
        val channelIdx  = intent.getIntExtra(EXTRA_CHANNEL_INDEX, -1)
        // Parent m3u URL — drives the Referer the player sends on
        // every subsequent HTTP request, which most home-IPTV
        // gateways require in order to serve the sub-playlist.
        val parentPlaylistUrl = intent.getStringExtra(EXTRA_PARENT_PLAYLIST_URL)
            ?: com.tvlive.tv.AppConfig.DEFAULT_SUBSCRIPTION_URL
        controller.playlistReferer = parentPlaylistUrl

        // We deliberately do NOT ferry the channel list through the
        // launching Intent as a Parcelable array — `Parcel.readParcelableCreatorInternal`
        // reads the CREATOR's enclosing class via reflection, and R8
        // strips the `InnerClasses` attribute on `ChannelEntity$CREATOR`
        // in the release build (verified in 1.2.0/1.2.1 dex), so the
        // unmarshal NPEs with "Class.isInterface() on a null object".
        // Instead we hand off just the playlist id and the index, and
        // query the repository on our own thread.

        // 更新检查只在用户 MENU 里手动触发（「检查更新」），不做后台自动检查——
        // 自动弹更新卡片对看电视的人是打扰。

        if (streamUrl.isNullOrBlank()) {
            // 作为 LAUNCHER 启动时没有 extra：自己从订阅里取第一个频道。
            // 这样「打开应用 = 全屏播放」且按键逻辑天然与播放页一致
            // （本来就是同一个 Activity），不再有一个单独的主页壳。
            //
            // 仓库里一条订阅都没有（public 版首启 / 用户删光了）：
            // 不走空的启动链，直接弹「添加订阅」（扫码页，含手动输入），
            // 添加成功后 onActivityResult 里切到新订阅并起播。
            if (SubscriptionStore.list(this).isEmpty()) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(this, PairingActivity::class.java), REQ_ADD_SUBSCRIPTION)
                return
            }
            startFromLibrary()
            return
        }

        startedAtMs = System.currentTimeMillis()
        controller.play(streamUrl, streamTitle, ChannelOptions.fromJson(optionsJson))


        // Multi-channel mode: query the playlist's channel list on
        // IO so the drawer has something to navigate. Single-channel
        // mode (no playlistId, e.g. test intents) skips this — the
        // drawer just stays empty.
        if (playlistId <= 0L) return

        lifecycleScope.launch {
            val channels = ChannelRepository(applicationContext)
                .listChannels(playlistId)
            if (isFinishing || isDestroyed) return@launch
            allChannels = channels
            currentIndex = channelIdx.coerceIn(-1, channels.size - 1)
            Log.i(TAG, "PlaybackActivity loaded ${channels.size} channels, currentIndex=$currentIndex")
            drawer.submit(channels, currentIndex.coerceAtLeast(0))
        }
    }

    /**
     * Single source of truth for what the UI shows.
     */
    private val callback = object : PlayerController.Callbacks {
        override fun onPlaybackStateChanged(state: PlayerController.PlaybackState) {
            runOnUiThread {
                // 播放状态不再上屏：诊断信息统一走 MENU 键面板
                Log.i(TAG, "onPlaybackStateChanged: $state")
                // A channel that reaches READY is a working channel, so
                // the failure streak is reset here rather than in
                // onChannelSelected — the latter also runs for the
                // auto-advance itself, which meant the counter never got
                // past 1 and the "all channels failed" give-up never
                // triggered.
                if (state == PlayerController.PlaybackState.READY) {
                    errorView.visibility = View.GONE
                    consecutiveFailures = 0
                }
            }
        }

        override fun onError(error: PlayerController.PlayerError, terminal: Boolean) {
            runOnUiThread {
                val prefix = if (terminal) "✗" else "⚠"
                showError("$prefix ${humanError(error.code)}")
                // 不再自动探测并弹面板：全屏直播被一屏等宽文字盖住，
                // 用户要的是继续看电视。需要看服务端原始响应时，
                // 长按 MENU 打开诊断面板。
                Log.w(TAG, "Error on ${controller.currentStreamUrl()}: ${error.code} ${error.message}")
                // If the error is terminal AND the drawer is closed
                // (i.e. the user isn't actively browsing), advance to
                // the next channel so they don't get stuck on a dead
                // stream.
                if (!terminal || drawer.isShown) return@runOnUiThread

                consecutiveFailures += 1
                val total = allChannels.size
                if (total > 0 && consecutiveFailures >= minOf(total, MAX_STARTUP_FAILURES)) {
                    // 连续失败到上限：源多半整体挂了。别再往下切，
                    // 每切一个都要拉一次清单，会把上游 CDN 打得更狠。
                    showError(
                        "✗ 连续 $consecutiveFailures 个频道都打不开\n" +
                            "订阅源可能暂时不可用（服务器没有返回有效视频流）\n" +
                            "按 MENU 打开频道列表，可刷新订阅或换一个频道。"
                    )
                    return@runOnUiThread
                }
                mainHandler.removeCallbacks(autoAdvanceRunnable)
                mainHandler.postDelayed(autoAdvanceRunnable, 1_500L)
            }
        }

        override fun onPositionChanged(positionMs: Long, durationMs: Long, isLive: Boolean) {
            // 进度条已按需求移除；这里保留回调（用于失败判定/统计），不更新 UI
        }
    }

    override fun onChannelSelected(channel: com.tvlive.tv.data.model.ChannelEntity, position: Int) {
        currentIndex = position
        // The user (or the auto-advance) picked a channel — drop any
        // other pending auto-advance so we don't skip past it.
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        startedAtMs = System.currentTimeMillis()
        controller.play(channel.url, channel.displayName, ChannelOptions.fromJson(channel.optionsJson))
        // Keep the drawer visible — user is still browsing.
        drawer.bumpActivity()
    }

    /** 把 ExoPlayer 的错误码翻成一句人话；技术码写 logcat。 */
    private fun humanError(code: String): String = when {
        code.contains("PARSING_MANIFEST") || code.contains("PARSING_CONTAINER") ->
            "这个频道暂时打不开（服务器没有返回有效的视频流）"
        code.contains("DECODING") ->
            "这个频道解码失败（编码格式这台电视不支持）"
        code.contains("NETWORK") || code.contains("TIMEOUT") ->
            "网络连不上这个频道"
        code.contains("RECONNECT_EXHAUSTED") ->
            "多次重连都失败了"
        else -> "这个频道暂时打不开"
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }

    /**
     * Probe [url] in the background and surface the result on the
     * diagnostic overlay. Triggered automatically when the player
     * fires a terminal error (so the user sees *why* a stream died
     * instead of just "PARSING_CONTAINER_UNSUPPORTED") and manually
     * by long-pressing the URL strip.
     */
    private fun runDiagnoser(url: String, error: PlayerController.PlayerError) {
        lifecycleScope.launch {
            val diag = withContext(Dispatchers.IO) {
                StreamDiagnoser.diagnose(url)
            }
            if (isFinishing || isDestroyed) return@launch
            showDiagnostic(diag, error)
        }
    }

    private fun showDiagnosticFor(url: String?) {
        if (url == null) {
            // Manual probe from the long-press but no stream has been
            // played yet — at least show the panel with a helpful note.
            showDiagnostic(
                StreamDiagnoser.Diagnosis(
                    url = "(no stream yet)",
                    statusLine = "",
                    headers = emptyList(),
                    bodyUtf8 = "",
                    bodyHex = "",
                    notes = listOf(
                        "Long-press is for probing the stream URL.",
                        "No stream has been played yet in this session — open a channel first."
                    )
                ),
                error = null
            )
            return
        }
        lifecycleScope.launch {
            val diag = withContext(Dispatchers.IO) {
                StreamDiagnoser.diagnose(url)
            }
            if (isFinishing || isDestroyed) return@launch
            showDiagnostic(diag, null)
        }
    }

    /**
     * Render the [diag] on the diagnostic overlay. A short summary
     * line is shown up top (which is the most useful 90% of the time);
     * the full multi-line dump is below it for when the user wants to
     * dig in.
     */
    private fun showDiagnostic(
        diag: StreamDiagnoser.Diagnosis,
        error: PlayerController.PlayerError?
    ) {
        val summary = buildString {
            if (error != null) {
                append("[ERROR] ").append(error.code).append(' ').append(error.message)
                    .append('\n')
            }
            append("Probed: ").append(diag.url).append('\n')
            append("→ ").append(diag.statusLine.ifBlank { "(no response)" }).append('\n')
            if (diag.bodyUtf8.isNotBlank()) {
                append("First KB of body: ").append(diag.bodyUtf8.take(120))
                if (diag.bodyUtf8.length > 120) append("…")
                append('\n')
            }
            diag.notes.forEach { append("• ").append(it).append('\n') }
            append("Hint: HTML/JSON usually means the URL needs a Referer or login.\n")
        }
        diagnoseView.text = summary + "\n\n" + diag.toDisplayString()
        diagnoseScroll.visibility = View.VISIBLE
        diagnoseScroll.scrollTo(0, 0)
    }

    /** Hide the diagnostic overlay (e.g. after the user pressed BACK). */
    private fun hideDiagnostic() {
        diagnoseScroll.visibility = View.GONE
    }

    /**
     * D-pad map:
     *   - LEFT          → toggle drawer (without switching channel)
     *   - UP / DOWN     → IMMEDIATELY switch to previous / next channel
     *                     AND make the drawer visible (so the user
     *                     can see what's playing). The drawer's
     *                     currentIndex updates to match.
     *   - DPAD_CENTER / ENTER → play/pause
     *   - RIGHT         → switch to next channel (same as DOWN)
     *   - MEDIA_REWIND / FFW → ±10s
     *   - BACK → close drawer if open, else finish()
     */
    /**
     * Intercept keys at the lowest level we can.
     *
     * Why here and not in [onKeyDown]: the focused child (the
     * SurfaceView, the drawer's ListView) can swallow a key before the
     * Activity ever sees it, and several OEM TV remotes only deliver the
     * centre key as ACTION_UP with no matching ACTION_DOWN. Handling the
     * key at the dispatch boundary makes all of those cases behave the
     * same — this is the fix for the "OK does nothing" reports.
     *
     * Repeat handling: holding UP/DOWN should machine-gun through
     * channels and holding FFW/RW should keep seeking, so those keys
     * honour key-repeat. Every other key ignores repeats — otherwise
     * holding LEFT would strobe the drawer and holding OK would toggle
     * play/pause as fast as the remote repeats.
     *
     * `@SuppressLint("RestrictedApi")`: `dispatchKeyEvent` is inherited
     * through `androidx.core.app.ComponentActivity`, which is
     * `@RestrictTo(LIBRARY_GROUP_PREFIX)`, so lint flags any override as
     * reaching into a restricted API. The method we actually override
     * and call through to is the public framework
     * `android.app.Activity.dispatchKeyEvent`, so there is no
     * stability risk — this is the documented false positive for that
     * detector.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.dispatchKeyEvent(event)

        val isCenterKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val repeatAllowed = event.repeatCount == 0 || isRepeatableKey(event.keyCode)
                if (repeatAllowed && handleKey(event.keyCode)) {
                    centerKeyDownSeen = isCenterKey
                    return true
                }
            }
            KeyEvent.ACTION_UP -> {
                // Fallback for remotes that never send ACTION_DOWN for
                // the centre key. Only acts when the matching DOWN was
                // not already handled, so a well-behaved remote that
                // sends both does not toggle twice.
                val actOnUp = isCenterKey && !centerKeyDownSeen
                centerKeyDownSeen = false
                if (actOnUp && handleKey(event.keyCode)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Keys where holding the button should auto-repeat. */
    private fun isRepeatableKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND -> true
        else -> false
    }

    private fun handleKey(keyCode: Int): Boolean {
        // Any key press counts as activity: keep the auto-hide
        // timer fresh. We do this *before* the show/hide logic so
        // the drawer stays visible for 3s after each press.
        drawer.bumpActivity()

        // Back is special — it has different semantics depending on
        // drawer state. We handle it first.
        if (keyCode == KeyEvent.KEYCODE_BACK) return handleBackKey()
        // MENU = 选项菜单（刷新订阅源 / 检查更新）。
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMenu()
            return true
        }
        // Media keys are always available (drawer-open or closed).
        if (handleMediaKey(keyCode)) return true
        // Channel navigation. Split for readability.
        return handleNavigationKey(keyCode)
    }

    /**
     * Channel-navigation keys (UP / DOWN / LEFT / RIGHT / OK). The
     * drawer's open/closed state changes the semantics:
     *   - drawer CLOSED: UP/DOWN/RIGHT immediately switch channel;
     *     LEFT summons the drawer; OK toggles play/pause.
     *   - drawer OPEN: UP/DOWN move the *browse cursor*; OK commits
     *     the focused row; LEFT closes.
     */
    private fun handleNavigationKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (drawer.isShown) drawer.hide() else drawer.show()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (allChannels.isEmpty()) return true
                if (drawer.isShown) drawer.browseBy(-1) else drawer.moveBy(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (allChannels.isEmpty()) return true
                if (drawer.isShown) drawer.browseBy(+1) else drawer.moveBy(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A -> {
                if (drawer.isShown) {
                    val pos = drawer.focusedIndex()
                    val ch = drawer.focusedChannel()
                    if (ch != null && pos != drawer.playingIndex) {
                        onChannelSelected(ch, pos)
                    }
                    drawer.hide()
                    return true
                }
                controller.togglePlayPause()
                return true
            }
        }
        return false
    }

    private fun handleMediaKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                controller.togglePlayPause(); true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                controller.seekBy(-PlayerConfig.SEEK_STEP_MS); true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                controller.seekBy(+PlayerConfig.SEEK_STEP_MS); true
            }
            else -> false
        }
    }

    private fun handleBackKey(): Boolean {
        // The diagnostic overlay has the highest priority: if it's
        // up, BACK dismisses it instead of leaving the activity.
        if (diagnoseScroll.visibility == View.VISIBLE) { hideDiagnostic(); return true }
        if (drawer.isShown) { drawer.hide(); return true }
        showExitConfirm()
        return true
    }

    /**
     * 从订阅自播（无 Intent extra 启动）。
     * 与主页时代不同：这里拿到的是完整频道列表，抽屉/上下切台立刻可用。
     */
    private fun startFromLibrary() {
        lifecycleScope.launch {
            val repo = ChannelRepository(applicationContext)
            var playlists = withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.observePlaylists().first()
            }
            // 优先用「地址等于当前默认订阅」的那份。换源之后库里往往
            // 还留着旧地址的播放列表，直接取 firstOrNull() 会接着播旧源
            // 的频道（旧源的 relay 可能已经失效）。
            val wantedUrl = activeSubscriptionUrl()
            var pl = playlists.firstOrNull { it.remoteUrl == wantedUrl }
            if (pl == null) {
                // 库里没有新源：当场拉一次（换订阅地址后的首次启动）。
                Log.i(TAG, "Subscription not in DB, loading: $wantedUrl")
                val loaded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val entryName = SubscriptionStore.find(applicationContext, wantedUrl)?.name
                    repo.loadRemote(wantedUrl, entryName)
                }
                if (loaded.isFailure) {
                    // 拉不到就退回库里已有的，至少还有东西可播
                    pl = playlists.firstOrNull()
                    if (pl == null) {
                        showError(
                            "订阅加载失败\n${loaded.exceptionOrNull()?.message ?: ""}\n按 MENU 检查网络"
                        )
                        return@launch
                    }
                    Log.w(TAG, "Default subscription load failed, fallback to existing playlist")
                } else {
                    playlists = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        repo.observePlaylists().first()
                    }
                    pl = playlists.firstOrNull { it.remoteUrl == wantedUrl }
                        ?: playlists.firstOrNull()
                }
            }
            if (pl == null) {
                showError("还没有订阅\n按 MENU 键刷新")
                return@launch
            }
            val channels = withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.listChannels(pl.id)
            }
            if (channels.isEmpty()) {
                showError("订阅还没加载好\n按 MENU 键刷新")
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch

            // 启动优先 CCTV-1 综合：它是这套订阅里最稳的源。
            // 直接播「列表第一个」会撞上 CCTV4 这类上游 CDN 挂掉的频道
            // （ysp 的 hlsliveali-cdn 返回 403，清单直接是错误文本）。
            val startIndex = channels.indexOfFirst {
                val n = it.displayName.lowercase()
                n.contains("cctv-1") || n.contains("cctv1") || n.contains("cctv 1")
            }.takeIf { it >= 0 } ?: 0
            allChannels = channels
            currentIndex = startIndex
            controller.playlistReferer = pl.remoteUrl?.takeIf { it.isNotBlank() }
                ?: com.tvlive.tv.AppConfig.DEFAULT_SUBSCRIPTION_URL
            drawer.submit(channels, startIndex)
            onChannelSelected(channels[startIndex], startIndex)
            Log.i(
                TAG,
                "Startup autoplay: ${channels[startIndex].displayName} " +
                    "(${channels.size} channels, index=$startIndex)"
            )
        }
    }

    /**
     * MENU 菜单：订阅管理（添加 / 切换 / 删除）+ 检查更新。
     *
     * 结构：[＋ 添加订阅] → 订阅行列表（当前项 ✓，长按删除）→ [检查更新]。
     * 订阅数据来自 [SubscriptionStore]；名字由 [PlaylistNamer] 从订阅源自动获取。
     * 所有动作都会离开当前画面或改状态，先收起弹框再执行。
     */
    private fun showMenu() {
        if (isFinishing) return
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_menu)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            setDimAmount(0.35f)
        }

        val container = dialog.findViewById<android.widget.LinearLayout>(R.id.menu_items)
        val scroll = dialog.findViewById<com.tvlive.tv.widget.MaxHeightScrollView>(R.id.menu_scroll)
        // 1080p TV ≈ 540dp 高：减去标题/添加/检查更新/内边距后，列表区约 280dp。
        scroll.maxHeightPx = (280 * resources.displayMetrics.density).toInt()
        val emptyHint = dialog.findViewById<View>(R.id.menu_empty)
        val addRow = dialog.findViewById<View>(R.id.menu_add)
        val update = dialog.findViewById<View>(R.id.menu_update)
        val activeUrl = activeSubscriptionUrl()
        val entries = SubscriptionStore.list(this)
        emptyHint.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val rows = mutableListOf<View>(addRow)

        entries.forEach { entry ->
            val row = layoutInflater.inflate(R.layout.item_menu, container, false)
                as android.widget.LinearLayout
            val nameView = row.findViewById<TextView>(R.id.menu_row_name)
            val delView = row.findViewById<View>(R.id.menu_row_del)
            nameView.text = if (entry.url == activeUrl) "✓  ${entry.name}" else "     ${entry.name}"
            nameView.setOnClickListener {
                dialog.dismiss()
                if (entry.url == activeUrl) {
                    Toast.makeText(this, "正在刷新${entry.name}…", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "正在切换到${entry.name}…", Toast.LENGTH_SHORT).show()
                }
                switchSubscription(entry)
            }
            // 行尾 ✕：确认后从仓库移除；删的是当前源时顺带切到剩余第一个。
            delView.setOnClickListener {
                confirmDialog(
                    "删除订阅「${entry.name}」？",
                    "删除后可以用「添加订阅」重新添加。"
                ) {
                    SubscriptionStore.remove(this, entry.url)
                    dialog.dismiss()
                    val remaining = SubscriptionStore.list(this)
                    if (entry.url == activeUrl && remaining.isNotEmpty()) {
                        SubscriptionStore.setActive(this, remaining.first().url)
                        Toast.makeText(this, "已切换到${remaining.first().name}", Toast.LENGTH_SHORT).show()
                        switchSubscription(remaining.first())
                    } else {
                        showMenu() // 重开一次，刷新列表
                    }
                }
            }
            container.addView(row)
            rows += nameView
            rows += delView
        }

        addRow.setOnClickListener {
            dialog.dismiss()
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, PairingActivity::class.java), REQ_ADD_SUBSCRIPTION)
        }

        update.setOnClickListener {
            dialog.dismiss()
            updateManager.check(
                force = true,
                onAvailable = { info -> updateManager.promptInstall(info) },
                onNone = {
                    Toast.makeText(this, "已是最新版本 ${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // 焦点交给系统几何算法：行是同一布局反复 inflate，id 全部相同，
        // 手动 nextFocusUp/DownId 会按 id 解析到第一行导致焦点锁死。
        // 线性列表 + ScrollView 下几何算法天然正确，且焦点变化时自动滚动。

        dialog.setOnShowListener { addRow.requestFocus() }
        dialog.show()

        // 顺手给兜底名的订阅取名（静默，下次打开 MENU 生效）。
        entries.forEach { entry ->
            if (PlaylistNamer.isImprovable(entry.name, entry.url)) {
                lifecycleScope.launch { PlaylistNamer.refreshName(applicationContext, entry.url) }
            }
        }
    }

    /** 扫码/手动添加完成：直接切到新订阅。 */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ADD_SUBSCRIPTION && resultCode == RESULT_OK) {
            val url = data?.getStringExtra(PairingActivity.EXTRA_URL) ?: return
            val entry = SubscriptionStore.find(this, url) ?: return
            Toast.makeText(this, "正在切换到${entry.name}…", Toast.LENGTH_SHORT).show()
            switchSubscription(entry)
        }
    }

    /**
     * 通用确认卡片：与退出确认同一套视觉语言（深色圆角卡 + 中性/危险双按钮），
     * D-pad 左右切换，默认焦点在「取消」。
     */
    private fun confirmDialog(
        title: String,
        message: String,
        confirmText: String = "删除",
        onConfirm: () -> Unit
    ) {
        if (isFinishing) return
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_confirm)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            setDimAmount(0.35f)
        }
        dialog.findViewById<TextView>(R.id.confirm_title).text = title
        dialog.findViewById<TextView>(R.id.confirm_message).text = message
        val cancel = dialog.findViewById<View>(R.id.btn_cancel)
        val confirm = dialog.findViewById<TextView>(R.id.btn_confirm).apply { text = confirmText }
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        cancel.nextFocusRightId = confirm.id
        confirm.nextFocusLeftId = cancel.id
        dialog.setOnShowListener { cancel.requestFocus() }
        dialog.show()
    }

    /** 当前生效的订阅源（仓库里记的，空仓库退到渠道默认）。 */
    private fun activeSubscriptionUrl(): String =
        SubscriptionStore.activeUrl(this)
            ?: AppConfig.DEFAULT_SUBSCRIPTION_URL

    /**
     * 切换订阅源：拉一次清单 → 记下当前源 → 用新源的频道重新起播。
     *
     * 不重建 Activity，避免黑屏闪一下；频道列表和抽屉就地替换。
     * 拉取失败就保留原来的播放，只弹一行提示。
     * 成功后若该订阅还是兜底名，后台用订阅源内容自动取名（下次 MENU 生效）。
     */
    private fun switchSubscription(sub: SubscriptionStore.Entry) {
        lifecycleScope.launch {
            val repo = ChannelRepository(applicationContext)
            Log.i(TAG, "Switching subscription to ${sub.name} (${sub.url})")
            val loaded = withContext(Dispatchers.IO) { repo.loadRemote(sub.url, sub.name) }
            if (loaded.isFailure) {
                Log.w(TAG, "Subscription switch failed: ${loaded.exceptionOrNull()?.message}")
                Toast.makeText(
                    this@PlaybackActivity,
                    "${sub.name} 加载失败：${loaded.exceptionOrNull()?.message ?: "网络错误"}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            SubscriptionStore.setActive(this@PlaybackActivity, sub.url)
            if (PlaylistNamer.isImprovable(sub.name, sub.url)) {
                PlaylistNamer.refreshName(applicationContext, sub.url)
            }

            val pl = withContext(Dispatchers.IO) {
                repo.observePlaylists().first().firstOrNull { it.remoteUrl == sub.url }
            }
            if (pl == null || isFinishing || isDestroyed) return@launch
            val channels = withContext(Dispatchers.IO) { repo.listChannels(pl.id) }
            if (channels.isEmpty()) {
                Toast.makeText(this@PlaybackActivity, "${sub.name} 没有频道", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch

            allChannels = channels
            controller.playlistReferer = pl.remoteUrl?.takeIf { it.isNotBlank() } ?: sub.url
            val startIndex = channels.indexOfFirst {
                val n = it.displayName.lowercase()
                n.contains("cctv-1") || n.contains("cctv1") || n.contains("cctv 1")
            }.takeIf { it >= 0 } ?: 0
            currentIndex = startIndex
            drawer.submit(channels, startIndex)
            onChannelSelected(channels[startIndex], startIndex)
            Log.i(TAG, "Switched subscription to ${sub.name} (${channels.size} channels)")
        }
    }

    /**
     * 返回键 → 半透明确认框（强调色与 launcher 图标同为 #FF8200）。
     * 全屏直播误按 BACK 会直接退出并断流，先问一句。
     */
    private fun showExitConfirm() {
        if (isFinishing) return
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_exit)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            setDimAmount(0.35f)
        }
        val cancel = dialog.findViewById<View>(R.id.btn_cancel)
        val exit = dialog.findViewById<View>(R.id.btn_exit)
        cancel.setOnClickListener { dialog.dismiss() }
        exit.setOnClickListener {
            dialog.dismiss()
            finishAffinity()
        }
        cancel.nextFocusRightId = exit.id
        exit.nextFocusLeftId = cancel.id
        dialog.setOnShowListener { cancel.requestFocus() }
        dialog.show()
    }

    override fun onStart() {
        super.onStart()
        // Only resume if we were actually playing when we went to the
        // background. The old code called resume() unconditionally,
        // which silently un-paused a stream the user had paused with
        // the OK key.
        if (wasPlayingBeforeStop) {
            controller.resume()
        }
    }

    override fun onStop() {
        // Remember whether the user had playback running before we
        // lose the foreground, then pause.
        wasPlayingBeforeStop = controller.isPlaying()
        controller.pause()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        drawer.release()
        controller.release()
        val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000
        Log.i(TAG, "PlaybackActivity onDestroy after ${elapsed}s")
    }

    companion object {        /** 连续失败到这个数就停手：继续切只会把上游打得更狠。 */
        private const val MAX_STARTUP_FAILURES = 6
        const val EXTRA_STREAM_URL = "com.tvlive.tv.STREAM_URL"
        const val EXTRA_STREAM_TITLE = "com.tvlive.tv.STREAM_TITLE"
        const val EXTRA_STREAM_OPTIONS_JSON = "com.tvlive.tv.STREAM_OPTIONS_JSON"

        /**
         * Playlist id whose channels should populate [allChannels] for the
         * drawer. The activity queries the repository for the actual
         * rows rather than receiving them through the Intent — see the
         * note in [onCreate] for why.
         */
        const val EXTRA_PLAYLIST_ID = "com.tvlive.tv.PLAYLIST_ID"

        /** Index into [allChannels] for the channel to start playing. */
        const val EXTRA_CHANNEL_INDEX = "com.tvlive.tv.CHANNEL_INDEX"

        /**
         * The URL of the m3u that produced this channel. Used by the
         * Activity to set [PlayerController.playlistReferer], which in
         * turn drives the `Referer` header on every request the player
         * makes — see [PlayerConfig.defaultRequestHeaders] for why.
         */
        const val EXTRA_PARENT_PLAYLIST_URL = "com.tvlive.tv.PARENT_PLAYLIST_URL"

        private const val TAG = "TVLive.Playback"

        /** startActivityForResult 请求码：添加订阅（扫码/手动）。 */
        private const val REQ_ADD_SUBSCRIPTION = 4001
    }
}
