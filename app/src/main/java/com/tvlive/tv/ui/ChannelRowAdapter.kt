package com.tvlive.tv.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.tvlive.tv.R
import com.tvlive.tv.data.model.ChannelEntity

/**
 * Adapter for the home-screen channel list.
 *
 * Renders each [ChannelEntity] as a single row with:
 *   - the channel name (or tvg-name fallback)
 *   - the group title as a subtle right-aligned hint
 *
 * Why a hand-rolled [BaseAdapter] instead of `simple_list_item_1`?
 * We want the D-pad focus state to look right on a TV. The default
 * row layout has no focus highlight, and the system `activated`
 * background is invisible on a dark theme.
 */
class ChannelRowAdapter(
    private val context: Context
) : BaseAdapter() {

    private val items = mutableListOf<ChannelEntity>()

    fun submit(newItems: List<ChannelEntity>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): ChannelEntity = items[position]

    override fun getItemId(position: Int): Long = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_channel_row, parent, false)
        val channel = items[position]
        val name = view.findViewById<TextView>(R.id.channel_name)
        val group = view.findViewById<TextView>(R.id.channel_group)
        val favorite = view.findViewById<TextView>(R.id.channel_favorite)
        name.text = displayName(channel)
        val groupText = channel.groupTitle?.takeIf { it.isNotBlank() }
        if (groupText != null) {
            group.text = groupText
            group.visibility = View.VISIBLE
        } else {
            group.visibility = View.GONE
        }
        favorite.visibility = if (channel.isFavorite) View.VISIBLE else View.GONE
        return view
    }

    private fun displayName(channel: ChannelEntity): String =
        channel.tvgName?.takeIf { it.isNotBlank() }
            ?: channel.name.takeIf { it.isNotBlank() }
            ?: channel.url.substringAfterLast('/').ifEmpty { channel.url }
}
