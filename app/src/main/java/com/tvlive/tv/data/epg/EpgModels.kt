package com.tvlive.tv.data.epg

import com.tvlive.tv.data.model.ChannelEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 单条 EPG 节目单条目。
 */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val description: String? = null
) {
    fun isLiveAt(nowMs: Long): Boolean = nowMs in startMs until stopMs

    /** 当前播出进度 [0f, 1f]。 */
    fun progressAt(nowMs: Long): Float {
        val duration = stopMs - startMs
        if (duration <= 0L) return 0f
        return ((nowMs - startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    /** 格式化时间段，例如 "19:00 - 19:30"。 */
    fun timeRangeText(): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${fmt.format(Date(startMs))} - ${fmt.format(Date(stopMs))}"
    }

    /** 格式化开始时间，例如 "19:00"。 */
    fun startTimeText(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(startMs))
}

/** 当前播出 + 下一档节目。 */
data class EpgNowNext(
    val current: EpgProgram?,
    val next: EpgProgram?
)

/** 一次 EPG 加载结果统计。 */
data class EpgStats(
    val sourceUrl: String,
    val channelCount: Int,
    val programCount: Int,
    val fromCache: Boolean
)

/**
 * 内存中的 EPG 索引：按 XMLTV channel id 与归一化频道名快速检索节目单。
 */
class EpgIndex(
    private val programsByChannelId: Map<String, List<EpgProgram>>,
    private val displayNameToChannelId: Map<String, String>
) {
    val isEmpty: Boolean get() = programsByChannelId.isEmpty()
    val channelCount: Int get() = programsByChannelId.size
    val programCount: Int get() = programsByChannelId.values.sumOf { it.size }

    /** 供 EpgIndexSerializer 写入缓存使用（只读，不做防御性拷贝以免大索引重复占内存）。 */
    internal fun programsForSerialization(): Map<String, List<EpgProgram>> = programsByChannelId
    internal fun aliasMapForSerialization(): Map<String, String> = displayNameToChannelId

    /** 按 ChannelEntity 的 tvgId / tvgName / name 多级匹配节目列表。 */
    fun programsFor(channel: ChannelEntity): List<EpgProgram> =
        programsFor(channel.tvgId, channel.tvgName, channel.name)

    /** 多级匹配：1) 精确 tvgId → 2) 精确名称/display-name → 3) 归一化名称（如 CCTV-1 综合 ↔ CCTV1）。 */
    fun programsFor(tvgId: String?, tvgName: String?, name: String?): List<EpgProgram> {
        if (programsByChannelId.isEmpty()) return emptyList()

        // 1. Exact id match (case-insensitive)
        val idKey = tvgId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        if (idKey != null) {
            programsByChannelId[idKey]?.let { return it }
            displayNameToChannelId[idKey]?.let { programsByChannelId[it] }?.let { return it }
        }

        // 2. Exact displayName / tvgName / name match (case-insensitive)
        val rawNames = listOfNotNull(tvgName, name, tvgId)
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        for (raw in rawNames) {
            programsByChannelId[raw]?.let { return it }
            displayNameToChannelId[raw]?.let { programsByChannelId[it] }?.let { return it }
        }

        // 3. Normalized fuzzy match (strips HD/高清/标清/空格/连字符等)
        for (raw in rawNames) {
            val norm = normalizeChannelName(raw)
            if (norm.isNotEmpty()) {
                programsByChannelId[norm]?.let { return it }
                displayNameToChannelId[norm]?.let { programsByChannelId[it] }?.let { return it }
            }
        }
        return emptyList()
    }

    /** 查询指定频道在 [nowMs] 时刻的「正在播出 + 下一档」。 */
    fun nowNext(channel: ChannelEntity, nowMs: Long = System.currentTimeMillis()): EpgNowNext =
        nowNext(programsFor(channel), nowMs)

    fun nowNext(
        tvgId: String?,
        tvgName: String?,
        name: String?,
        nowMs: Long = System.currentTimeMillis()
    ): EpgNowNext = nowNext(programsFor(tvgId, tvgName, name), nowMs)

    private fun nowNext(list: List<EpgProgram>, nowMs: Long): EpgNowNext {
        if (list.isEmpty()) return EpgNowNext(null, null)
        var current: EpgProgram? = null
        var next: EpgProgram? = null
        for (i in list.indices) {
            val p = list[i]
            if (p.isLiveAt(nowMs)) {
                current = p
                next = list.getOrNull(i + 1)
                break
            }
            if (p.startMs > nowMs) {
                next = p
                break
            }
        }
        return EpgNowNext(current, next)
    }

    /** 返回指定频道当天的节目单列表（按开始时间升序）。 */
    fun todaySchedule(channel: ChannelEntity, nowMs: Long = System.currentTimeMillis()): List<EpgProgram> {
        val list = programsFor(channel)
        if (list.isEmpty()) return emptyList()
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val dayEnd = cal.timeInMillis
        return list.filter { it.stopMs > dayStart && it.startMs < dayEnd }
    }

    companion object {
        val EMPTY = EpgIndex(emptyMap(), emptyMap())

        /**
         * 归一化频道名用于模糊匹配：
         * - 小写
         * - 去掉括号及括号内说明（如 "(备)"、"[高清]"）
         * - 去掉常见画质/后缀词（4K / UHD / FHD / HD / SD / 高清 / 超清 / 标清 / 综合 / 频道 / 卫视以外的符号）
         * - 去掉空格、连字符、下划线、加号
         */
        fun normalizeChannelName(raw: String): String {
            var s = raw.trim().lowercase(Locale.ROOT)
            if (s.isEmpty()) return ""
            // 去掉括号注释
            s = s.replace(Regex("""[（(\[【][^）)\]】]*[）)\]】]"""), "")
            // 去掉常见画质标签
            s = s.replace(Regex("""(4k|8k|uhd|fhd|hd|sd|蓝光|超高清|超清|高清|标清|综合)"""), "")
            // 去掉空白与连接符
            s = s.replace(Regex("""[\s\-_·•.+]+"""), "")
            return s
        }
    }
}
