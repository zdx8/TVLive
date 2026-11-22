package com.tvlive.tv.data.epg

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/**
 * 流式 XMLTV 解析器（零第三方依赖，基于 Android/JVM 内置 XmlPullParser）。
 *
 * 支持：
 * - 明文 `.xml` 与 GZIP 压缩 `.xml.gz`（按魔数 0x1f 0x8b 自动识别）
 * - `<channel id="..."><display-name>...</display-name></channel>`
 * - `<programme start="..." stop="..." channel="..."><title>...</title><desc>...</desc></programme>`
 * - 自动按时间窗裁剪，避免 7 天大体积 XMLTV 占满电视盒子内存
 */
object XmltvParser {

    /** 默认只保留当前时间前 2 小时到后 36 小时的节目，兼顾当天回顾与次日预告。 */
    const val DEFAULT_PAST_WINDOW_MS = 2 * 60 * 60 * 1000L
    const val DEFAULT_FUTURE_WINDOW_MS = 36 * 60 * 60 * 1000L

    fun parse(
        input: InputStream,
        nowMs: Long = System.currentTimeMillis(),
        windowStartMs: Long = nowMs - DEFAULT_PAST_WINDOW_MS,
        windowEndMs: Long = nowMs + DEFAULT_FUTURE_WINDOW_MS
    ): EpgIndex {
        // GZIPInputStream 是本方法自己包出来的，必须由本方法关闭；调用方只
        // 持有原始 FileInputStream / ByteArrayInputStream，关不到这一层。
        val stream = maybeGunzip(input)
        return try {
            parseStream(stream, nowMs, windowStartMs, windowEndMs)
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun parseStream(
        stream: InputStream,
        nowMs: Long,
        windowStartMs: Long,
        windowEndMs: Long
    ): EpgIndex {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val parser = factory.newPullParser()
        // 国内 XMLTV 源大量用 GB18030/GBK；不能写死 UTF-8。kxml2 的
        // setInput(InputStream, null) 不会嗅探声明编码，所以自己读 XML
        // 声明里的 encoding 再构造 InputStreamReader。
        val buffered = if (stream is BufferedInputStream) stream else BufferedInputStream(stream, 8192)
        val declared = sniffEncoding(buffered)
        val reader = if (declared == null) {
            InputStreamReader(buffered, Charsets.UTF_8)
        } else {
            runCatching { InputStreamReader(buffered, Charset.forName(declared)) }
                .getOrElse { InputStreamReader(buffered, Charsets.UTF_8) }
        }
        parser.setInput(reader)

        val programsMap = HashMap<String, MutableList<EpgProgram>>()
        val aliasMap = HashMap<String, String>()

        var event = parser.eventType
        var currentChannelId: String? = null

        var inProgramme = false
        var progChannelId = ""
        var progStartMs = -1L
        var progStopMs = -1L
        var progTitle: String? = null
        var progDesc: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "channel" -> {
                            currentChannelId = parser.getAttributeValue(null, "id")
                                ?.trim()
                                ?.lowercase(Locale.ROOT)
                                ?.takeIf { it.isNotEmpty() }
                            if (currentChannelId != null) {
                                val normId = EpgIndex.normalizeChannelName(currentChannelId)
                                if (normId.isNotEmpty()) {
                                    aliasMap.putIfAbsent(normId, currentChannelId)
                                }
                            }
                        }

                        "display-name" -> {
                            val chId = currentChannelId
                            if (chId != null) {
                                val text = safeNextText(parser).trim()
                                if (text.isNotEmpty()) {
                                    val lower = text.lowercase(Locale.ROOT)
                                    aliasMap.putIfAbsent(lower, chId)
                                    val norm = EpgIndex.normalizeChannelName(lower)
                                    if (norm.isNotEmpty()) {
                                        aliasMap.putIfAbsent(norm, chId)
                                    }
                                }
                            }
                        }

                        "programme" -> {
                            inProgramme = true
                            progChannelId = parser.getAttributeValue(null, "channel")
                                ?.trim()
                                ?.lowercase(Locale.ROOT)
                                .orEmpty()
                            progStartMs = parseXmltvTime(parser.getAttributeValue(null, "start"))
                            progStopMs = parseXmltvTime(parser.getAttributeValue(null, "stop"))
                            progTitle = null
                            progDesc = null
                        }

                        "title" -> {
                            if (inProgramme && progTitle == null) {
                                progTitle = safeNextText(parser).trim().takeIf { it.isNotEmpty() }
                            }
                        }

                        "desc" -> {
                            if (inProgramme && progDesc == null) {
                                progDesc = safeNextText(parser).trim().takeIf { it.isNotEmpty() }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase(Locale.ROOT)) {
                        "channel" -> currentChannelId = null
                        "programme" -> {
                            if (inProgramme &&
                                progChannelId.isNotEmpty() &&
                                progStartMs > 0L &&
                                !progTitle.isNullOrEmpty()
                            ) {
                                val effectiveStop = if (progStopMs > progStartMs) {
                                    progStopMs
                                } else {
                                    progStartMs + 30 * 60 * 1000L
                                }
                                // 只保留时间窗内节目
                                if (effectiveStop >= windowStartMs && progStartMs <= windowEndMs) {
                                    val list = programsMap.getOrPut(progChannelId) { mutableListOf() }
                                    list += EpgProgram(
                                        channelId = progChannelId,
                                        title = progTitle!!,
                                        startMs = progStartMs,
                                        stopMs = effectiveStop,
                                        description = progDesc
                                    )
                                }
                            }
                            inProgramme = false
                        }
                    }
                }
            }
            event = parser.next()
        }

        // 确保每个频道节目按开始时间升序排列，并补充 channelId 的归一化别名
        programsMap.forEach { (chId, list) ->
            list.sortBy { it.startMs }
            val norm = EpgIndex.normalizeChannelName(chId)
            if (norm.isNotEmpty()) {
                aliasMap.putIfAbsent(norm, chId)
            }
        }

        return EpgIndex(programsMap, aliasMap)
    }

    /**
     * 解析 XMLTV 时间格式：
     * - `20261122190000 +0800`
     * - `20261122190000 -0500`
     * - `20261122110000 Z`
     * - `20261122190000`（无时区时按设备默认时区）
     * - `202611221900`（缺秒数时补 00）
     */
    fun parseXmltvTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return -1L
        val trimmed = raw.trim()
        val parts = trimmed.split(Regex("""\s+"""), limit = 2)
        val digits = parts[0]
        if (digits.length < 12) return -1L

        val year = digits.substring(0, 4).toIntOrNull() ?: return -1L
        val month = digits.substring(4, 6).toIntOrNull() ?: return -1L
        val day = digits.substring(6, 8).toIntOrNull() ?: return -1L
        val hour = digits.substring(8, 10).toIntOrNull() ?: return -1L
        val minute = digits.substring(10, 12).toIntOrNull() ?: return -1L
        val second = if (digits.length >= 14) digits.substring(12, 14).toIntOrNull() ?: 0 else 0

        val tz: TimeZone = if (parts.size >= 2 && parts[1].isNotBlank()) {
            parseTimeZone(parts[1].trim())
        } else {
            TimeZone.getDefault()
        }

        val cal = Calendar.getInstance(tz, Locale.US).apply {
            isLenient = false
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }
        return runCatching { cal.timeInMillis }.getOrDefault(-1L)
    }

    private fun parseTimeZone(tzToken: String): TimeZone {
        val upper = tzToken.uppercase(Locale.US)
        if (upper == "Z" || upper == "UTC" || upper == "GMT") {
            return TimeZone.getTimeZone("UTC")
        }
        // 形如 +0800 / -0530 / +08:00
        val clean = upper.replace(":", "")
        if ((clean.startsWith("+") || clean.startsWith("-")) && clean.length == 5) {
            return TimeZone.getTimeZone("GMT${clean.substring(0, 3)}:${clean.substring(3, 5)}")
        }
        return TimeZone.getDefault()
    }

    private fun maybeGunzip(input: InputStream): InputStream {
        val bis = if (input is BufferedInputStream) input else BufferedInputStream(input, 8192)
        bis.mark(2)
        val b0 = bis.read()
        val b1 = bis.read()
        bis.reset()
        return if (b0 == 0x1F && b1 == 0x8B) {
            GZIPInputStream(bis)
        } else {
            bis
        }
    }

    /** 从 XML 声明里嗅探 encoding，例如 `<?xml version="1.0" encoding="GB18030"?>`。 */
    private fun sniffEncoding(stream: BufferedInputStream): String? {
        stream.mark(512)
        return try {
            val head = ByteArray(512)
            val n = stream.read(head)
            if (n <= 0) return null
            val text = String(head, 0, n, Charsets.ISO_8859_1)
            val match = Regex("""<\?xml[^>]*encoding\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(text) ?: return null
            match.groupValues[1].trim().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            stream.reset()
        }
    }

    /**
     * 读取当前 START_TAG 下全部 TEXT（含子元素里的文本），停在对应 END_TAG。
     * 不能用 nextText()：XMLTV 的 <desc> 常带 <br/> / <span> 等子元素，
     * nextText() 遇到子元素会抛 XmlPullParserException，导致整条简介丢失。
     */
    private fun safeNextText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 0
        try {
            if (parser.eventType == XmlPullParser.START_TAG) {
                depth = 1
                while (depth > 0) {
                    val event = parser.next()
                    if (event == XmlPullParser.END_DOCUMENT) break
                    when (event) {
                        XmlPullParser.START_TAG -> depth++
                        XmlPullParser.END_TAG -> depth--
                        XmlPullParser.TEXT -> sb.append(parser.text)
                    }
                }
            }
        } catch (_: Exception) {
            // 单个字段解析失败不应丢掉整份节目单，返回已读到的部分。
        }
        return sb.toString()
    }
}
