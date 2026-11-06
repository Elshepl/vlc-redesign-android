/*
 * VLC Redesign: the music player's seek well as the design's waveform (Figma 4767:23726, the Samsung TV app's
 * player.js drawWave / player.css .pl-wave): the #090909 well (radius 4), bars 2 px wide every 5 px from 4 px in,
 * 2 to 20 px high in the middle, #AB6314 up to the position and #404040 after it. The heights come from the song's
 * title (the same title, the same wave), as the TV app's when it has no peaks. The SeekBar's level is the position.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.television.ui.audioplayer

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

class RdWaveDrawable(private val px: Float, title: String) : Drawable() {

    private val heights: IntArray
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        var seed = 0L
        for (c in title) seed = (seed * 31 + c.code) % 2147483647
        heights = IntArray(400) {
            seed = (seed * 16807) % 2147483647
            2 + 2 * (seed % 10).toInt()
        }
    }

    override fun onLevelChange(level: Int): Boolean {
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = 0xFF090909.toInt()
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        canvas.drawRoundRect(rect, 4 * px, 4 * px, paint)
        val played = b.left + b.width() * level / 10000f
        val mid = b.exactCenterY()
        var x = b.left + 4 * px
        var i = 0
        while (x < b.right - 4 * px) {
            val h = heights[i % heights.size] * px
            paint.color = if (x < played) 0xFFAB6314.toInt() else 0xFF404040.toInt()
            canvas.drawRect(x, mid - h / 2, x + 2 * px, mid + h / 2, paint)
            x += 5 * px
            i++
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
