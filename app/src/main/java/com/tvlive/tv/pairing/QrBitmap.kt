package com.tvlive.tv.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码位图渲染 — 纯生成，不做识图。
 *
 * 选型说明：设计文档 ADR #1 原选 nayuki/qrcodegen 单文件源码拷入，
 * 实施时改为 ZXing core（纯 Java、MIT）：约 1100 行编码算法手工拷贝
 * 难以保证逐行正确，ZXing 一行依赖、正确性由上游保证；R8 minify 后
 * 实际只打包用到的类（实测增量约 30-50KB），远小于原始 jar 体积。
 */
object QrBitmap {

    /** 生成黑底白场的二维码位图（565 足够，黑白两色）。 */
    fun make(content: String, sizePx: Int, quietZoneModules: Int = 2): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to quietZoneModules
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        val black = Color.BLACK
        val white = Color.WHITE
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix[x, y]) black else white
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565)
    }
}
