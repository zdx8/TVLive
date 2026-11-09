package com.tvlive.tv.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 限高 ScrollView — 内容不足时按 wrap_content 收缩，超过 [maxHeightPx]
 * 后内部滚动（MENU 订阅列表用：D-pad 焦点移动时系统自动滚到焦点行）。
 *
 * 平台的 android:maxHeight 对 ScrollView 无效，所以自己量。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    /** 内容最大高度（px）；<=0 表示不限。 */
    var maxHeightPx: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hm = if (maxHeightPx > 0) {
            android.view.View.MeasureSpec.makeMeasureSpec(maxHeightPx, android.view.View.MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, hm)
    }
}
