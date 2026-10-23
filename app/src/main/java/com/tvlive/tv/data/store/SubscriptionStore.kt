package com.tvlive.tv.data.store

import android.content.Context
import android.content.SharedPreferences
import com.tvlive.tv.AppConfig
import com.tvlive.tv.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 订阅仓库 — MENU 里可管理的订阅列表（URL + 显示名 + 添加时间）。
 *
 * 持久化在 SharedPreferences（hftv_home / subscriptions_json），整表读写。
 * 首次启动 seed：
 * - 私有包（`-PprivateSubs=true` 构建）预置 assets/private-subscriptions.json
 *   里的订阅（该文件 gitignore、仓库里不存在，公开构建天然纯净）；
 * - 旧版本升级迁移：旧 PREF_ACTIVE_SUBSCRIPTION 里的 URL 会作为第一条
 *   订阅保留，名字由 [PlaylistNamer] 修正。
 *
 * 线程模型：写操作都从主线程触发（MENU 点击 / 扫码回调 / 取名协程回主线程），
 * 整表序列化天然串行，不加锁；读操作允许任意线程。
 */
object SubscriptionStore {

    data class Entry(val url: String, val name: String, val addedAt: Long)

    private const val KEY_LIST = "subscriptions_json"
    private const val KEY_ACTIVE = "active_subscription_url"
    private const val BUNDLED_ASSET = "private-subscriptions.json"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)

    /** 首次访问时做 seed + 旧数据迁移；此后 no-op。 */
    private fun ensureSeeded(p: SharedPreferences, context: Context) {
        if (p.contains(KEY_LIST)) return
        val now = System.currentTimeMillis()
        val entries = mutableListOf<Entry>()

        // 私有包：预置作者自己的订阅（文件只在作者本地，仓库里没有）。
        if (BuildConfig.INCLUDE_PRIVATE_SUBS) {
            entries += readBundled(context)
        }

        val legacyActive = p.getString(AppConfig.PREF_ACTIVE_SUBSCRIPTION, null)?.trim().orEmpty()
        if (legacyActive.isNotEmpty() && entries.none { it.url == legacyActive }) {
            // 升级用户已有但未预置的源：保成第一条，名字由 PlaylistNamer 修正。
            entries.add(0, Entry(legacyActive, hostOf(legacyActive) ?: "订阅 1", now - 1))
        }
        val active = if (legacyActive.isNotEmpty() && entries.any { it.url == legacyActive }) {
            legacyActive
        } else {
            entries.firstOrNull()?.url.orEmpty()
        }
        p.edit()
            .putString(KEY_LIST, encode(entries))
            .putString(KEY_ACTIVE, active)
            .apply()
    }

    /** 读私有包注入文件（assets/private-subscriptions.json，可选，缺失返回空）。 */
    private fun readBundled(context: Context): List<Entry> = runCatching {
        val now = System.currentTimeMillis()
        val arr = JSONArray(
            context.assets.open(BUNDLED_ASSET).bufferedReader().use { it.readText() }
        )
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(url, o.optString("name").ifBlank { hostOf(url) ?: "订阅" }, now + i)
        }
    }.getOrDefault(emptyList())

    // ---------- 读 ----------

    fun list(context: Context): List<Entry> {
        val p = prefs(context)
        ensureSeeded(p, context)
        return runCatching { decode(p.getString(KEY_LIST, null)) }.getOrDefault(emptyList())
    }

    fun find(context: Context, url: String): Entry? =
        list(context).firstOrNull { it.url == url }

    /** 当前生效的订阅 URL；空仓库返回 null（调用方再退到 AppConfig.DEFAULT）。 */
    fun activeUrl(context: Context): String? {
        val p = prefs(context)
        ensureSeeded(p, context)
        return p.getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }
            ?: list(context).firstOrNull()?.url
    }

    // ---------- 写 ----------

    /** 添加订阅；同 URL 已存在时仅更新名字（若给出），不改变原有顺序。 */
    fun add(context: Context, url: String, name: String? = null): Entry {
        val p = prefs(context)
        ensureSeeded(p, context)
        val entries = list(context).toMutableList()
        val entry: Entry
        val existing = entries.indexOfFirst { it.url == url }
        if (existing >= 0) {
            val old = entries[existing]
            if (!name.isNullOrBlank() && name != old.name) {
                entries[existing] = old.copy(name = name)
            }
            entry = entries[existing]
        } else {
            entry = Entry(
                url = url,
                name = name?.takeIf { it.isNotBlank() } ?: hostOf(url) ?: fallbackName(entries.size + 1),
                addedAt = System.currentTimeMillis()
            )
            entries.add(entry)
        }
        save(p, entries)
        return entry
    }

    fun remove(context: Context, url: String) {
        val p = prefs(context)
        ensureSeeded(p, context)
        val entries = list(context).filterNot { it.url == url }
        save(p, entries)
        if (p.getString(KEY_ACTIVE, null) == url) {
            p.edit().putString(KEY_ACTIVE, entries.firstOrNull()?.url.orEmpty()).apply()
        }
    }

    fun rename(context: Context, url: String, name: String) {
        if (name.isBlank()) return
        val p = prefs(context)
        ensureSeeded(p, context)
        val entries = list(context).toMutableList()
        val i = entries.indexOfFirst { it.url == url }
        if (i >= 0 && entries[i].name != name) {
            entries[i] = entries[i].copy(name = name)
            save(p, entries)
        }
    }

    fun setActive(context: Context, url: String) {
        prefs(context).edit().putString(KEY_ACTIVE, url).apply()
    }

    // ---------- 序列化 ----------

    private fun save(p: SharedPreferences, entries: List<Entry>) {
        p.edit().putString(KEY_LIST, encode(entries)).apply()
    }

    private fun encode(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach {
            arr.put(
                JSONObject()
                    .put("url", it.url)
                    .put("name", it.name)
                    .put("addedAt", it.addedAt)
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(url, o.optString("name").ifBlank { hostOf(url) ?: "订阅" }, o.optLong("addedAt", 0L))
            }
        }.getOrDefault(emptyList())
    }

    fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    fun fallbackName(n: Int): String = "订阅 $n"
}
