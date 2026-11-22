package com.tvlive.tv.data.epg

import android.content.Context
import android.util.Log
import com.tvlive.tv.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * EPG 节目单仓库：管理 EPG 来源地址、6 小时本地磁盘缓存与内存索引。
 *
 * 来源优先级：
 *   1. 当前订阅 M3U 头部的 `url-tvg` / `x-tvg-url`（由 ChannelRepository 解析后持久化）
 *   2. 用户通过扫码/手动输入的自定义 EPG 地址（`saveCustomEpgUrl`）
 *
 * 沿用项目规范：使用 `java.net.HttpURLConnection`（不引入 OkHttp），
 * 自动处理 `.xml` / `.xml.gz` 及 `Content-Encoding: gzip`。
 */
object EpgRepository {

    private const val TAG = "TVLive.EPG"
    private const val PREFS_NAME = "hftv_epg"
    private const val KEY_CUSTOM_URL = "custom_epg_url"
    private const val KEY_PLAYLIST_PREFIX = "pl_epg_"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L // 6 小时

    @Volatile
    private var currentIndex: EpgIndex = EpgIndex.EMPTY

    @Volatile
    private var currentSourceUrl: String? = null

    fun index(): EpgIndex = currentIndex

    fun activeSourceUrl(): String? = currentSourceUrl

    /** 保存某条订阅 M3U 头部解析出的 EPG 地址（逗号分隔保留首条有效地址）。 */
    fun savePlaylistEpgUrls(context: Context, playlistKey: String, urls: List<String>) {
        val valid = urls.map { it.trim() }.filter { isHttpUrl(it) }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_PLAYLIST_PREFIX + sha1Short(playlistKey)
        if (valid.isEmpty()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, valid.joinToString(",")).apply()
        }
    }

    fun getPlaylistEpgUrls(context: Context, playlistKey: String?): List<String> {
        if (playlistKey.isNullOrBlank()) return emptyList()
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PLAYLIST_PREFIX + sha1Short(playlistKey), null) ?: return emptyList()
        return raw.split(',').map { it.trim() }.filter { isHttpUrl(it) }
    }

    /** 保存用户自定义兜底 EPG 地址（扫码页或设置传入）。 */
    fun saveCustomEpgUrl(context: Context, url: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val clean = url?.trim()?.takeIf { isHttpUrl(it) }
        if (clean == null) {
            prefs.edit().remove(KEY_CUSTOM_URL).apply()
        } else {
            prefs.edit().putString(KEY_CUSTOM_URL, clean).apply()
        }
    }

    fun getCustomEpgUrl(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_URL, null)?.trim()?.takeIf { isHttpUrl(it) }
    }

    /** 计算指定订阅生效的 EPG 地址列表（优先订阅自带，其次用户自定义）。 */
    fun resolveEpgUrls(context: Context, playlistKey: String?): List<String> {
        val urls = mutableListOf<String>()
        urls += getPlaylistEpgUrls(context, playlistKey)
        getCustomEpgUrl(context)?.let { custom ->
            if (custom !in urls) urls += custom
        }
        return urls
    }

    /**
     * 加载并解析指定订阅对应的 EPG；命中 6 小时本地缓存时直接解析磁盘文件，
     * 否则联网下载并更新缓存。
     */
    suspend fun loadForPlaylist(
        context: Context,
        playlistKey: String?,
        forceRefresh: Boolean = false
    ): Result<EpgStats> = withContext(Dispatchers.IO) {
        val urls = resolveEpgUrls(context, playlistKey)
        if (urls.isEmpty()) {
            currentIndex = EpgIndex.EMPTY
            currentSourceUrl = null
            return@withContext Result.failure(IllegalStateException("当前订阅未配置 EPG 节目单地址"))
        }

        var lastError: Throwable? = null
        for (url in urls) {
            val result = runCatching { loadFromUrl(context.applicationContext, url, forceRefresh) }
            if (result.isSuccess) {
                return@withContext result
            }
            lastError = result.exceptionOrNull()
            Log.w(TAG, "EPG source failed ($url): ${lastError?.message}")
        }
        Result.failure(lastError ?: IllegalStateException("EPG 加载失败"))
    }

    private fun loadFromUrl(context: Context, url: String, forceRefresh: Boolean): EpgStats {
        val cacheFile = File(context.cacheDir, "epg_${sha1Short(url)}.cache")
        val cacheFresh = !forceRefresh &&
            cacheFile.exists() &&
            cacheFile.length() > 0 &&
            (System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS)

        if (cacheFresh) {
            val parsed = cacheFile.inputStream().use { XmltvParser.parse(it) }
            currentIndex = parsed
            currentSourceUrl = url
            Log.i(TAG, "EPG loaded from cache: ${parsed.channelCount} channels, ${parsed.programCount} programs")
            return EpgStats(url, parsed.channelCount, parsed.programCount, fromCache = true)
        }

        // 联网下载到临时文件再原子替换缓存
        val tempFile = File(context.cacheDir, "epg_${sha1Short(url)}.tmp")
        try {
            downloadToFile(url, tempFile)
            val parsed = tempFile.inputStream().use { XmltvParser.parse(it) }
            if (cacheFile.exists()) cacheFile.delete()
            tempFile.renameTo(cacheFile)
            currentIndex = parsed
            currentSourceUrl = url
            Log.i(TAG, "EPG fetched: ${parsed.channelCount} channels, ${parsed.programCount} programs ($url)")
            return EpgStats(url, parsed.channelCount, parsed.programCount, fromCache = false)
        } catch (t: Throwable) {
            tempFile.delete()
            // 联网失败时如有过期缓存，降级使用旧缓存
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val fallback = runCatching { cacheFile.inputStream().use { XmltvParser.parse(it) } }.getOrNull()
                if (fallback != null && !fallback.isEmpty) {
                    currentIndex = fallback
                    currentSourceUrl = url
                    Log.w(TAG, "EPG network failed (${t.message}), using stale cache")
                    return EpgStats(url, fallback.channelCount, fallback.programCount, fromCache = true)
                }
            }
            throw t
        }
    }

    private fun downloadToFile(url: String, target: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = (AppConfig.SUBSCRIPTION_CONNECT_TIMEOUT_S * 1000).toInt()
            readTimeout = (AppConfig.SUBSCRIPTION_READ_TIMEOUT_S * 1000).toInt()
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "TVLive/2.0 (Android TV)")
            setRequestProperty("Accept", "application/xml, text/xml, application/gzip, */*")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${conn.responseMessage ?: ""}")
            }
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun isHttpUrl(raw: String): Boolean {
        val lower = raw.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun sha1Short(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
