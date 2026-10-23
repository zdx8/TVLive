package com.tvlive.tv.data.source

import androidx.room.ColumnInfo
import com.tvlive.tv.data.model.ChannelEntity

/**
 * The per-row state that survives a playlist refresh.
 *
 * Deliberately excludes `is_favorite` and `last_played_at`: those are
 * the user's data, not the provider's, and a refresh must never touch
 * them. They are preserved implicitly because the merge keeps the row's
 * primary key and only rewrites the provider-supplied columns.
 *
 * Public only because [ChannelDao] is a public interface and Room
 * projections cannot be narrowed to `internal`. Treat it as an
 * implementation detail of the merge — nothing outside
 * `data.source` should reference it.
 */
data class ExistingChannelState(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "group_title") val groupTitle: String?,
    @ColumnInfo(name = "tvg_id") val tvgId: String?,
    @ColumnInfo(name = "tvg_name") val tvgName: String?,
    @ColumnInfo(name = "logo") val logo: String?,
    @ColumnInfo(name = "options_json") val optionsJson: String?
)

/**
 * Computes how to reconcile an already-stored playlist against a freshly
 * parsed one, *in place*, rather than dropping the table and rebuilding
 * it.
 *
 * Why this exists: the previous `DELETE`-then-`INSERT` approach gave every
 * channel a brand-new auto-increment id on every refresh. Because
 * `is_favorite` and `last_played_at` live on the channel row, and
 * `recent_plays` has a cascading foreign key to it, each refresh
 * silently wiped the user's favorites and their recent-play history.
 * Refreshes happen on every cold start, so a favorite could not survive a
 * single app restart.
 *
 * Channels are matched by URL, which is what identifies a stream in an
 * M3U source. The merge is:
 *
 *   - same URL, provider metadata unchanged  → left completely alone
 *   - same URL, provider metadata changed    → `UPDATE` the metadata only
 *   - URL not present before                 → `INSERT`
 *   - stored URL no longer in the source     → `DELETE`
 *
 * Pure and dependency-free so it can be unit-tested on the plain JVM —
 * Room DAOs need an instrumented database, and this is the part with the
 * actual logic.
 */
internal object ChannelMerge {

    /** Default chunk size for `DELETE ... WHERE id IN (...)` statements. */
    const val DELETE_CHUNK_SIZE = 400

    internal data class Plan(
        /** New channels to insert (their `id` is ignored / auto-generated). */
        val toInsert: List<ChannelEntity>,
        /** Existing row id → the metadata that should replace what is stored. */
        val toUpdate: List<Pair<Long, ChannelEntity>>,
        /** Ids of stored rows whose URL vanished from the source. */
        val toDelete: List<Long>
    ) {
        val isEmpty: Boolean
            get() = toInsert.isEmpty() && toUpdate.isEmpty() && toDelete.isEmpty()
    }

    /**
     * @param existing rows currently stored for one playlist
     * @param incoming the freshly parsed channel list, in display order
     */
    fun plan(existing: List<ExistingChannelState>, incoming: List<ChannelEntity>): Plan {
        // Queue the stored rows per URL: an M3U file can legitimately
        // repeat a URL, and a previous buggy refresh may have left
        // duplicate rows behind, so a 1:1 map would silently drop rows
        // and leak orphan ids into `toDelete`.
        val availableByUrl = LinkedHashMap<String, ArrayDeque<ExistingChannelState>>()
        for (row in existing) {
            availableByUrl.getOrPut(row.url) { ArrayDeque() }.addLast(row)
        }

        val toInsert = ArrayList<ChannelEntity>()
        val toUpdate = ArrayList<Pair<Long, ChannelEntity>>()

        for (channel in incoming) {
            val queue = availableByUrl[channel.url]
            val stored = if (queue.isNullOrEmpty()) null else queue.removeFirst()
            when {
                stored == null -> toInsert.add(channel)
                metadataDiffers(stored, channel) -> toUpdate.add(stored.id to channel)
                // Otherwise: identical, so leave the row untouched. A
                // no-op refresh then performs zero writes.
            }
        }

        val toDelete = ArrayList<Long>()
        for (queue in availableByUrl.values) {
            for (stale in queue) toDelete.add(stale.id)
        }

        return Plan(toInsert = toInsert, toUpdate = toUpdate, toDelete = toDelete)
    }

    /**
     * True when any provider-supplied column changed. Compared field by
     * field (rather than by building a throwaway entity) purely so the
     * intent stays obvious and the comparison stays allocation-free for
     * large playlists.
     */
    private fun metadataDiffers(stored: ExistingChannelState, incoming: ChannelEntity): Boolean =
        stored.name != incoming.name ||
            stored.groupTitle != incoming.groupTitle ||
            stored.tvgId != incoming.tvgId ||
            stored.tvgName != incoming.tvgName ||
            stored.logo != incoming.logo ||
            stored.optionsJson != incoming.optionsJson
}
