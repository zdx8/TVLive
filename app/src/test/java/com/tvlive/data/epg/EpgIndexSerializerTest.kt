package com.tvlive.data.epg

import com.tvlive.tv.data.epg.EpgIndex
import com.tvlive.tv.data.epg.EpgIndexSerializer
import com.tvlive.tv.data.epg.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class EpgIndexSerializerTest {

    private fun sampleIndex(): EpgIndex {
        val programs = mapOf(
            "cctv1" to listOf(
                EpgProgram("cctv1", "新闻联播", 1763700000000L, 1763701800000L, "今日要闻"),
                EpgProgram("cctv1", "焦点访谈", 1763701800000L, 1763703000000L, null)
            ),
            "hunan" to listOf(
                EpgProgram("hunan", "快乐大本营", 1763700000000L, 1763701200000L, "综艺<介绍>&更多")
            )
        )
        val aliases = mapOf("cctv1" to "cctv1", "cctv-1" to "cctv1", "湖南卫视" to "hunan")
        return EpgIndex(programs, aliases)
    }

    @Test
    fun `round trip preserves programs and aliases`() {
        val original = sampleIndex()
        val bos = ByteArrayOutputStream()
        EpgIndexSerializer.write(original, bos)
        val restored = EpgIndexSerializer.read(ByteArrayInputStream(bos.toByteArray()))!!

        assertEquals(original.channelCount, restored.channelCount)
        assertEquals(original.programCount, restored.programCount)
        assertEquals("新闻联播", restored.nowNext("cctv1", null, null, 1763700100000L).current?.title)
        assertEquals("焦点访谈", restored.nowNext("cctv1", null, null, 1763701900000L).current?.title)
        assertEquals("综艺<介绍>&更多", restored.programsFor("hunan", null, null).first().description)
        // 别名匹配：归一化名仍能解析到节目
        assertEquals("快乐大本营", restored.nowNext(null, "湖南卫视", null, 1763700100000L).current?.title)
    }

    @Test
    fun `corrupt or foreign data returns null`() {
        assertNull(EpgIndexSerializer.read(ByteArrayInputStream("not binary".toByteArray())))
        assertNull(EpgIndexSerializer.read(ByteArrayInputStream(byteArrayOf()))) 
    }

    @Test
    fun `large description beyond writeUTF limit survives`() {
        val longDesc = "很长".repeat(40_000) // 约 240 KB，超过 writeUTF 的 64 KB 上限
        val idx = EpgIndex(
            mapOf("c1" to listOf(EpgProgram("c1", "标题", 1000L, 2000L, longDesc))),
            emptyMap()
        )
        val bos = ByteArrayOutputStream()
        EpgIndexSerializer.write(idx, bos)
        assertTrue("encoded size should exceed 64KB", bos.size() > 65535)
        val restored = EpgIndexSerializer.read(ByteArrayInputStream(bos.toByteArray()))!!
        assertEquals(longDesc, restored.programsFor("c1", null, null).first().description)
    }
}
