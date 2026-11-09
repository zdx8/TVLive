package com.tvlive.tv.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.tvlive.tv.AppConfig
import com.tvlive.tv.CrashLog
import com.tvlive.tv.R
import com.tvlive.tv.data.model.ChannelEntity
import com.tvlive.tv.data.repository.ChannelRepository
import com.tvlive.tv.data.repository.SubscriptionRefresher
import com.tvlive.tv.player.PlaybackActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * MainActivity — the home screen, M3-onwards.
 *
 * Layout (top to bottom):
 *   ┌─────────────────────────────────────────────────────┐
 *   │  TVLive                          [⟳  Reload]      │  ← header
 *   │  ─────────────────────────────────────────────────  │
 *   │  ⏳  Loading / ✓ N channels / ⚠ Retrying / ✗ Failed │  ← status line
 *   │                                                     │
 *   │   ▸  Channel 1                                      │
 *   │      Channel 2                                      │  ← ListView
 *   │      …                                              │
 *   │                                                     │
 *   │  #123  (number buffer indicator, 3s timeout)        │  ← number buffer
 *   └─────────────────────────────────────────────────────┘
 *
 * Inputs:
 *   - D-pad UP/DOWN            → move focus through the list
 *   - D-pad OK / ENTER         → play the focused channel
 *   - Long-press OK on a row   → context menu (Play / Toggle favorite)
 *   - Number keys 0-9          → accumulate into a buffer; on each
 *                               press, jump to that 1-based index
 *   - Back                     → exit app
 *
 * Network:
 *   - On launch, kicks off [SubscriptionRefresher]; the DB is
 *     consulted first so we always show *something* even if the
 *     network is dead.
 */
class MainActivity : FragmentActivity() {

    private lateinit var repository: ChannelRepository
    private lateinit var refresher: SubscriptionRefresher
    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private lateinit var reloadButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var header: TextView
    private lateinit var numberBufferView: TextView

    private lateinit var channelAdapter: ChannelRowAdapter
    private var currentChannels: List<ChannelEntity> = emptyList()
    private var currentPlaylistId: Long = -1L
    private var currentPlaylistName: String = ""
    private var currentSubscriptionUrl: String? = null
    private val numberBuffer = ChannelIndexBuffer()

    /**
     * One-shot guard for the cold-start "jump straight into the first
     * channel" behaviour. It stays `true` for the rest of the process
     * after the first auto-play: if we reset it we would re-enter the
     * player on every subsequent Room emission, which is exactly what
     * made the home screen unusable after the user pressed Back.
     */
    private var hasAutoPlayedFirstChannel = false

    /** Set when we start the player, consumed by [onResume]. */
    private var cameFromPlayback = false

    /** Row the user last started playback from, so Back lands on it. */
    private var lastPlayedIndex: Int = 0

    /**
     * Catch-all handler for uncaught exceptions inside our coroutines.
     * Without this, a Room migration mismatch or NPE inside a flow
     * collector crashes the app silently. We log to the standard tag
     * and surface a non-fatal status to the user.
     *
     * We also guard against touching the view tree after the activity
     * is finishing or destroyed — Room can re-emit on its background
     * executor and we'd otherwise leak a reference to the old view
     * hierarchy (the bug that lints `Leaking this` in coroutines).
     */
    private val coroutineErrorHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine error", throwable)
        if (isFinishing || isDestroyed) return@CoroutineExceptionHandler
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            statusView.text = "✗ ${throwable.javaClass.simpleName}: ${throwable.message ?: "?"}"
            statusView.setTextColor(ColorExt.statusError(this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wrap the whole setup in try/catch so that a crash during
        // inflation (e.g. a missing layout attribute, a buggy OEM
        // resource overlay) doesn't kill the app silently. We
        // degrade to a plain TextView showing the error so the
        // user — and our crash handler — can still see what
        // happened.
        try {
            setContentView(R.layout.activity_main)
        } catch (t: Throwable) {
            showFatalError("setContentView failed", t)
            return
        }

        try {
            setupMainScreen()
        } catch (t: Throwable) {
            showFatalError("setupMainScreen failed", t)
            return
        }

        // 主页「刷新订阅」胶囊通过 extra 进来：立刻拉一次订阅。
        // 清掉 extra，避免 Activity 重建时重复发起。
        if (intent?.getBooleanExtra(EXTRA_TRIGGER_REFRESH, false) == true) {
            intent.removeExtra(EXTRA_TRIGGER_REFRESH)
            refreshSubscription()
        }
    }

    /**
     * singleTask 模式下，从主页再次点「电视订阅 / 刷新订阅」不会新建
     * 实例，而是复用当前实例并走这里 —— 刷新的语义必须在这里也生效。
     */
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_TRIGGER_REFRESH, false) == true) {
            intent.removeExtra(EXTRA_TRIGGER_REFRESH)
            refreshSubscription()
        }
    }

    private fun setupMainScreen() {
        repository = ChannelRepository(applicationContext)
        refresher = SubscriptionRefresher(repository, lifecycleScope)

        statusView = findViewById(R.id.status)
        listView = findViewById(R.id.channel_list)
        reloadButton = findViewById(R.id.reload_button)
        findViewById<View>(R.id.update_button).setOnClickListener {
            // force=true：忽略 6 小时节流，手动点就必须真的查
            val updates = com.tvlive.tv.update.UpdateManager(this)
            updates.check(
                force = true,
                onAvailable = { info -> updates.promptInstall(info) },
                onNone = {
                    android.widget.Toast.makeText(
                        this, "已是最新版本", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
        progress = findViewById(R.id.progress)
        header = findViewById(R.id.header)
        numberBufferView = findViewById(R.id.number_buffer)

        // Android 15 (API 35) is edge-to-edge by default. Without
        // this padding, the channel-list header would render under
        // the system status bar (the row with the clock / signal
        // strength on TVs). We only need the top inset — there's no
        // soft-nav bar on a TV remote, and we want the channel list
        // to extend to the very bottom of the screen.
        EdgeToEdgeInsets.apply(
            root = findViewById(android.R.id.content),
            applyTop = true,
            applyBottom = false,
            applyLeft = true,
            applyRight = true
        )

        header.text = getString(R.string.app_name)
        reloadButton.setOnClickListener { refreshSubscription() }
        // Long-press the Reload button → prompt for a new URL. Useful
        // on the bench: you can swap the subscription URL without
        // rebuilding the app. Mirrors the iTelly-macOS "Subscribe to
        // URL" flow, just hidden behind a long-press.
        reloadButton.setOnLongClickListener {
            promptForUrl()
            true
        }

        channelAdapter = ChannelRowAdapter(this)
        listView.adapter = channelAdapter

        // Click on a row → play
        listView.setOnItemClickListener { _, _, position, _ ->
            val channel = currentChannels.getOrNull(position) ?: return@setOnItemClickListener
            playChannel(channel)
        }

        // Long-press on a row → context menu (Play / Toggle favorite)
        listView.setOnItemLongClickListener { _, view, position, _ ->
            val channel = currentChannels.getOrNull(position) ?: return@setOnItemLongClickListener false
            ChannelContextMenu.show(
                anchor = view,
                channel = channel,
                onToggleFavorite = { id, newState -> toggleFavorite(id, newState) },
                onPlay = { ch -> playChannel(ch) }
            )
            true
        }

        observeRefresherState()

        // Cold-start load of the default subscription.
        //
        // This used to fire only when the playlist table was completely
        // empty. That meant changing [AppConfig.DEFAULT_SUBSCRIPTION_URL]
        // (or moving the IPTV box to a new address) had no effect on an
        // install that already had the old playlist cached: the stale row
        // was non-empty, so we never re-fetched, and the app kept trying
        // the dead source forever.
        //
        // Instead we refresh whenever the *default* source is not among
        // the stored playlists. Deliberately not "always refresh": a user
        // who imported a local file or a different URL should not have
        // the network hit on every launch.
        lifecycleScope.launch(coroutineErrorHandler) {
            val playlists = repository.observePlaylists().first()
            val defaultAlreadyStored = playlists.any {
                it.remoteUrl == AppConfig.DEFAULT_SUBSCRIPTION_URL
            }
            if (!defaultAlreadyStored) {
                refreshSubscription()
            }
        }
    }

    /**
     * Last-resort error display. We swap the whole view tree for a
     * single TextView containing the throwable's message and stack.
     * This runs on the main thread so the user sees it immediately
     * and we don't have to worry about coroutine contexts.
     */
    private fun showFatalError(stage: String, t: Throwable) {
        Log.e(TAG, "$stage: ${t.javaClass.simpleName}: ${t.message}", t)
        try {
            // Write to a file in the app's external files dir so we
            // can pull it via `adb pull` without root.
            CrashLog.write(applicationContext, stage, t)
        } catch (_: Throwable) {
            // Even file IO failed — fine, logcat still has the trace.
        }
        val text = buildString {
            append("⚠ 电视直播启动失败\n\n")
            append("Stage: $stage\n")
            append("Error: ${t.javaClass.name}\n")
            append("Message: ${t.message ?: "(none)"}\n\n")
            append("Full trace written to:\n")
            append(CrashLog.filePath(applicationContext))
            append("\n\nPull it with:\n")
            append("adb pull ")
            append(CrashLog.filePath(applicationContext))
            append(" ~/Desktop/")
        }
        val tv = TextView(this).apply {
            setText(text)
            setTextColor(ColorExt.statusError(this@MainActivity))
            setBackgroundColor(ColorExt.surfaceDark(this@MainActivity))
            setPadding(48, 48, 48, 48)
            textSize = 14f
        }
        setContentView(tv)
    }

    override fun onResume() {
        super.onResume()
        // Returning from the player.
        //
        // On the very first launch [renderChannels] auto-plays the first
        // channel and returns *before* it ever calls
        // `channelAdapter.submit(...)` — the ListView was deliberately
        // left un-rendered to avoid a one-frame flash of the list before
        // the activity transition. Room does not re-emit just because we
        // came back from another activity, so without an explicit
        // re-render here the home screen stays an empty ListView for the
        // rest of the session. That was the "Back from playback shows a
        // blank list" bug.
        if (cameFromPlayback) {
            cameFromPlayback = false
            renderCurrentList()
        }
        // After returning from PlaybackActivity, re-anchor the list
        // focus so D-pad is immediately usable.
        if (currentChannels.isNotEmpty()) {
            listView.requestFocus()
        }
    }

    /**
     * Paint the list from the channels we already hold, without waiting
     * for Room to re-emit. Used when coming back from the player.
     */
    private fun renderCurrentList() {
        if (isFinishing || isDestroyed) return
        if (currentChannels.isEmpty()) {
            // The playlist was deleted (or never loaded) while we were
            // in the player — go back to the network for a fresh copy.
            refreshSubscription()
            return
        }
        channelAdapter.submit(currentChannels)
        listView.visibility = View.VISIBLE
        progress.visibility = View.GONE
        statusView.text = "${currentChannels.size} channels · $currentPlaylistName"
        statusView.setTextColor(ColorExt.statusOk(this))
        // Land the cursor back on the channel the user was watching,
        // not on row 0 — pressing Back from the player and then OK
        // should resume what they were on.
        listView.setSelection(lastPlayedIndex.coerceIn(0, currentChannels.size - 1))
    }

    /**
     * Key handling lives at the Activity boundary rather than in
     * [onKeyDown] so the result does not depend on which child currently
     * holds focus:
     *
     *   - a `ListView` fires its own item-click on OK,
     *   - a focused `Button` (our Reload button) consumes OK itself, so
     *     `onKeyDown` never runs,
     *   - some OEM TV remotes only deliver the centre key as
     *     `dispatchKeyEvent` and never as `onKeyDown`.
     *
     * We intercept only the digit keys here (they are unambiguous — no
     * widget wants them) and let everything else fall through to the
     * normal focus navigation.
     *
     * `@SuppressLint("RestrictedApi")`: `dispatchKeyEvent` is inherited
     * through `androidx.core.app.ComponentActivity`, which is
     * `@RestrictTo(LIBRARY_GROUP_PREFIX)`, so lint flags any override as
     * reaching into a restricted API. The method actually being
     * overridden / called through is the public framework
     * `android.app.Activity.dispatchKeyEvent` — the documented false
     * positive for that detector.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            val digit = digitFromKeyCode(event.keyCode)
            if (digit != null) {
                handleDigit(digit)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * OK / ENTER safety net.
     *
     * Only acts when the channel list actually holds focus — reading
     * `listView.selectedItemPosition` while the Reload button is focused
     * returns a stale row and would start playback instead of reloading.
     * (`ViewGroup.hasFocus()` is true when the view itself *or* any
     * descendant, i.e. a row, has focus.)
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER) &&
            listView.hasFocus()
        ) {
            val pos = listView.selectedItemPosition
            val channel = currentChannels.getOrNull(pos)
            if (channel != null) {
                playChannel(channel)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun digitFromKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
        else -> null
    }

    private fun handleDigit(digit: Int) {
        if (currentChannels.isEmpty()) return
        val buffer = numberBuffer.onDigit(digit) ?: return
        val target = buffer.toIntOrNull() ?: return
        if (target < 1) return
        val pos = target - 1   // 1-based → 0-based
        if (pos >= currentChannels.size) {
            // Out of range — show a brief hint and don't move.
            // The "#" is added by flashNumberBuffer, so don't
            // include it here (the old code produced "##123").
            flashNumberBuffer("$buffer (out of range)")
            return
        }
        listView.setSelection(pos)
        // Move focus to the list so the selection is visible.
        listView.requestFocus()
        flashNumberBuffer(buffer)
    }

    private fun flashNumberBuffer(text: String) {
        numberBufferView.text = "#$text"
        numberBufferView.visibility = View.VISIBLE
        numberBufferView.removeCallbacks(hideNumberBuffer)
        numberBufferView.postDelayed(hideNumberBuffer, NUMBER_BUFFER_DISPLAY_MS)
    }

    private val hideNumberBuffer = Runnable {
        numberBufferView.visibility = View.GONE
        numberBuffer.clear()
    }

    // ---------- Network / data ----------

    private fun refreshSubscription() {
        refresher.refresh(currentSubscriptionUrl ?: AppConfig.DEFAULT_SUBSCRIPTION_URL)
    }

    /**
     * Show an input dialog so the user can paste a different M3U URL.
     * The new URL is kept only for the rest of the session; the
     * default [AppConfig.DEFAULT_SUBSCRIPTION_URL] is what a fresh
     * install will use.
     */
    private fun promptForUrl() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/playlist.m3u"
            setText(currentSubscriptionUrl ?: AppConfig.DEFAULT_SUBSCRIPTION_URL)
            setSelection(text.length)
        }
        // framework 版 AlertDialog：项目主题是原生 Material，AppCompat 版会崩。
        android.app.AlertDialog.Builder(this)
            .setTitle("Load subscription URL")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    currentSubscriptionUrl = url
                    refresher.refresh(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeRefresherState() {
        lifecycleScope.launch(coroutineErrorHandler) {
            refresher.state.collect { state ->
                when (state) {
                    is SubscriptionRefresher.State.Idle -> {
                        progress.visibility = View.GONE
                    }
                    is SubscriptionRefresher.State.Loading -> {
                        progress.visibility = View.VISIBLE
                        statusView.text = state.message
                        statusView.setTextColor(ColorExt.statusMuted(this@MainActivity))
                    }
                    is SubscriptionRefresher.State.Retrying -> {
                        progress.visibility = View.VISIBLE
                        statusView.text =
                            "⚠ Retrying (${state.attempt}/3) in ${state.nextRetryMs / 1000}s …"
                        statusView.setTextColor(ColorExt.statusWarn(this@MainActivity))
                    }
                    is SubscriptionRefresher.State.Failed -> {
                        progress.visibility = View.GONE
                        statusView.text = state.message
                        statusView.setTextColor(ColorExt.statusError(this@MainActivity))
                    }
                }
            }
        }
        // Always observe the channel list (whether or not the network
        // is alive) so we have something to show after a failed reload.
        lifecycleScope.launch(coroutineErrorHandler) {
            repository.observePlaylists().collect { playlists ->
                val first = playlists.firstOrNull() ?: return@collect
                repository.observeChannels(first.id).collect { channels ->
                    renderChannels(channels, first.id, first.name)
                }
            }
        }
    }

    private fun renderChannels(channels: List<ChannelEntity>, playlistId: Long, playlistName: String) {
        // Don't clobber a "Retrying…" / "Failed" status message
        // while the network is in the middle of doing something.
        // Also: if the activity is going away, don't touch the view
        // tree (Room can re-emit from its IO executor; touching the
        // old views would leak the activity reference).
        if (isFinishing || isDestroyed) return
        currentChannels = channels
        currentPlaylistId = playlistId
        currentPlaylistName = playlistName
        if (channels.isEmpty()) {
            // Clear the adapter too — otherwise the previous
            // playlist's rows stay on screen underneath the error
            // message, which is how a deleted/emptied playlist ends up
            // looking like it still has channels.
            channelAdapter.submit(emptyList())
            listView.visibility = View.VISIBLE
            showError("No channels in $playlistName")
            return
        }

        // Auto-play path: when we have a list AND haven't auto-played
        // yet, jump straight into the player WITHOUT rendering the
        // list. This avoids the visual "flash" of the list before
        // the activity transition. The list is still kept in
        // currentChannels so the player activity can receive it
        // via Intent extras.
        if (!hasAutoPlayedFirstChannel) {
            hasAutoPlayedFirstChannel = true
            playChannel(channels.first())
            return
        }

        // Manual path: from here the user has already been to the
        // player at least once and is now back on the list (e.g.
        // pressed Back). Render the list normally.
        channelAdapter.submit(channels)
        listView.visibility = View.VISIBLE
        if (refresher.state.value is SubscriptionRefresher.State.Idle) {
            statusView.text = "${channels.size} channels · $playlistName"
            statusView.setTextColor(ColorExt.statusOk(this@MainActivity))
        }
        progress.visibility = View.GONE
        if (!listView.hasFocus() && currentFocus == null) {
            listView.requestFocus()
            listView.setSelection(0)
        }
    }

    private fun toggleFavorite(channelId: Long, newState: Boolean) {
        lifecycleScope.launch(coroutineErrorHandler) {
            repository.setFavorite(channelId, newState)
            // Force the adapter to re-render the row so the star
            // indicator updates.
            val list = repository.observePlaylists().first().firstOrNull()
                ?.let { pl -> repository.observeChannels(pl.id).first() }
                ?: return@launch
            channelAdapter.submit(list)
        }
    }

    // ---------- Playback ----------

    private fun playChannel(channel: ChannelEntity) {
        // Match on the primary key first: two rows can legitimately be
        // equal by value (same name/URL from two groups), and
        // `indexOf` would then always pick the first one, so the drawer
        // would open on the wrong row.
        val idx = currentChannels.indexOfFirst { it.id == channel.id }
            .takeIf { it >= 0 }
            ?: currentChannels.indexOf(channel)

        // Identify the playlist by id — PlaybackActivity re-reads the
        // channel list from the repository on its own thread rather
        // than receiving a Parcelable over the Intent. See the note on
        // [ChannelRepository.listChannels] for the R8 reason.
        val firstPlaylistId = currentPlaylistId
        // Pass the subscription URL so PlaybackActivity can set it as
        // the Referer for every request the player makes — gateways
        // commonly require the Referer to match the playlist document
        // that originally fetched the channel URL.
        val parentPlaylistUrl = currentSubscriptionUrl ?: AppConfig.DEFAULT_SUBSCRIPTION_URL
        val intent = Intent(this, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_STREAM_URL, channel.url)
            putExtra(PlaybackActivity.EXTRA_STREAM_TITLE, channel.displayName)
            putExtra(PlaybackActivity.EXTRA_STREAM_OPTIONS_JSON, channel.optionsJson)
            putExtra(PlaybackActivity.EXTRA_PLAYLIST_ID, firstPlaylistId)
            putExtra(PlaybackActivity.EXTRA_CHANNEL_INDEX, idx)
            putExtra(PlaybackActivity.EXTRA_PARENT_PLAYLIST_URL, parentPlaylistUrl)
        }
        if (idx >= 0) lastPlayedIndex = idx
        // Mark that the next onResume came from the player, so we
        // re-render the list instead of jumping straight back in.
        cameFromPlayback = true
        startActivity(intent)
    }

    // ---------- Display helpers ----------

    private fun showError(message: String) {
        statusView.text = message
        statusView.setTextColor(ColorExt.statusError(this))
        progress.visibility = View.GONE
    }

    companion object {
        private const val TAG = "TVLive.Main"
        private const val NUMBER_BUFFER_DISPLAY_MS = 1_500L

        /** 由 [HomeActivity] 的「刷新订阅」胶囊传入：true = 进来就刷新一次。 */
        const val EXTRA_TRIGGER_REFRESH = "com.tvlive.tv.TRIGGER_REFRESH"
    }
}
