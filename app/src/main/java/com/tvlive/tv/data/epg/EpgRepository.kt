package com.tvlive.tv.data.epg

import android.content.Context
import android.util.Log
import com.tvlive.tv.AppConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * EPG 节目单仓库：管理 EPG 来源地址、6 小时本地磁盘缓存与内存索引。
 *
 * 来源优先级：
 *   1. 当前订阅 M3U 头部的 `url-tvg` / `x-tvg-url`（由 ChannelRepository 解析后持久化）
 *   2. 用户通过扫码/手动输入的自定义 EPG 地址（`saveCustomEpgUrl`）
 *
 * 缓存用 EpgIndex 二进制序列化（只含时间窗内节目），命中时直接反序列化，
 * 不再解析全量 XML；联网时从响应流边下边解析，不落全量临时文件。
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

    /** 单次源加载结果：索引 + 统计，纯数据，不写全局状态。 */
    private data class LoadOutcome(val index: EpgIndex, val stats: EpgStats)

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
     * 加载指定订阅的 EPG：所有来源并发请求，首个成功即提交结果并取消其余，
     * 避免串行等待某个失效源超时（原来最坏要等 30 秒才试下一个）。
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

        val appCtx = context.applicationContext
        val winner = CompletableDeferred<Result<LoadOutcome>>()
        val remaining = AtomicInteger(urls.size)
        val jobs = urls.map { url ->
            async {
                val outcome = runCatching { loadFromUrl(appCtx, url, forceRefresh) }
                if (outcome.isSuccess) {
                    winner.complete(outcome) // 首个成功者获胜（CompletableDeferred.complete 是 CAS）
                } else if (remaining.decrementAndGet() == 0) {
                    // 全部失败：把最后一个错误交给等待方，避免永久挂起。
                    winner.complete(outcome)
                    Log.w(TAG, "EPG source failed ($url): ${outcome.exceptionOrNull()?.message}")
                } else {
                    Log.w(TAG, "EPG source failed ($url): ${outcome.exceptionOrNull()?.message}")
                }
            }
        }

        val winnerResult = try {
            winner.await()
        } finally {
            // 首个成功后取消其余仍在下载的源，省流量与电量。
            jobs.forEach { it.cancel() }
        }

        if (winnerResult.isFailure) {
            return@withContext Result.failure(
                winnerResult.exceptionOrNull() ?: IllegalStateException("EPG 加载失败")
            )
        }

        val outcome = winnerResult.getOrThrow()
        // 统一提交，避免多源并发写全局状态互相覆盖。
        currentIndex = outcome.index
        currentSourceUrl = outcome.stats.sourceUrl
        Result.success(outcome.stats)
    }

    private fun loadFromUrl(context: Context, url: String, forceRefresh: Boolean): LoadOutcome {
        val cacheFile = File(context.cacheDir, "epg_${sha1Short(url)}.idx")
        val cacheFresh = !forceRefresh &&
            cacheFile.exists() &&
            cacheFile.length() > 0 &&
            (System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS)

        // 命中缓存：直接反序列化精简索引（几毫秒），不再解析 XML。
        if (cacheFresh) {
            val cached = runCatching {
                cacheFile.inputStream().use { EpgIndexSerializer.read(it) }
            }.getOrNull()
            if (cached != null && !cached.isEmpty) {
                Log.i(TAG, "EPG loaded from cache: ${cached.channelCount} channels, ${cached.programCount} programs")
                return LoadOutcome(
                    cached,
                    EpgStats(url, cached.channelCount, cached.programCount, fromCache = true)
                )
            }
        }

        // 联网：从网络流直接边下边解析，只把精简索引写缓存（不再落全量 XML 临时文件）。
        val tempFile = File(context.cacheDir, "epg_${sha1Short(url)}.tmp")
        try {
            val parsed = downloadAndParse(url)
            if (parsed.isEmpty) throw IllegalStateException("EPG 解析结果为空")
            // 原子替换：先写 tmp，成功后再替换正式缓存。
            runCatching {
                tempFile.outputStream().use { EpgIndexSerializer.write(parsed, it) }
                if (cacheFile.exists()) cacheFile.delete()
                tempFile.renameTo(cacheFile)
            }
            Log.i(TAG, "EPG fetched: ${parsed.channelCount} channels, ${parsed.programCount} programs ($url)")
            return LoadOutcome(
                parsed,
                EpgStats(url, parsed.channelCount, parsed.programCount, fromCache = false)
            )
        } catch (t: Throwable) {
            tempFile.delete()
            // 联网失败时如有过期缓存，降级使用旧缓存
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val fallback = runCatching {
                    cacheFile.inputStream().use { EpgIndexSerializer.read(it) }
                }.getOrNull()
                if (fallback != null && !fallback.isEmpty) {
                    Log.w(TAG, "EPG network failed (${t.message}), using stale cache")
                    return LoadOutcome(
                        fallback,
                        EpgStats(url, fallback.channelCount, fallback.programCount, fromCache = true)
                    )
                }
            }
            throw t
        }
    }

    /** 打开连接并直接从响应流解析 XMLTV，避免把几十 MB 全量数据先写盘再读回。 */
    private fun downloadAndParse(url: String): EpgIndex {
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
            return conn.inputStream.use { input -> XmltvParser.parse(input) }
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
