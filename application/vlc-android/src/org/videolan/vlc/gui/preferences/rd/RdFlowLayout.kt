/*
 * VLC Redesign: buttons in a row that go on to the next line when they don't fit (a phone's width, long words of a
 * translation), from the start side (right to left as well).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.preferences.rd

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

class RdFlowLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val gap = context.rdDp(8f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var line = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > width) {
                y += line + gap
                x = 0
                line = 0
            }
            x += c.measuredWidth + gap
            widest = maxOf(widest, x - gap)
            line = maxOf(line, c.measuredHeight)
        }
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else widest + paddingLeft + paddingRight
        setMeasuredDimension(w, y + line + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val width = r - l - paddingLeft - paddingRight
        var x = 0
        var y = paddingTop
        var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > width) {
                y += line + gap
                x = 0
                line = 0
            }
            val left = if (rtl) paddingLeft + width - x - c.measuredWidth else paddingLeft + x
            c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            line = maxOf(line, c.measuredHeight)
        }
    }
}
