package com.tvlive.tv.player

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.tvlive.tv.R
import com.tvlive.tv.data.model.ChannelEntity

/**
 * Adapter for the channel-drawer list.
 *
 * Each row is a small TextView (channel name + group on the right).
 *
 * The "currently playing" row is marked via [setCurrentIndex]; the
 * row's `isSelected` state is true, which the row's background state
 * list ([drawer_row_background.xml]) reads to paint a persistent
 * blue highlight. This works even when the ListView itself has no
 * D-pad focus — important because on TV the user might summon the
 * drawer with LEFT and immediately look at it without focusing it.
 */
class ChannelDrawerAdapter(
    private val context: Context
) : BaseAdapter() {

    private val items = mutableListOf<ChannelEntity>()
    private var currentIndex: Int = -1

    fun submit(newItems: List<ChannelEntity>, currentIndex: Int = this.currentIndex) {
        items.clear()
        items.addAll(newItems)
        this.currentIndex = currentIndex
        notifyDataSetChanged()
    }

    /**
     * Update the highlighted "currently playing" row without
     * reloading the whole list. Called every time the user
     * switches channels via UP / DOWN / RIGHT so the highlight
     * follows the playback.
     *
     * Implementation note: notifyDataSetChanged() is a heavy hammer
     * that re-binds every visible row. For 200-300 channels on a
     * 1080p TV this is sub-frame and the simpler code wins; for
     * 5000+ channels (some IPTV providers do this) we'd want to
     * call `notifyItemChanged(previous)` and `notifyItemChanged(new)`.
     * We keep the simple version for now — it matches the ListView
     * "small list" mental model and the test surface.
     */
    fun setCurrentIndex(index: Int) {
        if (index == currentIndex) return
        val previous = currentIndex
        currentIndex = index
        // Only invalidate when the change is actually visible.
        if (previous in items.indices || index in items.indices) {
            notifyDataSetChanged()
        }
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): ChannelEntity = items[position]
    override fun getItemId(position: Int): Long = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_drawer_row, parent, false)
        val name = view.findViewById<TextView>(R.id.drawer_row_name)
        val group = view.findViewById<TextView>(R.id.drawer_row_group)
        val ch = items[position]
        name.text = ch.displayName
        val groupText = ch.groupTitle?.takeIf { it.isNotBlank() }
        if (groupText != null) {
            group.text = groupText
            group.visibility = View.VISIBLE
        } else {
            group.visibility = View.GONE
        }
        // Drive the "currently playing" highlight via isSelected.
        // The state list in drawer_row_background.xml paints a blue
        // tint for state_selected=true regardless of D-pad focus.
        view.isSelected = isRowSelected(position, currentIndex)
        return view
    }

    companion object {
        /**
         * Pure function: should the row at [position] be highlighted as
         * the "currently playing" channel? Extracted so it can be
         * unit-tested without instantiating the adapter.
         */
        @JvmStatic
        fun isRowSelected(position: Int, currentIndex: Int): Boolean =
            currentIndex in 0..Int.MAX_VALUE && position == currentIndex
    }
}
