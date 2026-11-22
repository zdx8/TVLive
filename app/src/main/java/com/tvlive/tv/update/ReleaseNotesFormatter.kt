package com.tvlive.tv.update

/**
 * GitHub Release body（Markdown）→ 电视弹窗可读的纯文本。
 *
 * 不修这段会出真机事故：Release 说明原样塞进 TextView，19 行正文把
 * 「以后 / 立即更新」按钮顶出屏幕，遥控器按不到，更新流程直接卡死。
 *
 * 这里只做展示层压缩，Info.notes 仍保留原文供日志排查。
 */
internal object ReleaseNotesFormatter {

    private const val MAX_LINES = 12
    private const val MAX_CHARS = 360
    private const val MORE = "…（完整说明见 GitHub Release 页）"

    private val HEADING = Regex("^#{1,6}\\s*")
    private val BULLET = Regex("^\\s*[-*+]\\s+")
    private val QUOTE = Regex("^\\s*>\\s?")
    private val LINK = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val CODE = Regex("`+")

    fun format(raw: String): String {
        val cleaned = raw.lineSequence()
            .map { line ->
                var s = line.trim()
                s = HEADING.replace(s, "")
                s = BULLET.replace(s, "• ")
                s = QUOTE.replace(s, "")
                s = LINK.replace(s, "$1")
                s = CODE.replace(s, "")
                s.trim()
            }
            .filter { it.isNotEmpty() }
            .toList()

        if (cleaned.isEmpty()) return ""

        val budget = MAX_CHARS - MORE.length
        val sb = StringBuilder()
        var count = 0
        var truncated = false
        for (line in cleaned) {
            if (count >= MAX_LINES) {
                truncated = true
                break
            }
            if (sb.isNotEmpty()) sb.append('\n')
            if (sb.length + line.length > budget) {
                val room = budget - sb.length
                if (room > 0) sb.append(line.take(room))
                truncated = true
                break
            }
            sb.append(line)
            count++
        }
        if (truncated) sb.append(MORE)
        return sb.toString()
    }
}
