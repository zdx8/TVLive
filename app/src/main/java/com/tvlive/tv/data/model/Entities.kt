package com.tvlive.tv.data.model

import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A M3U/M3U8 playlist source. The user can have many of these:
 *   - local files they imported once
 *   - remote subscriptions they want to refresh
 *
 * Mirrors iTelly-macOS's "Multi-playlist" idea. We keep both
 * [localPath] and [remoteUrl] optional; the non-null one is the
 * active source. Refresh only applies to remote sources.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Display name. For local files: the basename; for remote: the host. */
    @ColumnInfo(name = "name") val name: String,

    /** Absolute file path if the playlist was imported from disk. */
    @ColumnInfo(name = "local_path") val localPath: String? = null,

    /** HTTP(S) URL if the playlist is a remote subscription. */
    @ColumnInfo(name = "remote_url") val remoteUrl: String? = null,

    /** When the playlist was last parsed (epoch millis). */
    @ColumnInfo(name = "loaded_at") val loadedAtMs: Long = System.currentTimeMillis(),

    /** Whether to refresh remote_url automatically via WorkManager. */
    @ColumnInfo(name = "auto_refresh") val autoRefresh: Boolean = false
) {
    fun isLocal(): Boolean = !localPath.isNullOrBlank()
    fun isRemote(): Boolean = !remoteUrl.isNullOrBlank()
}

/**
 * One channel (entry) inside a playlist. Most M3U files have
 * thousands of these, so the table is indexed heavily.
 *
 * We denormalize [groupTitle] here (rather than a separate GroupEntity)
 * because the M3U format itself uses free-form strings — there's no
 * canonical group registry to enforce. The browse UI groups in memory.
 *
 * [tvgId] / [tvgName] / [logo] come from the #EXTINF attributes that
 * iTelly-macOS's M3UParser pulls out. They are best-effort hints that
 * EPG / channel logos can use.
 */
@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("playlist_id"),
        Index("group_title"),
        Index("name")
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "playlist_id") val playlistId: Long,

    @ColumnInfo(name = "name") val name: String,

    @ColumnInfo(name = "url") val url: String,

    @ColumnInfo(name = "group_title") val groupTitle: String? = null,

    @ColumnInfo(name = "tvg_id") val tvgId: String? = null,

    @ColumnInfo(name = "tvg_name") val tvgName: String? = null,

    @ColumnInfo(name = "logo") val logo: String? = null,

    /**
     * JSON array of Media3 / ExoPlayer command-line options derived
     * from #EXTVLCOPT / #KODIPROP. The M2 playback path doesn't
     * consume these yet (Media3 has no equivalent of libvlc's
     * `:network-caching=1500`); the column is reserved for the
     * PlayerController custom-data-source hookup in M5.
     */
    @ColumnInfo(name = "options_json") val optionsJson: String? = null,

    /** User-set favorite flag. */
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,

    /** Last time the user actually started playback (epoch millis). */
    @ColumnInfo(name = "last_played_at") val lastPlayedAtMs: Long? = null
) : Parcelable {

    /**
     * Best human-readable name. Prefers tvg-name (the provider's
     * canonical label) then our parsed name then the URL basename.
     * Not persisted — computed in memory.
     */
    val displayName: String
        get() = tvgName?.takeIf { it.isNotBlank() }
            ?: name.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').ifEmpty { url }

    /**
     * Natural-sort key, retained for tests and callers that want a
     * plain string. The actual [com.tvlive.tv.data.repository
     * .ChannelRepository] uses [NaturalSortKey.bucketKey] which adds
     * a "CCTV first / 湖系 next / other CJK after / other last"
     * bucketing layer that this single string can't express.
     */
    val naturalSortKey: String
        get() = NaturalSortKey.of(displayName)

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeLong(playlistId)
        dest.writeString(name)
        dest.writeString(url)
        dest.writeString(groupTitle)
        dest.writeString(tvgId)
        dest.writeString(tvgName)
        dest.writeString(logo)
        dest.writeString(optionsJson)
        dest.writeInt(if (isFavorite) 1 else 0)
        dest.writeLong(lastPlayedAtMs ?: -1L)
    }

    companion object CREATOR : Parcelable.Creator<ChannelEntity> {
        override fun createFromParcel(source: Parcel): ChannelEntity {
            return ChannelEntity(
                id = source.readLong(),
                playlistId = source.readLong(),
                name = source.readString() ?: "",
                url = source.readString() ?: "",
                groupTitle = source.readString(),
                tvgId = source.readString(),
                tvgName = source.readString(),
                logo = source.readString(),
                optionsJson = source.readString(),
                isFavorite = source.readInt() != 0,
                lastPlayedAtMs = source.readLong().takeIf { it >= 0 }
            )
        }
        override fun newArray(size: Int): Array<ChannelEntity?> = arrayOfNulls(size)
    }
}

/**
 * Recent-play log. Mirrors iTelly-macOS's "保留 40 条" cap; we enforce
 * the cap in the repository rather than here.
 *
 * We use a separate table rather than mutating [ChannelEntity] because
 * "recent" is a derived view (last N plays) and we want to be able
 * to add a single row per play without contention on the channel row.
 */
@Entity(
    tableName = "recent_plays",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["channel_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("channel_id"), Index("played_at")]
)
data class RecentPlayEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "channel_id") val channelId: Long,

    @ColumnInfo(name = "played_at") val playedAtMs: Long = System.currentTimeMillis()
)
