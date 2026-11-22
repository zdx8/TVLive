package com.tvlive.tv.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class XmltvParserTest {

    @Test
    fun `parseXmltvTime handles explicit positive timezone`() {
        val ms = XmltvParser.parseXmltvTime("20261122190000 +0800")
        assertTrue(ms > 0L)
        val utcMs = XmltvParser.parseXmltvTime("20261122110000 Z")
        assertEquals(utcMs, ms)
    }

    @Test
    fun `parseXmltvTime handles missing seconds`() {
        val ms1 = XmltvParser.parseXmltvTime("202611221900 +0800")
        val ms2 = XmltvParser.parseXmltvTime("20261122190000 +0800")
        assertEquals(ms2, ms1)
    }

    @Test
    fun `parse extracts channels display names and now-next programmes`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1">
                <display-name>CCTV-1 综合</display-name>
                <display-name>CCTV1</display-name>
              </channel>
              <programme start="20261122190000 +0800" stop="20261122193000 +0800" channel="cctv1">
                <title>新闻联播</title>
                <desc>每日新闻</desc>
              </programme>
              <programme start="20261122193800 +0800" stop="20261122195500 +0800" channel="cctv1">
                <title>焦点访谈</title>
              </programme>
            </tv>
        """.trimIndent()

        val nowMs = XmltvParser.parseXmltvTime("20261122191500 +0800")
        val index = XmltvParser.parse(
            ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)),
            nowMs = nowMs
        )

        assertFalse(index.isEmpty)
        assertEquals(1, index.channelCount)
        assertEquals(2, index.programCount)

        // Exact tvg-id match
        val nowNext = index.nowNext(tvgId = "cctv1", tvgName = null, name = null, nowMs = nowMs)
        assertEquals("新闻联播", nowNext.current?.title)
        assertEquals("焦点访谈", nowNext.next?.title)
        assertEquals(0.5f, nowNext.current!!.progressAt(nowMs), 0.01f)

        // Fuzzy channel name match (CCTV-1 高清 -> cctv1)
        val fuzzy = index.nowNext(tvgId = null, tvgName = null, name = "CCTV-1 高清", nowMs = nowMs)
        assertNotNull(fuzzy.current)
        assertEquals("新闻联播", fuzzy.current?.title)
    }

    @Test
    fun `parse auto-decompresses gzipped xmltv`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="hunan"><display-name>湖南卫视</display-name></channel>
              <programme start="20261122200000 +0800" stop="20261122210000 +0800" channel="hunan">
                <title>快乐大本营</title>
              </programme>
            </tv>
        """.trimIndent()

        val gzBytes = ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            bos.toByteArray()
        }

        val nowMs = XmltvParser.parseXmltvTime("20261122201000 +0800")
        val index = XmltvParser.parse(ByteArrayInputStream(gzBytes), nowMs = nowMs)
        val res = index.nowNext(tvgId = null, tvgName = "湖南卫视高清", name = null, nowMs = nowMs)
        assertEquals("快乐大本营", res.current?.title)
        assertNull(res.next)
    }

    @Test
    fun `normalizeChannelName strips HD tags and punctuation`() {
        assertEquals("cctv1", EpgIndex.normalizeChannelName("CCTV-1 综合(高清)"))
        assertEquals("湖南卫视", EpgIndex.normalizeChannelName("湖南卫视 4K"))
    }

    @Test
    fun `parse reads GB18030 declared encoding without mojibake`() {
        val xml = "<?xml version=\"1.0\" encoding=\"GB18030\"?><tv>" +
            "<channel id=\"cctv1\"><display-name>综合</display-name></channel>" +
            "<programme start=\"20261122200000 +0800\" stop=\"20261122210000 +0800\" channel=\"cctv1\">" +
            "<title>中文节目单</title></programme></tv>"
        val bytes = xml.toByteArray(java.nio.charset.Charset.forName("GB18030"))
        val now = XmltvParser.parseXmltvTime("20261122203000 +0800")
        val index = XmltvParser.parse(ByteArrayInputStream(bytes), nowMs = now)
        assertEquals("中文节目单", index.nowNext("cctv1", null, null, now).current?.title)
    }

    @Test
    fun `parse keeps program title and description with nested child elements`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="c1"><display-name>测试</display-name></channel>
              <programme start="20261122200000 +0800" stop="20261122210000 +0800" channel="c1">
                <title lang="zh">带子元素的标题</title>
                <desc>第一行<br/>第二行</desc>
              </programme>
            </tv>
        """.trimIndent()
        val now = XmltvParser.parseXmltvTime("20261122203000 +0800")
        val index = XmltvParser.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), nowMs = now)
        val prog = index.nowNext("c1", null, null, now).current!!
        assertEquals("带子元素的标题", prog.title)
        assertEquals("第一行第二行", prog.description)
    }
}
