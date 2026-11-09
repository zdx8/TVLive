package com.tvlive.tv.ui

import android.content.Context
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import com.tvlive.tv.R
import com.tvlive.tv.data.model.ChannelEntity

/**
 * Long-press handler for a channel row.
 *
 * On Android TV the "context menu" of a focused row is conventionally
 * invoked by long-pressing the OK button (or the equivalent key
 * combo). This helper:
 *   1. Anchors a [PopupMenu] to the row.
 *   2. Shows "Add to favorites" / "Remove from favorites" based on
 *      the channel's current state.
 *   3. Invokes [onToggleFavorite] when the user picks that item.
 *
 * We use [PopupMenu] rather than a system dialog because the menu
 * stays in-place visually (good for the 10-foot UI) and accepts
 * D-pad navigation natively.
 */
object ChannelContextMenu {

    fun show(
        anchor: View,
        channel: ChannelEntity,
        onToggleFavorite: (Long, Boolean) -> Unit,
        onPlay: (ChannelEntity) -> Unit
    ) {
        val ctx = anchor.context
        val popup = PopupMenu(ctx, anchor)
        popup.menu.add(
            0,
            MENU_PLAY,
            0,
            ctx.getString(R.string.menu_play_now)
        )
        val favLabel = if (channel.isFavorite) {
            ctx.getString(R.string.menu_remove_favorite)
        } else {
            ctx.getString(R.string.menu_add_favorite)
        }
        popup.menu.add(0, MENU_FAVORITE, 1, favLabel)
        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                MENU_PLAY -> { onPlay(channel); true }
                MENU_FAVORITE -> {
                    onToggleFavorite(channel.id, !channel.isFavorite)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private const val MENU_PLAY = 1
    private const val MENU_FAVORITE = 2
}
