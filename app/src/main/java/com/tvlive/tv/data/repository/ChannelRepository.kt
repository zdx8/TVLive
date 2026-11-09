package com.tvlive.tv.data.repository

import android.content.Context
import com.tvlive.tv.AppConfig
import com.tvlive.tv.data.m3u.M3UParser
import com.tvlive.tv.data.model.ChannelEntity
import com.tvlive.tv.data.model.NaturalSortKey
import com.tvlive.tv.data.model.PlaylistEntity
import com.tvlive.tv.data.model.RecentPlayEntity
import com.tvlive.tv.data.source.ChannelDao
import com.tvlive.tv.data.source.TVLiveDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * The application-level entry point for playlist / channel / recent
 * data. Hides the Room DAOs and the M3U parser from callers, and
 * owns the OkHttpClient for remote subscription loads.
 *
 * The macOS project calls this layer "library"; the name "Repository"
 * follows the Android architecture conventions.
 */
class ChannelRepository(
    private val context: Context,
    private val parser: M3UParser = M3UParser()
) {

    private val db = TVLiveDatabase.get(context)
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()
    private val recentDao = db.recentPlayDao()

    // ---- Playlists ----

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAll()

    /**
     * Import a local M3U file. Returns the new playlist id; the
     * caller can then navigate to it.
     */
    suspend fun importLocalFile(path: String): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            require(file.isFile) { "File not found: $path" }
            val bytes = file.readBytes()
            val text = parser.decode(bytes)
                ?: throw IllegalArgumentException("无法识别文件编码（不是 UTF-8 / GB18030 / Latin-1 文本）")
            val parsed = parser.parse(text)
            require(parsed.channels.isNotEmpty()) { "文件不含任何 #EXTINF 条目" }

            val playlistId = playlistDao.insert(
                PlaylistEntity(
                    name = file.nameWithoutExtension,
                    localPath = file.absolutePath,
                    remoteUrl = null,
                    loadedAtMs = System.currentTimeMillis()
                )
            )
            channelDao.replaceForPlaylist(
                playlistId = playlistId,
                newChannels = parsed.channels.map { ch ->
                    ChannelEntity(
                        playlistId = playlistId,
                        name = ch.name,
                        url = ch.url,
                        groupTitle = ch.groupTitle,
                        tvgId = ch.tvgId,
                        tvgName = ch.tvgName,
                        logo = ch.logo
                    )
                }
            )
            ImportSummary(playlistId, parsed.channelCount)
        }
    }

    /**
     * Load (or refresh) a remote subscription. Returns a Result
     * wrapping the playlist id + channel count.
     *
     * Idempotent by URL: if a playlist with this [url] already exists
     * we reuse its row and just replace its channels. Previously every
     * call inserted a fresh [PlaylistEntity], so a subscription that
     * was refreshed on each launch grew the database without bound —
     * N launches meant N playlists and N copies of every channel, all
     * of which appeared in the playlist picker.
     *
     * Uses [java.net.HttpURLConnection] instead of OkHttp because
     * some older Android TV boxes (Android 9, many Chinese OEM
     * builds) ship a buggy okio native lib that crashes inside
     * OkHttp's connection pool. HttpURLConnection is bundled with
     * the JVM and works everywhere.
     */
    suspend fun loadRemote(url: String, suggestedName: String? = null): Result<ImportSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = fetchUrl(url)
                val text = parser.decode(bytes)
                    ?: throw IllegalArgumentException("无法识别订阅源编码")
                val parsed = parser.parse(text)
                require(parsed.channels.isNotEmpty()) { "订阅源不含任何 #EXTINF 条目" }

                val existing = playlistDao.findByRemoteUrl(url)
                val playlistId = if (existing != null) {
                    // Refresh in place — keep the id (and therefore the
                    // user's navigation position) stable.
                    playlistDao.updateLoadedAt(existing.id, System.currentTimeMillis())
                    existing.id
                } else {
                    playlistDao.insert(
                        PlaylistEntity(
                            name = suggestedName ?: URI.create(url).host ?: url,
                            localPath = null,
                            remoteUrl = url,
                            loadedAtMs = System.currentTimeMillis(),
                            autoRefresh = false
                        )
                    )
                }
                channelDao.replaceForPlaylist(
                    playlistId = playlistId,
                    newChannels = parsed.channels.map { ch ->
                        ChannelEntity(
                            playlistId = playlistId,
                            name = ch.name,
                            url = ch.url,
                            groupTitle = ch.groupTitle,
                            tvgId = ch.tvgId,
                            tvgName = ch.tvgName,
                            logo = ch.logo
                        )
                    }
                )
                ImportSummary(playlistId, parsed.channelCount)
            }
        }

    /**
     * Fetch a URL with HttpURLConnection. Returns the response bytes
     * on success, throws on any failure. Caller is expected to wrap
     * this in runCatching.
     */
    private fun fetchUrl(url: String): ByteArray {
        val parsed = URL(url)
        val conn = parsed.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = (AppConfig.SUBSCRIPTION_CONNECT_TIMEOUT_S * 1000).toInt()
            conn.readTimeout = (AppConfig.SUBSCRIPTION_READ_TIMEOUT_S * 1000).toInt()
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "TVLive/0.1 (Android TV 13)")
            conn.setRequestProperty("Accept", "*/*")
            // Some HTTP servers on the LAN won't talk to the default
            // okhttp user agent; explicit UA is harmless.
            if (conn is HttpsURLConnection) {
                // Trust system defaults — these are local-network
                // endpoints, not the public internet.
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${conn.responseMessage ?: ""}")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        playlistDao.deleteById(id) // channels cascade via ForeignKey
    }

    // ---- Channels ----

    /**
     * Observable channel list for a playlist, sorted using a natural
     * (numeric-aware) comparison. The DAO returns rows in insertion
     * order; we re-sort here because SQLite's `ORDER BY name` is
     * lexicographic and gives "CCTV1, CCTV10, CCTV11, CCTV2" — wrong
     * for human channel lists.
     *
     * Performance: a few hundred rows sorted by Kotlin's `compareBy`
     * takes well under a millisecond; doing it in the Repository
     * layer (not in SQL) keeps the schema stable and lets the
     * natural-sort key be computed from the same `displayName` the
     * UI shows, so what-you-see-is-what-you-sort.
     */
    fun observeChannels(playlistId: Long): Flow<List<ChannelEntity>> =
        channelDao.observeByPlaylist(playlistId).map { rows -> sortChannels(rows) }

    /**
     * One-shot (non-Flow) read of a playlist's channels, already in the
     * same natural-sort order [observeChannels] returns. Used by
     * [PlaybackActivity] to pick up the channel list when the user
     * navigates into playback — we deliberately *don't* ferry the list
     * through the launching Intent because Room/Parcelable-via-Intent
     * is brittle under R8 (the platform reads the Parcelable creator
     * via `getEnclosingClass()` reflection, which R8 strips from the
     * `ChannelEntity$CREATOR` companion in the release build → NPE on
     * the read side).
     */
    suspend fun listChannels(playlistId: Long): List<ChannelEntity> =
        withContext(Dispatchers.IO) {
            sortChannels(channelDao.listByPlaylist(playlistId))
        }

    private fun sortChannels(rows: List<ChannelEntity>): List<ChannelEntity> =
        rows.sortedWith(compareBy(
            { NaturalSortKey.bucketKey(it.displayName).first },
            { NaturalSortKey.bucketKey(it.displayName).second }
        ))

    fun observeFavorites(): Flow<List<ChannelEntity>> =
        channelDao.observeFavorites().map { rows ->
            rows.sortedWith(compareBy(
                { NaturalSortKey.bucketKey(it.displayName).first },
                { NaturalSortKey.bucketKey(it.displayName).second }
            ))
        }

    suspend fun searchChannels(query: String): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val pattern = "%${query.trim()}%"
        channelDao.search(pattern).sortedWith(compareBy(
            { NaturalSortKey.bucketKey(it.displayName).first },
            { NaturalSortKey.bucketKey(it.displayName).second }
        ))
    }

    suspend fun channelById(id: Long): ChannelEntity? = withContext(Dispatchers.IO) {
        channelDao.byId(id)
    }

    suspend fun setFavorite(id: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        channelDao.setFavorite(id, favorite)
    }

    // ---- Recent plays ----

    fun observeRecent(limit: Int = 40): Flow<List<com.tvlive.tv.data.model.RecentPlayEntity>> =
        recentDao.observeRecent(limit)

    suspend fun recordPlay(channelId: Long) = withContext(Dispatchers.IO) {
        recentDao.recordAndTrim(channelId, keep = 40)
    }

    data class ImportSummary(val playlistId: Long, val channelCount: Int)
}

/** Lightweight URI alias to avoid pulling java.net.URI all over. */
private typealias URI = java.net.URI
