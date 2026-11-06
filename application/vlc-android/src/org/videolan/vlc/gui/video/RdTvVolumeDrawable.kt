/*
 * VLC Redesign: the volume of the design's control bar (Figma "volume" 244:184, the desktop's VolumeWidget) for the
 * TV layout at the design's size, as the Samsung TV app draws it (player.js volume, player.css .pl-vol): the
 * speaker (24) and, 8 after it, the wedge of 89 x 27.4 (#090909) holding the gradient fill (#95D318 → #B2A918 →
 * #D37918 → #FF3737) clipped to the level, with a 20 % shade. The fill follows the design's steps (0 to 200 %; the
 * device's full volume is the design's 100 %, the player's boost goes on to 200 %).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import org.videolan.vlc.R

class RdTvVolumeDrawable(private val context: Context) : Drawable() {

    /** The level in the design's percent (0 to 200) and the mute */
    var percent = 100
        set(value) { if (field != value) { field = value; invalidateSelf() } }
    var muted = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    private val density = context.resources.displayMetrics.density
    private val bg = PathParser.createPathFromPathData("M3.59756 19.0454C1.55468 18.8388 0 17.119 0 15.0657V12.2975C0 10.2442 1.55467 8.52441 3.59756 8.31783L85.6982 0.0155207C87.4654 -0.163183 89 1.2241 89 3.0003V24.363C89 26.1392 87.4654 27.5264 85.6982 27.3477L3.59756 19.0454Z")
    private val fill = PathParser.createPathFromPathData("M1.79183 15.9675C0.773415 15.8609 0 15.0023 0 13.9783V10.5816C0 9.55761 0.773416 8.69903 1.79183 8.59245L83.7918 0.0110748C84.9723 -0.112463 86 0.813294 86 2.00021V22.5597C86 23.7467 84.9723 24.6724 83.7918 24.5489L1.79183 15.9675Z")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gradient = LinearGradient(-2.5f, 12.28f, 87.5f, 12.28f,
            intArrayOf(0xFF95D318.toInt(), 0xFFB2A918.toInt(), 0xFFD37918.toInt(), 0xFFFF3737.toInt()),
            floatArrayOf(0f, 0.578809f, 0.792379f, 1f), Shader.TileMode.CLAMP)

    override fun getIntrinsicWidth() = (120 * density).toInt()
    override fun getIntrinsicHeight() = (28 * density).toInt()

    private fun icon(): Drawable? {
        val res = when {
            muted || percent <= 0 -> R.drawable.rd_ic_volume_muted
            percent < 25 -> R.drawable.rd_ic_volume_low
            percent < 50 -> R.drawable.rd_ic_volume_medium
            else -> R.drawable.rd_ic_volume_high
        }
        return ContextCompat.getDrawable(context, res)?.mutate()?.apply { colorFilter = PorterDuffColorFilter(0xFF8F8F8F.toInt(), PorterDuff.Mode.SRC_IN) }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top + (b.height() - 28 * density) / 2f)
        icon()?.let { it.setBounds(0, (2 * density).toInt(), (24 * density).toInt(), (26 * density).toInt()); it.draw(canvas) }
        canvas.translate(32 * density, 0f)
        canvas.scale(density, density)
        // the well (muted: at half, as .pl-vol.muted)
        val alpha = if (muted) 128 else 255
        canvas.save()
        canvas.translate(-0.5f, 0.32f)
        paint.shader = null; paint.color = 0xFF090909.toInt(); paint.alpha = alpha
        canvas.drawPath(bg, paint)
        canvas.restore()
        val width = if (muted) 0f else fillOf(percent)
        if (width > 0f) {
            canvas.save()
            canvas.translate(1.5f, 1.72f)
            canvas.clipRect(0f, 0f, width, 24.56f)
            paint.shader = gradient; paint.alpha = 255
            canvas.drawPath(fill, paint)
            paint.shader = null; paint.color = 0x33000000
            canvas.drawPath(fill, paint)
            canvas.restore()
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** The design's fill (px of 86) of each level, 0 to 200 % (player.js FILL) */
        private val FILL = arrayOf(0 to 0, 10 to 5, 20 to 12, 30 to 18, 40 to 24, 50 to 31, 60 to 39, 70 to 46, 80 to 51,
                90 to 56, 100 to 61, 150 to 73, 200 to 85)

        fun fillOf(percent: Int): Float {
            if (percent <= 0) return 0f
            for (i in 1 until FILL.size) if (percent <= FILL[i].first) {
                val a = FILL[i - 1]; val b = FILL[i]
                return a.second + (b.second - a.second) * (percent - a.first).toFloat() / (b.first - a.first)
            }
            return 86f
        }
    }
}
