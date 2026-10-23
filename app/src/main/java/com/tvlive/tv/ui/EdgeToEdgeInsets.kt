package com.tvlive.tv.ui

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Applies system-bar (status / nav) insets as **additional** padding
 * on a view so its content doesn't sit behind the OS chrome.
 *
 * Android 15 (API 35) made edge-to-edge the default — the system bars
 * are transparent and the app is responsible for offsetting its own
 * content. TVs don't usually have a software nav bar, but they do
 * render the status bar (timestamp / signal row) at the top.
 *
 * Two things this helper gets right that the previous version did not:
 *
 *  1. **Additive, not replacing.** The inset is added to whatever
 *     padding the view already declares in XML. The old version called
 *     `updatePadding(...)` with only the inset values, wiping the
 *     designed `12dp` strip padding.
 *
 *  2. **Idempotent.** The base padding is captured once at install
 *     time, so repeated inset dispatches don't accumulate.
 *
 * Note: applying this to a container that holds the video SurfaceView
 * will shrink the video. For full-bleed playback apply it to the
 * overlay strips instead — see `PlaybackActivity`.
 */
object EdgeToEdgeInsets {

    private val APPLIED_TAG = -0x77E1D   // sentinel to avoid double-install

    fun apply(
        root: View,
        applyTop: Boolean = true,
        applyBottom: Boolean = true,
        applyLeft: Boolean = true,
        applyRight: Boolean = true
    ) {
        if (root.getTag(APPLIED_TAG) == true) return
        root.setTag(APPLIED_TAG, true)

        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val bars: Insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )
            v.updatePadding(
                left   = baseLeft   + if (applyLeft)   bars.left   else 0,
                top    = baseTop    + if (applyTop)    bars.top    else 0,
                right  = baseRight  + if (applyRight)  bars.right  else 0,
                bottom = baseBottom + if (applyBottom) bars.bottom else 0
            )
            // Return the insets unconsumed so children that want to
            // react to IME / display-cutout can still see them.
            windowInsets
        }
        // Kick the first dispatch — without this the padding is only
        // applied once something else triggers an inset pass.
        ViewCompat.requestApplyInsets(root)
    }
}
