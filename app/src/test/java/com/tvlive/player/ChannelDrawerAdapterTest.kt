package com.tvlive.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChannelDrawerAdapter.isRowSelected] — the rule
 * that drives the "currently playing" blue background on the
 * drawer's row.
 *
 * Pure function — no Android dependencies, runs on plain JVM.
 */
class ChannelDrawerAdapterTest {

    @Test
    fun `row matching currentIndex is selected`() {
        assertTrue(ChannelDrawerAdapter.isRowSelected(position = 3, currentIndex = 3))
    }

    @Test
    fun `row not matching currentIndex is not selected`() {
        assertFalse(ChannelDrawerAdapter.isRowSelected(position = 0, currentIndex = 3))
        assertFalse(ChannelDrawerAdapter.isRowSelected(position = 5, currentIndex = 3))
    }

    @Test
    fun `no current channel selected when currentIndex is -1`() {
        assertFalse(ChannelDrawerAdapter.isRowSelected(0, -1))
        assertFalse(ChannelDrawerAdapter.isRowSelected(99, -1))
    }

    @Test
    fun `first row is selected when currentIndex is 0`() {
        assertTrue(ChannelDrawerAdapter.isRowSelected(0, 0))
    }

    @Test
    fun `last row is selected when currentIndex is last index`() {
        assertTrue(ChannelDrawerAdapter.isRowSelected(99, 99))
    }
}
