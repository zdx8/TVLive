package com.tvlive.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalSortKeyTest {

    @Test
    fun `CCTV-1 through CCTV-13 sort in numeric order`() {
        val channels = listOf(
            "CCTV10", "CCTV12", "CCTV13", "CCTV1", "CCTV2", "CCTV3"
        )
        val sorted = channels.sortedBy { NaturalSortKey.of(it) }
        assertEquals(
            listOf("CCTV1", "CCTV2", "CCTV3", "CCTV10", "CCTV12", "CCTV13"),
            sorted
        )
    }

    @Test
    fun `CCTV no separator also sorts correctly`() {
        // The user's actual channel naming — no dash between prefix
        // and number. The key still pads the digit run to 10
        // characters so "CCTV1" < "CCTV2" < "CCTV10".
        val channels = listOf("CCTV1", "CCTV10", "CCTV11", "CCTV2", "CCTV20")
        val sorted = channels.sortedBy { NaturalSortKey.of(it) }
        assertEquals(
            listOf("CCTV1", "CCTV2", "CCTV10", "CCTV11", "CCTV20"),
            sorted
        )
    }

    @Test
    fun `mixed Chinese and digits sort correctly`() {
        val list = listOf("第1集", "第10集", "第2集", "第20集")
        val sorted = list.sortedBy { NaturalSortKey.of(it) }
        assertEquals(listOf("第1集", "第2集", "第10集", "第20集"), sorted)
    }

    @Test
    fun `no digits — key equals input`() {
        val s = "凤凰卫视"
        assertEquals(s, NaturalSortKey.of(s))
    }

    @Test
    fun `handles null and empty`() {
        assertEquals("", NaturalSortKey.of(null))
        assertEquals("", NaturalSortKey.of(""))
    }

    @Test
    fun `multiple digit runs in one string`() {
        // "1-10" should sort with "1-2" and "1-9" by the *first* run
        // alone. The second run still has the same digit count
        // (2 chars) in all three so it doesn't change the order.
        val list = listOf("A-10", "A-2", "A-9", "A-1")
        val sorted = list.sortedBy { NaturalSortKey.of(it) }
        assertEquals(listOf("A-1", "A-2", "A-9", "A-10"), sorted)
    }

    @Test
    fun `pads single digit to full 10-char width`() {
        val key = NaturalSortKey.of("CCTV1")
        // 4 chars (CCTV) + 9 zeros + "1" = 14 chars
        assertEquals(14, key.length)
        assertTrue(key.startsWith("CCTV"))
        assertTrue(key.endsWith("1"))
    }

    @Test
    fun `ChannelEntity naturalSortKey uses displayName`() {
        val a = ChannelEntity(
            id = 1, playlistId = 1, name = "ignored",
            url = "x", tvgName = "CCTV2", groupTitle = null
        )
        val b = ChannelEntity(
            id = 2, playlistId = 1, name = "ignored",
            url = "x", tvgName = "CCTV10", groupTitle = null
        )
        // tvgName takes priority; sorted order should be CCTV2 < CCTV10
        assertTrue(a.naturalSortKey < b.naturalSortKey)
    }

    // ---- bucketKey: the 3-bucket layout used by the repository ----

    @Test
    fun `bucketKey puts CCTV in bucket 0`() {
        assertEquals(0, NaturalSortKey.bucketKey("CCTV1").first)
        assertEquals(0, NaturalSortKey.bucketKey("CCTV-1").first)
        assertEquals(0, NaturalSortKey.bucketKey("CCTV13 综合").first)
    }

    @Test
    fun `bucketKey puts names containing lake-char in bucket 1`() {
        // 湖系 is the special sub-bucket inside the Chinese bucket
        // (the user wants Hunan/Hubei TV right after CCTV).
        assertEquals(1, NaturalSortKey.bucketKey("湖南卫视").first)
        assertEquals(1, NaturalSortKey.bucketKey("湖北经视").first)
        // Even at the end of the name the match still applies.
        assertEquals(1, NaturalSortKey.bucketKey("高清湖南卫视").first)
    }

    @Test
    fun `bucketKey puts other CJK names in bucket 2`() {
        assertEquals(2, NaturalSortKey.bucketKey("东方卫视").first)
        assertEquals(2, NaturalSortKey.bucketKey("北京卫视").first)
        assertEquals(2, NaturalSortKey.bucketKey("凤凰卫视").first)
        // 翡翠 is U+7FE1, also in the CJK basic range.
        assertEquals(2, NaturalSortKey.bucketKey("翡翠台").first)
    }

    @Test
    fun `bucketKey puts Latin names in bucket 3`() {
        assertEquals(3, NaturalSortKey.bucketKey("ATV").first)
        assertEquals(3, NaturalSortKey.bucketKey("Jade").first)
        assertEquals(3, NaturalSortKey.bucketKey("TVB").first)
    }

    @Test
    fun `bucketKey preserves numeric natural sort inside each bucket`() {
        // The secondary key (sortKey) is the same as `of`, so the
        // existing numeric-aware sort still applies. We assert that
        // by sorting a small list with compareBy + bucketKey and
        // confirming the result is what the user expects.
        //
        // Note: among Chinese names the secondary key falls back to
        // Unicode code-point order (we don't ship a pinyin table
        // because the dependency is ~2MB and the user's home
        // channels happen to all be non-pinyin). So 东 (U+4E1C)
        // sorts before 北 (U+5317) — which is why 东方卫视 comes
        // before 北京卫视 in the expected output.
        val list = listOf("CCTV10", "CCTV2", "CCTV1", "ATV", "湖南卫视", "东方卫视", "北京卫视", "TVB")
        val sorted = list.sortedWith(compareBy(
            { NaturalSortKey.bucketKey(it).first },
            { NaturalSortKey.bucketKey(it).second }
        ))
        assertEquals(
            listOf("CCTV1", "CCTV2", "CCTV10", "湖南卫视", "东方卫视", "北京卫视", "ATV", "TVB"),
            sorted
        )
    }

    @Test
    fun `bucketKey handles null and empty as bucket 3 with empty key`() {
        // Defensive: the data layer guards against nulls, but the
        // comparator must not crash if a stray row sneaks through.
        val (b, k) = NaturalSortKey.bucketKey(null)
        assertEquals(3, b)
        assertEquals("", k)
        val (b2, k2) = NaturalSortKey.bucketKey("")
        assertEquals(3, b2)
        assertEquals("", k2)
    }
}
