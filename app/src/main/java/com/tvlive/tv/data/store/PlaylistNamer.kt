package com.tvlive.tv.data.store

import android.content.Context
import android.util.Log
import com.tvlive.tv.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 订阅名自动解析 — 从订阅源内容里给订阅取个「能认出来」的名字。
 *
 * 启发式（从优到劣，取到即返回）：
 *  1. m3u 里的 `# name: xxx` 注释（部分自建工具会写）；
 *  2. 首个 `group-title="xxx"`（最常见的分类字段，通常代表订阅的编制者/类型）;
 *  3. URL 域名（去 www.）。
 *
 * 名字最长截 24 字符，避免 MENU 行被撑爆。
 */
object PlaylistNamer {

    private const val TAG = "PlaylistNamer"
    private const val MAX_NAME_LEN = 24
    private const val MAX_FETCH_BYTES = 4 * 1024 * 1024

    private val EXPLICIT_NAME = Regex("^#\\s*name:\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val GROUP_TITLE = Regex("group-title=\"([^\"]+)\"")

    /** 兜底名形态：预置的「订阅(源) N」都算，允许被订阅源真实名字替换。 */
    private val FALLBACK_NAME = Regex("^订阅(源)? ?\\d+$")

    /** 名字是否还是兜底形式（值得尝试自动取名）。 */
    fun isFallback(name: String): Boolean =
        name.isBlank() || FALLBACK_NAME.matches(name.trim())

    /**
     * 是否值得花一次网络请求取名：兜底名，或迁移来的「域名当名字」。
     * 已经是用户见过的真实名字（group-title 等）就不反复改。
     */
    fun isImprovable(name: String, url: String): Boolean =
        isFallback(name) || name == SubscriptionStore.hostOf(url)

    /** 从 m3u 文本里解析名字；解析不出返回 null。 */
    fun nameFrom(m3u: String, url: String): String? {
        EXPLICIT_NAME.find(m3u)?.let {
            val v = it.groupValues[1].trim()
            if (v.isNotEmpty()) return v.take(MAX_NAME_LEN)
        }
        GROUP_TITLE.find(m3u)?.let { g ->
            val v = g.groupValues[1].trim()
            if (v.isNotEmpty() && !v.equals("undefined", true)) return v.take(MAX_NAME_LEN)
        }
        return SubscriptionStore.hostOf(url)
            ?.removePrefix("www.")
            ?.take(MAX_NAME_LEN)
    }

    /**
     * 拉一次订阅文本并回写名字（异步入口，挂 IO）。
     * 失败静默保留原兜底名 — 取名是锦上添花，绝不能打扰用户。
     */
    suspend fun refreshName(context: Context, url: String) {
        val name = withContext(Dispatchers.IO) { fetchAndName(url) }
        if (name != null) {
            SubscriptionStore.rename(context, url, name)
            Log.i(TAG, "Subscription renamed: $url -> $name")
        }
    }

    private fun fetchAndName(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = (AppConfig.SUBSCRIPTION_CONNECT_TIMEOUT_S * 1000).toInt()
            conn.readTimeout = (AppConfig.SUBSCRIPTION_READ_TIMEOUT_S * 1000).toInt()
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "TVLive/0.1 (Android TV 13)")
            if (conn.responseCode !in 200..299) return@runCatching null
            val text = conn.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (total < MAX_FETCH_BYTES) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    total += n
                }
                buf.toString("UTF-8")
            }
            nameFrom(text, url)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
