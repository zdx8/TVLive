package com.tvlive.update

import com.tvlive.tv.update.ReleaseNotesFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesFormatterTest {

    @Test
    fun `markdown headings bullets links and code are flattened`() {
        val raw = """
            ## v2.1.0（2026-11-22）

            新增 EPG 电子节目单。
            ### 新增
            - EPG：自动识别 `url-tvg` / `x-tvg-url`
            - [查看文档](https://example.com/docs)
            > 引用一行
        """.trimIndent()

        val out = ReleaseNotesFormatter.format(raw)

        assertFalse(out.contains("##"))
        assertFalse(out.contains("`"))
        assertFalse(out.contains("]("))
        assertTrue(out.contains("• EPG：自动识别 url-tvg / x-tvg-url"))
        assertTrue(out.contains("查看文档"))
        assertTrue(out.lines().all { it.isNotBlank() })
    }

    @Test
    fun `long body is capped so buttons stay on screen`() {
        val raw = (1..40).joinToString("\n") { "- 第 $it 条很长的更新说明文字用于撑爆电视弹窗" }

        val out = ReleaseNotesFormatter.format(raw)

        assertTrue("实际 ${out.length} 字符", out.length <= 360)
        assertTrue("实际 ${out.lines().size} 行", out.lines().size <= 12)
        assertTrue(out.contains("完整说明见 GitHub Release"))
    }

    @Test
    fun `blank body yields empty text`() {
        assertEquals("", ReleaseNotesFormatter.format("  \n\n  "))
    }
}
