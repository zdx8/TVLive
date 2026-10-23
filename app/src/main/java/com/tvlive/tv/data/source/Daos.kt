package com.tvlive.tv.data.source

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tvlive.tv.data.model.ChannelEntity
import com.tvlive.tv.data.model.PlaylistEntity
import com.tvlive.tv.data.model.RecentPlayEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY loaded_at DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    /**
     * Look up an existing remote subscription by its URL.
     *
     * Used to make "subscribe to this URL" idempotent: re-importing
     * the same subscription refreshes the channels in place instead of
     * inserting a second playlist row (and a second copy of every
     * channel) on each launch.
     */
    @Query("SELECT * FROM playlists WHERE remote_url = :url LIMIT 1")
    suspend fun findByRemoteUrl(url: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity): Long

    /** Refresh the "last parsed" stamp without touching the name. */
    @Query("UPDATE playlists SET loaded_at = :loadedAtMs WHERE id = :id")
    suspend fun updateLoadedAt(id: Long, loadedAtMs: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels WHERE playlist_id = :playlistId")
    fun observeByPlaylist(playlistId: Long): Flow<List<ChannelEntity>>

    /** One-shot (non-Flow) read of a playlist's channels, in row order. */
    @Query("SELECT * FROM channels WHERE playlist_id = :playlistId")
    suspend fun listByPlaylist(playlistId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    @Query("SELECT * FROM channels WHERE name LIKE :pattern COLLATE NOCASE OR tvg_name LIKE :pattern COLLATE NOCASE LIMIT 200")
    suspend fun search(pattern: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE is_favorite = 1")
    fun observeFavorites(): Flow<List<ChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>): List<Long>

    @Query("UPDATE channels SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE channels SET last_played_at = :timestamp WHERE id = :id")
    suspend fun markLastPlayed(id: Long, timestamp: Long)

    /**
     * Atomic "replace this playlist's channels" — reconciles the stored
     * rows against [newChannels] **in place**. Mirrors the
     * iTelly-macOS "refresh subscription" behaviour, where we re-read the
     * whole playlist and replace it.
     *
     * This used to be `deleteByPlaylist()` + `insertAll()`. That was
     * destructive: every refresh re-created every row with a new
     * auto-increment id, which silently cleared `is_favorite` /
     * `last_played_at` and — via the cascading foreign key — the entire
     * `recent_plays` log. Since the app refreshes its subscription on
     * every cold start, a favorited channel could not survive one
     * restart.
     *
     * The reconciliation is computed by [ChannelMerge] and then applied
     * here: unchanged channels are not written at all, so refreshing an
     * unmodified playlist costs a single `SELECT` and no writes.
     */
    @Transaction
    suspend fun replaceForPlaylist(playlistId: Long, newChannels: List<ChannelEntity>) {
        val plan = ChannelMerge.plan(existingStateForPlaylist(playlistId), newChannels)

        for (chunk in plan.toDelete.chunked(ChannelMerge.DELETE_CHUNK_SIZE)) {
            deleteByIds(chunk)
        }
        for ((id, channel) in plan.toUpdate) {
            updateMetadata(
                id = id,
                name = channel.name,
                groupTitle = channel.groupTitle,
                tvgId = channel.tvgId,
                tvgName = channel.tvgName,
                logo = channel.logo,
                optionsJson = channel.optionsJson
            )
        }
        if (plan.toInsert.isNotEmpty()) {
            insertAll(plan.toInsert)
        }
    }

    /**
     * Provider-supplied columns for every stored row of a playlist.
     * Used to diff against a freshly parsed list. `is_favorite` and
     * `last_played_at` are intentionally not selected — they are never
     * rewritten by a refresh.
     */
    @Query(
        """
        SELECT id, url, name, group_title, tvg_id, tvg_name, logo, options_json
        FROM channels
        WHERE playlist_id = :playlistId
        """
    )
    suspend fun existingStateForPlaylist(playlistId: Long): List<ExistingChannelState>

    /**
     * Update only the provider-supplied metadata for one stored row,
     * leaving `is_favorite`, `last_played_at` and the primary key alone
     * so user data and foreign keys survive the refresh.
     */
    @Query(
        """
        UPDATE channels SET
            name = :name,
            group_title = :groupTitle,
            tvg_id = :tvgId,
            tvg_name = :tvgName,
            logo = :logo,
            options_json = :optionsJson
        WHERE id = :id
        """
    )
    suspend fun updateMetadata(
        id: Long,
        name: String,
        groupTitle: String?,
        tvgId: String?,
        tvgName: String?,
        logo: String?,
        optionsJson: String?
    )

    /** Delete a batch of rows by id. Callers must chunk large lists. */
    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /**
     * Drop every stored row for a playlist.
     *
     * NOTE: not used by [replaceForPlaylist] any more — that path
     * reconciles in place so it does not destroy favorites or the
     * recent-play log. Kept for an explicit "clear cached channels"
     * action, and because Room validates it at compile time so it
     * cannot silently rot.
     */
    @Query("DELETE FROM channels WHERE playlist_id = :playlistId")
    suspend fun deleteByPlaylist(playlistId: Long)
}

@Dao
interface RecentPlayDao {

    @Query("SELECT * FROM recent_plays ORDER BY played_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 40): Flow<List<RecentPlayEntity>>

    @Insert
    suspend fun insert(recent: RecentPlayEntity): Long

    /**
     * Cap the recent-play log at [keep] rows. Called after every
     * insert. iTelly-macOS caps at 40 — we expose the limit as a
     * parameter so the user setting can change it later.
     */
    @Query("""
        DELETE FROM recent_plays
        WHERE id NOT IN (
            SELECT id FROM recent_plays ORDER BY played_at DESC LIMIT :keep
        )
    """)
    suspend fun trim(keep: Int)

    @Transaction
    suspend fun recordAndTrim(channelId: Long, keep: Int = 40) {
        insert(RecentPlayEntity(channelId = channelId))
        trim(keep)
    }
}
