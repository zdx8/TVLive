package com.tvlive.update

import com.tvlive.tv.update.UpdateAssetPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAssetPickerTest {

    private val privateApk = UpdateAssetPicker.Asset(
        "TVLive-v2.1.1-private-release.apk", "https://example/private.apk", 4_373_962L
    )
    private val publicApk = UpdateAssetPicker.Asset(
        "TVLive-v2.1.1-release.apk", "https://example/public.apk", 4_372_312L
    )

    @Test
    fun `public build never picks private apk even when listed first`() {
        val picked = UpdateAssetPicker.pick(listOf(privateApk, publicApk), privateBuild = false)
        assertEquals(publicApk, picked)
    }

    @Test
    fun `private build picks private apk`() {
        val picked = UpdateAssetPicker.pick(listOf(publicApk, privateApk), privateBuild = true)
        assertEquals(privateApk, picked)
    }

    @Test
    fun `public build returns null when only private apk exists`() {
        assertNull(UpdateAssetPicker.pick(listOf(privateApk), privateBuild = false))
    }

    @Test
    fun `private build returns null when only public apk exists`() {
        assertNull(UpdateAssetPicker.pick(listOf(publicApk), privateBuild = true))
    }

    @Test
    fun `blank download url is skipped`() {
        val broken = privateApk.copy(url = "")
        assertNull(UpdateAssetPicker.pick(listOf(broken), privateBuild = true))
    }
}
