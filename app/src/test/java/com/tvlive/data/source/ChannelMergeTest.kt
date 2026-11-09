package com.tvlive.tv.data.source

import com.tvlive.tv.data.model.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ChannelMerge] — the reconciliation that lets a subscription
 * refresh update a playlist without destroying it.
 *
 * The bug this guards against: the old implementation deleted every row
 * and re-inserted it, so each refresh produced new auto-increment ids.
 * `is_favorite` / `last_played_at` live on those rows and `recent_plays`
 * cascades from them, so favorites and history were silently wiped on
 * every cold start. Every assertion below is really "the user's data
 * would have survived".
 *
 * Pure logic — no Android, no database.
 */
class ChannelMergeTest {

    // ---------- helpers ----------

    private fun channel(
        url: String,
        name: String = url,
        groupTitle: String? = null,
        tvgId: String? = null,
        tvgName: String? = null,
        logo: String? = null,
        optionsJson: String? = null
    ) = ChannelEntity(
        playlistId = 1L,
        name = name,
        url = url,
        groupTitle = groupTitle,
        tvgId = tvgId,
        tvgName = tvgName,
        logo = logo,
        optionsJson = optionsJson
    )

    private fun stored(
        id: Long,
        url: String,
        name: String = url,
        groupTitle: String? = null,
        tvgId: String? = null,
        tvgName: String? = null,
        logo: String? = null,
        optionsJson: String? = null
    ) = ExistingChannelState(
        id = id,
        url = url,
        name = name,
        groupTitle = groupTitle,
        tvgId = tvgId,
        tvgName = tvgName,
        logo = logo,
        optionsJson = optionsJson
    )

    // ---------- the whole point: an unchanged refresh is a no-op ----------

    @Test
    fun `identical refresh plans no writes at all`() {
        val existing = listOf(stored(1, "http://a"), stored(2, "http://b"))
        val incoming = listOf(channel("http://a"), channel("http://b"))

        val plan = ChannelMerge.plan(existing, incoming)

        assertTrue("expected a completely empty plan, got $plan", plan.isEmpty)
        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.toUpdate.isEmpty())
        assertTrue(plan.toDelete.isEmpty())
    }

    @Test
    fun `unchanged rows keep their ids`() {
        val existing = listOf(stored(41, "http://a"), stored(42, "http://b"))
        val plan = ChannelMerge.plan(existing, listOf(channel("http://a"), channel("http://b")))

        // Nothing to write means nothing can renumber the rows, which is
        // what keeps is_favorite and recent_plays intact.
        assertEquals(emptyList<Pair<Long, ChannelEntity>>(), plan.toUpdate)
        assertEquals(emptyList<Long>(), plan.toDelete)
    }

    // ---------- inserts ----------

    @Test
    fun `empty table inserts everything`() {
        val incoming = listOf(channel("http://a"), channel("http://b"))
        val plan = ChannelMerge.plan(emptyList(), incoming)

        assertEquals(incoming, plan.toInsert)
        assertTrue(plan.toUpdate.isEmpty())
        assertTrue(plan.toDelete.isEmpty())
    }

    @Test
    fun `new channel is inserted and existing ones are left alone`() {
        val existing = listOf(stored(1, "http://a"))
        val plan = ChannelMerge.plan(
            existing,
            listOf(channel("http://a"), channel("http://c"))
        )

        assertEquals(listOf("http://c"), plan.toInsert.map { it.url })
        assertTrue(plan.toUpdate.isEmpty())
        assertTrue(plan.toDelete.isEmpty())
    }

    // ---------- in-place metadata updates ----------

    @Test
    fun `renamed channel updates metadata in place and keeps its id`() {
        val existing = listOf(stored(7, "http://a", name = "Old name"))
        val plan = ChannelMerge.plan(existing, listOf(channel("http://a", name = "New name")))

        assertEquals(1, plan.toUpdate.size)
        assertEquals(7L, plan.toUpdate.first().first)
        assertEquals("New name", plan.toUpdate.first().second.name)
        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.toDelete.isEmpty())
    }

    @Test
    fun `every provider column change is detected`() {
        val base = stored(1, "http://a")

        val cases = listOf(
            channel("http://a", name = "n"),
            channel("http://a", groupTitle = "g"),
            channel("http://a", tvgId = "id"),
            channel("http://a", tvgName = "tvg"),
            channel("http://a", logo = "http://logo"),
            channel("http://a", optionsJson = "[]")
        )

        for (incoming in cases) {
            val plan = ChannelMerge.plan(listOf(base), listOf(incoming))
            assertEquals(
                "change to $incoming should have been detected",
                1,
                plan.toUpdate.size
            )
        }
    }

    @Test
    fun `logo change is treated as a metadata update`() {
        val existing = listOf(stored(3, "http://a", logo = "http://old.png"))
        val plan = ChannelMerge.plan(existing, listOf(channel("http://a", logo = "http://new.png")))

        assertEquals(3L, plan.toUpdate.single().first)
        assertEquals("http://new.png", plan.toUpdate.single().second.logo)
        assertEquals(emptyList<Long>(), plan.toDelete)
    }

    // ---------- deletes ----------

    @Test
    fun `channel removed from the source is deleted`() {
        val existing = listOf(stored(1, "http://a"), stored(2, "http://gone"))
        val plan = ChannelMerge.plan(existing, listOf(channel("http://a")))

        assertEquals(listOf(2L), plan.toDelete)
        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.toUpdate.isEmpty())
    }

    @Test
    fun `empty source deletes every stored row`() {
        val existing = listOf(stored(1, "http://a"), stored(2, "http://b"))
        val plan = ChannelMerge.plan(existing, emptyList())

        assertEquals(listOf(1L, 2L), plan.toDelete)
    }

    // ---------- mixed, and duplicate URLs ----------

    @Test
    fun `mixed refresh inserts updates and deletes together`() {
        val existing = listOf(
            stored(1, "http://keep"),
            stored(2, "http://rename", name = "before"),
            stored(3, "http://drop")
        )
        val plan = ChannelMerge.plan(
            existing,
            listOf(
                channel("http://keep"),
                channel("http://rename", name = "after"),
                channel("http://new")
            )
        )

        assertEquals(listOf("http://new"), plan.toInsert.map { it.url })
        assertEquals(listOf(2L), plan.toUpdate.map { it.first })
        assertEquals(listOf(3L), plan.toDelete)
    }

    @Test
    fun `duplicate url in one source inserts the second occurrence`() {
        val existing = listOf(stored(1, "http://dup", name = "one"))
        val plan = ChannelMerge.plan(
            existing,
            listOf(channel("http://dup", name = "one"), channel("http://dup", name = "two"))
        )

        // First occurrence reuses the stored row untouched; the second
        // has no row left to pair with, so it is inserted.
        assertTrue(plan.toUpdate.isEmpty())
        assertEquals(listOf("two"), plan.toInsert.map { it.name })
        assertTrue(plan.toDelete.isEmpty())
    }

    @Test
    fun `leftover duplicate stored rows are cleaned up`() {
        // A playlist poisoned by the old destructive refresh path could
        // hold two rows for one URL; the merge must not orphan either.
        val existing = listOf(stored(1, "http://dup"), stored(2, "http://dup"))
        val plan = ChannelMerge.plan(existing, listOf(channel("http://dup")))

        assertEquals(listOf(2L), plan.toDelete)
        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.toUpdate.isEmpty())
    }

    @Test
    fun `every stored row and every incoming channel is accounted for exactly once`() {
        val existing = listOf(
            stored(1, "http://a"),                 // unchanged  → no action
            stored(2, "http://b", name = "old"),   // changed    → update
            stored(3, "http://c")                  // vanished   → delete
        )
        val incoming = listOf(
            channel("http://a"),                   // matched, unchanged
            channel("http://b", name = "new"),     // matched, changed
            channel("http://d")                    // new
        )
        val plan = ChannelMerge.plan(existing, incoming)

        // `a` is the single row that needs no action, so it is the one
        // element that appears in neither side of the accounting.
        assertEquals(existing.size, plan.toUpdate.size + plan.toDelete.size + 1)
        assertEquals(incoming.size, plan.toUpdate.size + plan.toInsert.size + 1)

        // And no row is both updated and deleted.
        val updatedIds = plan.toUpdate.map { it.first }.toSet()
        assertTrue(updatedIds.intersect(plan.toDelete.toSet()).isEmpty())
    }

    // ---------- scale ----------

    @Test
    fun `large unchanged playlist plans nothing`() {
        val size = 5_000
        val existing = (0 until size).map { stored(it.toLong(), "http://ch/$it") }
        val incoming = (0 until size).map { channel("http://ch/$it") }

        val plan = ChannelMerge.plan(existing, incoming)

        assertTrue("a 5000-channel no-op refresh must not write", plan.isEmpty)
    }

    @Test
    fun `delete batches stay within the sqlite bind-variable budget`() {
        assertTrue(ChannelMerge.DELETE_CHUNK_SIZE <= 999)
    }
}
