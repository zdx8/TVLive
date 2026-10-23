package com.tvlive.tv.player

/**
 * D-pad channel-index arithmetic, extracted from [ChannelDrawer].
 *
 * ChannelDrawer itself needs a real `View` tree (an inflated ListView,
 * a Handler on the main looper), so its navigation logic cannot run on
 * a plain JVM. Pulling the arithmetic out into this dependency-free
 * object means the behaviour that actually broke before — off-by-one
 * at the ends of the list, and getting permanently stuck on the last
 * channel while auto-advancing past dead streams — is covered by fast
 * unit tests.
 *
 * Both functions return a valid index, or `-1` when [size] is not
 * positive (an empty channel list has nowhere to move to).
 */
internal object ChannelNavigation {

    /**
     * Wrap-around move, used for the quick "channel surf" path while the
     * drawer is closed.
     *
     * Wrapping matches the set-top-box convention (pressing UP on the
     * first channel lands on the last), and it also means the
     * auto-advance-on-error path can never park on the final channel
     * forever.
     *
     * An out-of-range [from] means "no current channel" — any move then
     * lands on the first row rather than somewhere arbitrary.
     */
    fun wrapIndex(from: Int, delta: Int, size: Int): Int {
        if (size <= 0) return -1
        if (from !in 0 until size) return 0
        return ((from + delta) % size + size) % size
    }

    /**
     * Clamped move, used while the drawer is open: the browse cursor
     * stops at the ends instead of jumping across the whole list, which
     * would be disorienting when the user is deliberately browsing.
     */
    fun clampIndex(from: Int, delta: Int, size: Int): Int {
        if (size <= 0) return -1
        if (from !in 0 until size) return 0
        return (from + delta).coerceIn(0, size - 1)
    }
}
