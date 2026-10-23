package com.tvlive.tv.ui

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.tvlive.tv.R

/**
 * Color helpers backed by [R.color] entries. Putting the magic
 * strings in `colors.xml` is the long-term right answer; this
 * object keeps the call sites short.
 */
object ColorExt {
    @ColorInt
    fun statusError(ctx: Context) = ContextCompat.getColor(ctx, R.color.text_status_error)

    @ColorInt
    fun statusOk(ctx: Context) = ContextCompat.getColor(ctx, R.color.text_status_ok)

    @ColorInt
    fun statusWarn(ctx: Context) = Color.parseColor("#FFD54F")

    @ColorInt
    fun statusMuted(ctx: Context) = Color.parseColor("#9CA3AF")

    @ColorInt
    fun brandBlue(ctx: Context) = Color.parseColor("#1976D2")

    @ColorInt
    fun surfaceDark(ctx: Context) = Color.parseColor("#101418")
}
