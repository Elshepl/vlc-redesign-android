/*
 * VLC Redesign: the subtitle sync timeline (SubtitleSyncTimeline.qml of the desktop, drawn as a timeline).
 *
 * Twenty seconds of the video around the playing time: the loudness of its sound as bars (the part already
 * played in the accent, as the waveform seek bar of the music player), under them the subtitle lines as blocks
 * where the subtitle delay puts them, the playhead in the middle and a mark every second. Dragging the lines
 * left or right moves them (the delay, in steps of 50 ms); the arrow keys move them by 50 ms. Time runs from
 * left to right in a right to left language too, as on the seek bar.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.videolan.vlc.R
import org.videolan.vlc.util.SubtitleCues
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

class SubtitleSyncView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** The span shown, the playing time in its middle */
    var windowMs = 20_000L

    /** The playing time (ms) */
    var time = 0L
        set(value) { field = value; invalidate() }

    /** The subtitle delay (ms): a line shows [delay] later than its file says */
    var delay = 0L
        set(value) { field = value; invalidate() }

    /** The lines of the subtitle track shown, in their file's times */
    var cues: List<SubtitleCues.Cue> = emptyList()
        set(value) { field = value; invalidate() }

    /** The loudness of every [levelsBinMs] from [levelsStart] (ms), null while it is read or when it cannot be */
    var levels: FloatArray? = null
        set(value) { field = value; levelsPeak = value?.maxOrNull()?.coerceAtLeast(0.05f) ?: 1f; invalidate() }
    var levelsStart = 0L
    var levelsBinMs = 50
    private var levelsPeak = 1f

    /** Called with the new delay while the lines are dragged ([done]: let go) */
    var onDelayChanged: ((delay: Long, done: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = ResourcesCompat.getFont(context, R.font.inter)
    }
    private val rect = RectF()
    // (the accent of the sheet's theme: the player's sheets have no rd_accent_bright)
    private val accent = org.videolan.vlc.gui.helpers.UiTools.getColorFromAttribute(context, androidx.appcompat.R.attr.colorPrimary)
    private val well = ContextCompat.getColor(context, R.color.rd_dark_bg_b2)
    private val unplayed = 0xFF404040.toInt()
    private val tick = ContextCompat.getColor(context, R.color.rd_dark_divider_bright)
    private val muted = ContextCompat.getColor(context, R.color.rd_dark_fg_a3)
    private val main = ContextCompat.getColor(context, R.color.rd_dark_fg_1)

    init {
        isFocusable = true
        isClickable = true
        // the timeline is a picture of time: the same in both directions of text
        layoutDirection = LAYOUT_DIRECTION_LTR
    }

    private fun px(dp: Float) = dp * density

    private fun pxPerMs() = width / windowMs.toFloat()

    private fun xOf(t: Long) = width / 2f + (t - time) * pxPerMs()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize(px(132f).toInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = well
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, px(6f), px(6f), paint)
        val waveTop = px(8f)
        val waveBottom = h * 0.52f
        val laneTop = waveBottom + px(8f)
        val laneBottom = h - px(20f)
        val start = time - windowMs / 2
        val end = time + windowMs / 2

        // a mark every second, a longer one and the time every five
        paint.strokeWidth = px(1f)
        var s = (start / 1000) * 1000
        while (s <= end) {
            if (s >= 0) {
                val x = xOf(s)
                val major = s % 5000 == 0L
                paint.color = tick
                canvas.drawLine(x, laneBottom + px(2f), x, laneBottom + px(if (major) 7f else 4f), paint)
                if (major) {
                    textPaint.color = muted
                    val label = timeLabel(s)
                    canvas.drawText(label, x - textPaint.measureText(label) / 2, h - px(4f), textPaint)
                }
            }
            s += 1000
        }

        // the sound: a bar every 4 dp, the part played in the accent
        val lv = levels
        val cy = (waveTop + waveBottom) / 2
        val half = (waveBottom - waveTop) / 2
        val pitch = px(4f)
        val barWidth = px(2f)
        var x = pitch / 2
        while (x < w) {
            val t = start + ((x / w) * windowMs).toLong()
            var v = 0f
            if (lv != null) {
                val bin = ((t - levelsStart) / levelsBinMs).toInt()
                // the loudest bin under the bar
                val span = max(1, (pitch / pxPerMs() / levelsBinMs).toInt())
                for (b in bin until bin + span) if (b in lv.indices) v = max(v, lv[b])
                v /= levelsPeak
            }
            val bh = max(barWidth, v * half * 2)
            paint.color = if (t <= time) accent else unplayed
            paint.alpha = if (t <= time) 204 else 255
            rect.set(x - barWidth / 2, cy - bh / 2, x + barWidth / 2, cy + bh / 2)
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
            x += pitch
        }
        paint.alpha = 255

        // the subtitle lines where the delay puts them
        for (cue in cues) {
            val cs = cue.start + delay
            val ce = cue.end + delay
            if (ce < start || cs > end) continue
            val left = max(0f, xOf(cs))
            val right = minOf(w, xOf(ce)).coerceAtLeast(left + px(3f))
            val current = time in cs..ce
            rect.set(left, laneTop, right, laneBottom)
            paint.style = Paint.Style.FILL
            paint.color = accent
            paint.alpha = if (current) 140 else 70
            canvas.drawRoundRect(rect, px(4f), px(4f), paint)
            paint.style = Paint.Style.STROKE
            paint.alpha = 255
            paint.strokeWidth = px(1f)
            canvas.drawRoundRect(rect, px(4f), px(4f), paint)
            paint.style = Paint.Style.FILL
            val room = right - left - px(8f)
            if (room > px(24f)) {
                textPaint.color = main
                val line = TextUtils.ellipsize(cue.text.replace('\n', ' '), textPaint, room, TextUtils.TruncateAt.END)
                canvas.drawText(line, 0, line.length, left + px(4f), (laneTop + laneBottom) / 2 - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            }
        }

        // the playhead
        paint.color = main
        paint.strokeWidth = px(2f)
        canvas.drawLine(w / 2, px(2f), w / 2, laneBottom + px(2f), paint)
        paint.strokeWidth = px(1f)
    }

    private fun timeLabel(ms: Long): String {
        val total = ms / 1000
        return if (total >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
        else String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60)
    }

    private var downX = 0f
    private var downDelay = 0L
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downDelay = delay
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > px(4f)) dragging = true
                if (dragging) {
                    val next = step(downDelay + ((event.x - downX) / pxPerMs()).roundToLong())
                    if (next != delay) {
                        delay = next
                        onDelayChanged?.invoke(next, false)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) onDelayChanged?.invoke(delay, true)
                dragging = false
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val d = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -STEP_MS
            KeyEvent.KEYCODE_DPAD_RIGHT -> STEP_MS
            else -> return super.onKeyDown(keyCode, event)
        }
        delay = step(delay + d)
        onDelayChanged?.invoke(delay, true)
        return true
    }

    companion object {
        const val STEP_MS = 50L
        const val MAX_MS = 10_000L

        /** In steps of 50 ms, at most 10 s either way (the delay slider's range) */
        fun step(ms: Long) = ((ms.toDouble() / STEP_MS).roundToLong() * STEP_MS).coerceIn(-MAX_MS, MAX_MS)
    }
}
