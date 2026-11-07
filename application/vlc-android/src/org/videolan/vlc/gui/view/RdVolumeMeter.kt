/*
 * VLC Redesign: the player's volume meter, the indicator of the volume keys, the swipe and the mouse wheel. The
 * design's wedge (Figma "volume" 244:184, the desktop's and the TV app's volume slider: narrow where it is quiet,
 * wide where it is loud, green to red) with the volume icon and the value, on a small dark card over the video.
 * Upright on a side of the video, lying in a top corner (Settings > Controls > Volume indicator); lying, it
 * mirrors in a right-to-left language. It is never focused and never takes a key or a touch.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.PathParser
import org.videolan.vlc.R
import java.text.NumberFormat
import kotlin.math.roundToInt

class RdVolumeMeter @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Upright (on a side of the video) or lying (in a top corner). */
    var upright = true
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }

    private var volume = 0
    private var max = 100

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CARD }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
        textAlign = Paint.Align.CENTER
    }
    // the design's wedge (89 x 27.36) and its fill (86 x 24.56, 1.5 / 1.72 inside it)
    private val wedge = PathParser.createPathFromPathData(WEDGE)
    private val wedgeFill = PathParser.createPathFromPathData(WEDGE_FILL)
    private val path = Path()
    private val matrix = Matrix()
    private val clip = RectF()
    private var icon: Drawable? = null
    private var iconRes = 0
    private var label = ""

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** [value] percent of the volume, out of [maximum] (200 with the audio boost). */
    fun set(value: Int, maximum: Int) {
        volume = value.coerceIn(0, maximum)
        max = maximum.coerceAtLeast(1)
        label = NumberFormat.getPercentInstance(org.videolan.vlc.util.RdCompat.primaryLocale(resources.configuration)).format(volume / 100.0)
        val res = when {
            volume <= 0 -> R.drawable.rd_ic_volume_muted
            volume * 100 / max < 25 -> R.drawable.rd_ic_volume_low
            volume * 100 / max < 50 -> R.drawable.rd_ic_volume_medium
            else -> R.drawable.rd_ic_volume_high
        }
        if (res != iconRes) {
            iconRes = res
            icon = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(TEXT) }
        }
        contentDescription = context.getString(R.string.volume) + " " + label
        invalidate()
    }

    // the wedge 1.5 times the design's size: read over a video, at arm's length or from the sofa
    private val wedgeLong get() = dp(89f * 1.5f)
    private val wedgeWide get() = dp(27.3633f * 1.5f)
    private val pad get() = dp(12f)
    private val gap get() = dp(10f)
    private val iconSize get() = dp(24f)
    private val labelWidth get() = text.measureText(NumberFormat.getPercentInstance(org.videolan.vlc.util.RdCompat.primaryLocale(resources.configuration)).format(if (max > 100) 2.0 else 1.0))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val lineH = text.fontSpacing
        val w: Float
        val h: Float
        if (upright) {
            w = pad * 2 + maxOf(wedgeWide, labelWidth, iconSize)
            h = pad * 2 + lineH + gap + wedgeLong + gap + iconSize
        } else {
            w = pad * 2 + iconSize + gap + wedgeLong + gap + labelWidth
            h = pad * 2 + maxOf(wedgeWide, lineH, iconSize)
        }
        setMeasuredDimension(w.roundToInt(), h.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, dp(8f), dp(8f), card)
        val frac = volume.toFloat() / max
        val baseline = { centreY: Float -> centreY - (text.descent() + text.ascent()) / 2 }
        if (upright) {
            // the value at the top, the wedge upright (quiet at the bottom), the icon under it
            val cx = w / 2
            canvas.drawText(label, cx, baseline(pad + text.fontSpacing / 2), text)
            val top = pad + text.fontSpacing + gap
            drawWedge(canvas, cx - wedgeWide / 2, top, frac, upright = true, mirrored = false)
            icon?.let { it.setBounds((cx - iconSize / 2).toInt(), (top + wedgeLong + gap).toInt(), (cx + iconSize / 2).toInt(), (top + wedgeLong + gap + iconSize).toInt()); it.draw(canvas) }
        } else {
            // the icon, the wedge lying (quiet at the start), the value; from the right in a right-to-left language
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            val cy = h / 2
            val iconL = if (rtl) w - pad - iconSize else pad
            icon?.let { it.setBounds(iconL.toInt(), (cy - iconSize / 2).toInt(), (iconL + iconSize).toInt(), (cy + iconSize / 2).toInt()); it.draw(canvas) }
            val wedgeL = if (rtl) w - pad - iconSize - gap - wedgeLong else pad + iconSize + gap
            drawWedge(canvas, wedgeL, cy - wedgeWide / 2, frac, upright = false, mirrored = rtl)
            val labelCx = if (rtl) pad + labelWidth / 2 else w - pad - labelWidth / 2
            canvas.drawText(label, labelCx, baseline(cy), text)
        }
    }

    /** The wedge at (x, y), its fill [frac] of the way from its quiet end, the design's green to red. */
    private fun drawWedge(canvas: Canvas, x: Float, y: Float, frac: Float, upright: Boolean, mirrored: Boolean) {
        val k = wedgeLong / dp(89f) * density
        canvas.save()
        canvas.translate(x, y)
        // the design draws it lying, quiet at the left: turned up (quiet at the bottom), or mirrored
        if (upright) { canvas.translate(0f, wedgeLong); canvas.rotate(-90f) }
        else if (mirrored) { canvas.translate(wedgeLong, 0f); canvas.scale(-1f, 1f) }
        matrix.setScale(k, k)
        wedge.transform(matrix, path)
        canvas.drawPath(path, track)
        if (frac > 0f) {
            matrix.setScale(k, k)
            matrix.postTranslate(1.5f * k, 1.72f * k)
            wedgeFill.transform(matrix, path)
            if (fill.shader == null || shaderK != k) {
                shaderK = k
                fill.shader = LinearGradient(1.5f * k, 0f, 87.5f * k, 0f, STOPS, POSITIONS, Shader.TileMode.CLAMP)
            }
            canvas.save()
            clip.set(0f, 0f, (1.5f + 86f * frac) * k, 27.3633f * k)
            canvas.clipRect(clip)
            canvas.drawPath(path, fill)
            canvas.drawPath(path, shade)
            canvas.restore()
        }
        canvas.restore()
    }

    private var shaderK = 0f

    private companion object {
        const val CARD = 0xE6161616.toInt()
        const val TRACK = 0xFF090909.toInt()
        const val TEXT = 0xFFC6C6C6.toInt()
        // the design's gradient (the desktop's and the TV app's volume slider)
        val STOPS = intArrayOf(0xFF95D318.toInt(), 0xFFB2A918.toInt(), 0xFFD37918.toInt(), 0xFFFF3737.toInt())
        val POSITIONS = floatArrayOf(0f, 0.578809f, 0.792379f, 1f)
        const val WEDGE = "M3.59756 19.0454C1.55468 18.8388 0 17.119 0 15.0657V12.2975C0 10.2442 1.55467 8.52441 3.59756 8.31783L85.6982 0.0155207C87.4654 -0.163183 89 1.2241 89 3.0003V24.363C89 26.1392 87.4654 27.5264 85.6982 27.3477L3.59756 19.0454Z"
        const val WEDGE_FILL = "M1.79183 15.9675C0.773415 15.8609 0 15.0023 0 13.9783V10.5816C0 9.55761 0.773416 8.69903 1.79183 8.59245L83.7918 0.0110748C84.9723 -0.112463 86 0.813294 86 2.00021V22.5597C86 23.7467 84.9723 24.6724 83.7918 24.5489L1.79183 15.9675Z"
    }
}
