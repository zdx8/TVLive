package com.tvlive.tv.player

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import com.tvlive.tv.R
import com.tvlive.tv.data.model.ChannelEntity

/**
 * ChannelDrawer — the left-side overlay that lets a TV viewer
 * browse channels while a stream is playing in the background.
 *
 * Behaviour:
 *   - 30% width drawer sliding over the video (left side).
 *   - Default state: HIDDEN. Auto-shows on D-pad LEFT, hides on
 *     BACK, or auto-hides after [AUTO_HIDE_MS] of no key input.
 *   - UP/DOWN navigate the list. Selecting a row invokes
 *     [onChannelSelected] so the caller can switch playback.
 *   - ENTER (or any "show" key) also counts as activity → resets
 *     the auto-hide timer.
 *
 * The drawer is a pure helper; it owns no player state. The
 * activity manages "current channel index" and forwards the
 * drawer events through [Callbacks].
 */
class ChannelDrawer(
    private val rootView: ViewGroup,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onChannelSelected(channel: ChannelEntity, position: Int)
    }

    private var listView: ListView? = null
    private var adapter: ChannelDrawerAdapter? = null
    private var channels: List<ChannelEntity> = emptyList()
    private var currentIndex: Int = -1
    /**
     * The D-pad "browse cursor". Tracks where the user has navigated
     * with UP/DOWN while the drawer is open. Starts equal to
     * [currentIndex] so the first OK after the drawer opens acts on
     * the currently-playing channel (a "close drawer" gesture).
     *
     * We can't rely on [ListView.selectedItemPosition] because the
     * ListView only updates that when *it* owns the D-pad focus.
     * Our activity intercepts OK before the ListView sees it, so the
     * ListView never knows about our key events. We track the
     * cursor ourselves via [browseBy] / [submit].
     */
    private var browseIndex: Int = -1
    private var state: State = State.HIDDEN

    private val hideRunnable = Runnable { hide() }
    private val rootHandler = android.os.Handler(android.os.Looper.getMainLooper())

    enum class State { HIDDEN, SHOWN }

    /**
     * Initialise the drawer's ListView, attach it to the host
     * container. Must be called once when the activity's view is
     * inflated.
     */
    fun setup() {
        if (listView != null) return
        val host = rootView.findViewById<FrameLayout>(R.id.drawer_host) ?: return
        val view = android.view.LayoutInflater.from(rootView.context)
            .inflate(R.layout.view_channel_drawer, host, false)
        // The drawer must only cover the left ~30% of the screen so the
        // video keeps playing, visible, on the right. A FrameLayout
        // can't express a percentage in XML, so we compute the width
        // here from the real display metrics (which also handles the
        // 720p/1080p/4K spread across TV boxes). The XML root declares
        // MATCH_PARENT and is deliberately overridden.
        val metrics = rootView.resources.displayMetrics
        val minWidthPx = (MIN_DRAWER_WIDTH_DP * metrics.density).toInt()
        val drawerWidthPx = (metrics.widthPixels * DRAWER_WIDTH_FRACTION)
            .toInt()
            .coerceAtLeast(minWidthPx)
        host.addView(view, FrameLayout.LayoutParams(
            drawerWidthPx,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        listView = view.findViewById(R.id.drawer_list)
        adapter = ChannelDrawerAdapter(rootView.context)
        listView?.adapter = adapter
        listView?.setOnItemClickListener { _, _, position, _ ->
            val ch = channels.getOrNull(position) ?: return@setOnItemClickListener
            callbacks.onChannelSelected(ch, position)
        }
    }

    /**
     * Push a new channel list into the drawer. [currentIndex] is
     * the row that should be highlighted (the one currently playing).
     */
    fun submit(newChannels: List<ChannelEntity>, currentIndex: Int) {
        this.channels = newChannels
        this.currentIndex = currentIndex
        // The browse cursor starts on the currently-playing row.
        this.browseIndex = currentIndex
        adapter?.submit(newChannels, currentIndex)
        if (currentIndex in newChannels.indices) {
            listView?.setSelection(currentIndex)
        }
    }

    /**
     * Update the "currently playing" highlight without reloading
     * the list. Called when the activity switches channels so the
     * highlight follows the new channel. Also re-anchors the
     * browse cursor to the new playing row.
     */
    fun setCurrentIndex(index: Int) {
        if (index == currentIndex) return
        currentIndex = index
        // Re-anchor the browse cursor too: if the user is on a
        // different row when the channel changes (e.g. switched
        // via drawer.moveBy), don't lose their place.
        browseIndex = index
        adapter?.setCurrentIndex(index)
        if (index in channels.indices) {
            listView?.setSelection(index)
        }
    }

    /**
     * Move the highlight up or down AND switch playback. Used
     * when the drawer is *not* shown — a quick "channel surf" on
     * the D-pad without any UI noise.
     *
     * Wraps at the ends (last → first, first → last). That is the
     * set-top-box convention, and — more importantly — it means the
     * auto-advance-on-error path can't get permanently stuck on the
     * final channel. A one-channel list still returns null (no
     * movement), so there is no callback loop.
     */
    fun moveBy(delta: Int): ChannelEntity? {
        val newIndex = ChannelNavigation.wrapIndex(currentIndex, delta, channels.size)
        if (newIndex < 0 || newIndex == currentIndex) return null
        currentIndex = newIndex
        browseIndex = newIndex
        adapter?.setCurrentIndex(newIndex)
        listView?.setSelection(newIndex)
        val ch = channels[newIndex]
        callbacks.onChannelSelected(ch, newIndex)
        return ch
    }

    /**
     * Move the browse cursor up or down WITHOUT switching playback.
     * Used when the drawer is open — the user is browsing the
     * list and will press OK to commit the selection. Clamped (not
     * wrapped) so the cursor stays put at the ends instead of
     * jumping across the whole list.
     */
    fun browseBy(delta: Int): Int {
        val seed = if (browseIndex in channels.indices) browseIndex else currentIndex
        val newIndex = ChannelNavigation.clampIndex(seed, delta, channels.size)
        if (newIndex < 0) return browseIndex
        if (newIndex == browseIndex) return newIndex
        browseIndex = newIndex
        // The ListView's own selection is what we paint as the
        // "browse cursor" — a slightly different highlight from
        // the "currently playing" blue background.
        listView?.setSelection(newIndex)
        return newIndex
    }

    /**
     * Show the drawer and start the auto-hide timer.
     *
     * The drawer host starts at `View.INVISIBLE` (not `GONE`) so
     * the ListView inside has measured and laid out its children
     * on the first frame — this is what makes the "currently
     * playing" row's blue highlight paint correctly the very
     * first time the drawer is shown after a cold start.
     */
    fun show() {
        if (state == State.SHOWN) {
            // Already shown — just reset the timer.
            scheduleAutoHide()
            return
        }
        state = State.SHOWN
        val host = rootView.findViewById<View>(R.id.drawer_host) ?: return
        host.visibility = View.VISIBLE
        // The list is already laid out (because the host was
        // INVISIBLE, not GONE) — no extra work needed here.
        scheduleAutoHide()
    }

    /**
     * Hide the drawer. The host flips back to INVISIBLE (not GONE)
     * so the ListView keeps its measure state — that way the
     * next show() paints the highlighted row immediately.
     */
    fun hide() {
        if (state == State.HIDDEN) return
        state = State.HIDDEN
        rootHandler.removeCallbacks(hideRunnable)
        rootView.findViewById<View>(R.id.drawer_host)?.visibility = View.INVISIBLE
    }

    /**
     * Reset the auto-hide countdown. Call this on every key
     * press that should keep the drawer alive (any key really —
     * volume, play/pause, etc., all count as "user is still here").
     */
    fun bumpActivity() {
        if (state == State.SHOWN) scheduleAutoHide()
    }

    /**
     * Tear the drawer down for good. Cancels any pending
     * auto-hide callback so the Handler can't hold a reference to
     * this drawer (and, through it, the whole Activity view tree)
     * after the Activity is destroyed. Call this from
     * `Activity.onDestroy()`.
     *
     * Safe to call more than once.
     */
    fun release() {
        state = State.HIDDEN
        rootHandler.removeCallbacks(hideRunnable)
        listView?.setOnItemClickListener(null)
        listView = null
        adapter = null
        channels = emptyList()
        currentIndex = -1
        browseIndex = -1
    }

    val isShown: Boolean get() = state == State.SHOWN

    /**
     * The index of the channel currently being played (the
     * "currently playing" highlight, NOT the D-pad browse
     * cursor). Exposed so the activity can decide whether
     * pressing OK on the focused row should switch channels
     * (different from the playing one) or just close the drawer.
     */
    val playingIndex: Int get() = currentIndex

    /**
     * The D-pad browse cursor. This is where the user has navigated
     * within the drawer (separate from [playingIndex] which is the
     * row currently being played). Always returns a valid index
     * if the drawer has any channels.
     */
    fun focusedIndex(): Int {
        return if (browseIndex in channels.indices) browseIndex else currentIndex
    }

    /**
     * Return the channel at the currently D-pad-focused position,
     * or null if the drawer has nothing to choose from.
     */
    fun focusedChannel(): ChannelEntity? =
        channels.getOrNull(focusedIndex())

    private fun scheduleAutoHide() {
        rootHandler.removeCallbacks(hideRunnable)
        rootHandler.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    companion object {
        const val AUTO_HIDE_MS = 3_000L

        /**
         * Drawer width as a fraction of the screen. 0.30 covers the
         * left third and leaves the video readable on the right — the
         * figure the README and the macOS original both quote.
         */
        private const val DRAWER_WIDTH_FRACTION = 0.30f

        /**
         * Floor for very small / low-density panels (and for the odd
         * box that reports a narrow display), so channel names are
         * never clipped to a couple of characters.
         */
        private const val MIN_DRAWER_WIDTH_DP = 220f
    }
}
