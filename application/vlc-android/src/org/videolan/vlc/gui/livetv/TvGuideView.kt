/*
 * VLC Redesign, Live TV: the programme guide (TvGuideView.qml): the channels of the TV page (its group and
 * search) down, the time across with the line of now; the programmes as cells of the controls' grey, the one on
 * now brighter with its progress, the archived ones marked. Drawn on one canvas: only the rows and hours in
 * sight are drawn, so a guide of 20 000 channels scrolls as one of 20. The time goes from left to right in every
 * language, as the seek bar; the channels stay on the start side.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.livetv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import org.videolan.vlc.R
import org.videolan.vlc.livetv.Entry
import org.videolan.vlc.livetv.LiveTv
import org.videolan.vlc.livetv.TvChannel
import org.videolan.vlc.livetv.TvLogos
import org.videolan.vlc.livetv.TvPlayback
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ViewConstructor")
internal class TvGuideView(context: Context, private val onPlay: (TvChannel) -> Unit, private val onProgramme: (TvChannel, Entry) -> Unit) : View(context) {

    var channels: List<TvChannel> = emptyList()
        set(v) { field = v; focusRow = focusRow.coerceIn(0, max(0, v.size - 1)); clampScroll(); invalidate() }
    var hours = 3
        set(v) { val left = leftTime; field = v.coerceIn(1, 12); scrollToTime(left, false) }
    /** the day at the left of the grid changed (the header shows it) */
    var onTimeChanged: ((Long) -> Unit)? = null

    private val d = { v: Float -> context.tvDp(v) }
    private val rowHeight = d(56f)
    private val rowGap = d(2f)
    private val timelineHeight = d(28f)
    private val rtl = context.isRtl
    private val channelWidth get() = min(d(220f), max(d(120f), width * 0.34f))
    private val gridWidth get() = max(d(100f), width - channelWidth - d(8f))
    private val gridLeft get() = if (rtl) 0f else width - gridWidth
    /** the hours across: the setting's on a wide window, half of them on a phone held upright */
    private val shownHours get() = if (width > 0 && width < d(600f)) maxOf(1f, hours / 2f) else hours.toFloat()
    private val pxPerSec get() = gridWidth / (shownHours * 3600f)

    /** two days back (the archives), a week ahead */
    private val origin: Long = Calendar.getInstance().run {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        timeInMillis / 1000 - 2 * 86400
    }
    private val span = 9 * 86400L

    private var timeX = 0f      // the time scrolled, in px from the origin
    private var rowsY = 0f      // the rows scrolled
    private val leftTime get() = origin + (timeX / pxPerSec).toLong()
    private var focusRow = 0
    private var focusTime = 0L

    private val scroller = OverScroller(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = d(13f); typeface = context.inter }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = d(11f); typeface = context.inter }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = d(12f); typeface = context.inter }
    private val rect = RectF()
    private val archiveIcon = ContextCompat.getDrawable(context, R.drawable.rd_ic_recent)?.mutate()
    private val reminderIcon = ContextCompat.getDrawable(context, R.drawable.rd_ic_duo_recent)?.mutate()

    private val cFg1 = context.tvColor(R.color.rd_fg_1)
    private val cFgA1 = context.tvColor(R.color.rd_fg_a1)
    private val cFgA3 = context.tvColor(R.color.rd_fg_a3)
    private val cFgA4 = context.tvColor(R.color.rd_fg_a4)
    private val cDivider = context.tvColor(R.color.rd_divider_dim)
    private val cTick = context.tvColor(R.color.rd_divider_bright)
    private val cPast = context.tvColor(R.color.rd_tv_cell_past)
    private val cCell = context.tvColor(R.color.rd_tv_cell)
    private val cNow = context.tvColor(R.color.rd_tv_cell_now)
    private val cHover = context.tvColor(R.color.rd_bg_a1)
    private val cRowHover = context.tvColor(R.color.rd_bg_a5)
    private val cAccent = context.tvAccent
    private val cWell = context.tvColor(R.color.rd_tv_logo_well)

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        archiveIcon?.setTint(cFgA3)
        reminderIcon?.setTint(cAccent)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        private var axis = 0   // 0 undecided, 1 across, 2 down
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            axis = 0
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (axis == 0) axis = if (abs(dx) > abs(dy)) 1 else 2
            parent?.requestDisallowInterceptTouchEvent(true)
            if (axis == 1) timeX += dx else rowsY += dy
            clampScroll()
            invalidate()
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val maxX = ((span * pxPerSec) - gridWidth).toInt()
            val maxY = max(0f, contentHeight() - (height - timelineHeight)).toInt()
            if (axis == 1) scroller.fling(timeX.toInt(), rowsY.toInt(), -vx.toInt(), 0, 0, max(0, maxX), rowsY.toInt(), rowsY.toInt())
            else scroller.fling(timeX.toInt(), rowsY.toInt(), 0, -vy.toInt(), timeX.toInt(), timeX.toInt(), 0, maxY)
            postInvalidateOnAnimation()
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            hit(e.x, e.y, false)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            hit(e.x, e.y, true)
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            timeX = scroller.currX.toFloat()
            rowsY = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    private fun contentHeight() = channels.size * (rowHeight + rowGap)

    private fun clampScroll() {
        if (width == 0) return
        timeX = timeX.coerceIn(0f, max(0f, span * pxPerSec - gridWidth))
        rowsY = rowsY.coerceIn(0f, max(0f, contentHeight() - (height - timelineHeight)))
        onTimeChanged?.invoke(leftTime)
    }

    fun scrollToTime(time: Long, animate: Boolean) {
        val x = ((time - origin) * pxPerSec).coerceIn(0f, max(0f, span * pxPerSec - gridWidth))
        if (animate && width > 0) {
            scroller.forceFinished(true)
            scroller.startScroll(timeX.toInt(), rowsY.toInt(), (x - timeX).toInt(), 0, 250)
            postInvalidateOnAnimation()
        } else {
            timeX = x
            clampScroll()
            invalidate()
        }
    }

    fun jumpToNow() = scrollToTime(now() - (shownHours * 900).toLong(), true)
    fun earlier() = scrollToTime(leftTime - (shownHours * 1800).toLong(), true)
    fun later() = scrollToTime(leftTime + (shownHours * 1800).toLong(), true)

    fun showChannel(key: String) {
        val i = channels.indexOfFirst { it.key == key }
        if (i < 0) return
        focusRow = i
        rowsY = i * (rowHeight + rowGap) - (height - timelineHeight - rowHeight) / 2
        clampScroll()
        invalidate()
    }

    private fun now() = System.currentTimeMillis() / 1000

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0) scrollToTime(now() - (shownHours * 900).toLong(), false) else clampScroll()
    }

    private fun xOf(t: Long) = gridLeft + (t - origin) * pxPerSec - timeX

    private fun hit(x: Float, y: Float, long: Boolean) {
        if (y < timelineHeight) return
        val row = ((y - timelineHeight + rowsY) / (rowHeight + rowGap)).toInt()
        val c = channels.getOrNull(row) ?: return
        focusRow = row
        val inGrid = x >= gridLeft && x <= gridLeft + gridWidth
        if (!inGrid) {
            onPlay(c)
            return
        }
        val t = origin + ((x - gridLeft + timeX) / pxPerSec).toLong()
        val e = c.between(t, t + 1).firstOrNull() ?: run { if (!long) onPlay(c); return }
        focusTime = e.start
        invalidate()
        val n = now()
        if (!long && e.start <= n && n < e.stop) onPlay(c) else onProgramme(c, e)
    }

    override fun onDraw(canvas: Canvas) {
        val n = now()
        val gl = gridLeft
        val gw = gridWidth
        val pps = pxPerSec
        // the hours, every half hour
        val labelWidth = label.measureText(TvTexts.time(context, origin + 12 * 3600 + 1800)) + d(14f)
        val step = when {
            1800 * pps >= labelWidth -> 1800L
            3600 * pps >= labelWidth -> 3600L
            else -> 7200L
        }
        val firstTick = ((leftTime - origin) / 1800) * 1800 + origin
        canvas.save()
        canvas.clipRect(gl, 0f, gl + gw, timelineHeight)
        var t = firstTick
        while (xOf(t) < gl + gw) {
            val x = xOf(t)
            paint.color = cTick
            canvas.drawRect(x, timelineHeight - d(8f), x + d(1f), timelineHeight, paint)
            if ((t - origin) % step == 0L) {
                label.color = cFgA3
                canvas.drawText(TvTexts.time(context, t), x + d(6f), timelineHeight - d(10f), label)
            }
            t += 1800
        }
        canvas.restore()
        // the line under the hours
        paint.color = cDivider
        canvas.drawRect(0f, timelineHeight - d(1f), width.toFloat(), timelineHeight, paint)

        val top = timelineHeight + d(6f)
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), height.toFloat())
        val first = max(0, ((rowsY) / (rowHeight + rowGap)).toInt())
        val last = min(channels.size - 1, ((rowsY + height) / (rowHeight + rowGap)).toInt())
        val from = leftTime
        val until = from + (gw / pps).toLong() + 60
        val current = TvPlayback.current.value
        for (i in first..last) {
            val c = channels[i]
            val y = top + i * (rowHeight + rowGap) - rowsY
            drawChannel(canvas, c, y, i == focusRow && isFocused, c.key == current)
            canvas.save()
            canvas.clipRect(gl, y, gl + gw, y + rowHeight)
            val list = c.between(from, until)
            if (list.isEmpty()) {
                label.color = cFgA4
                canvas.drawText(context.getString(R.string.rd_tv_no_guide_channel), gl + d(12f), y + rowHeight / 2 + d(4f), label)
            }
            for (e in list) drawCell(canvas, c, e, y, n, i == focusRow && isFocused && e.start <= focusTime && focusTime < e.stop)
            canvas.restore()
        }
        canvas.restore()
        // now: a line of the accent across the rows
        val nx = xOf(n)
        if (nx >= gl && nx <= gl + gw) {
            paint.color = cAccent
            canvas.drawRect(nx - d(1f), timelineHeight - d(6f), nx + d(1f), height.toFloat(), paint)
            canvas.drawCircle(nx, timelineHeight - d(6f), d(4f), paint)
        }
    }

    private fun drawChannel(canvas: Canvas, c: TvChannel, y: Float, focused: Boolean, current: Boolean) {
        val cw = channelWidth - d(8f)
        val left = if (rtl) width - cw else 0f
        if (focused || current) {
            paint.color = if (current) context.tvAttr(R.attr.rd_accent_bright) else cRowHover
            rect.set(left, y, left + cw, y + rowHeight)
            canvas.drawRoundRect(rect, d(6f), d(6f), paint)
        }
        // number, logo, name, in the order of the language
        val numW = if (cw < d(170f)) 0f else d(32f)
        val logoS = d(36f)
        fun at(x: Float, w: Float) = if (rtl) left + cw - x - w else left + x
        label.color = cFgA3
        label.textSize = d(13f)
        val num = if (c.number > 0 && numW > 0f) c.number.toString() else ""
        val nw = label.measureText(num)
        canvas.drawText(num, at(d(4f), numW) + if (rtl) 0f else numW - nw, y + rowHeight / 2 + d(4.5f), label)
        val lx = at(d(4f) + numW + if (numW > 0f) d(8f) else 0f, logoS)
        rect.set(lx, y + (rowHeight - logoS) / 2, lx + logoS, y + (rowHeight + logoS) / 2)
        paint.color = cWell
        canvas.drawRoundRect(rect, d(6f), d(6f), paint)
        val bmp: Bitmap? = TvLogos.cached(c.logo)
        if (bmp != null) {
            val pad = d(4f)
            val box = RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
            val s = min(box.width() / bmp.width, box.height() / bmp.height)
            val bw = bmp.width * s
            val bh = bmp.height * s
            val dst = Rect((box.centerX() - bw / 2).toInt(), (box.centerY() - bh / 2).toInt(), (box.centerX() + bw / 2).toInt(), (box.centerY() + bh / 2).toInt())
            canvas.drawBitmap(bmp, null, dst, paint)
        } else {
            if (c.logo.isNotEmpty()) TvLogos.get(context, c.logo, context.tvPx(72f)) { if (it != null) invalidate() }
            small.color = cFgA3
            val ini = TvLogos.initials(c.name)
            canvas.drawText(ini, rect.centerX() - small.measureText(ini) / 2, rect.centerY() + d(4f), small)
        }
        val nameX = d(4f) + numW + (if (numW > 0f) d(8f) else 0f) + logoS + d(10f)
        val nameW = cw - nameX - d(6f)
        title.color = if (current) cAccent else cFgA1
        title.textSize = d(13f)
        val text = TextUtils.ellipsize(c.name, title, max(0f, nameW), TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, at(nameX, nameW) + if (rtl) nameW - title.measureText(text) else 0f, y + rowHeight / 2 + d(4.5f), title)
    }

    private fun drawCell(canvas: Canvas, c: TvChannel, e: Entry, y: Float, n: Long, focused: Boolean) {
        val x0 = xOf(e.start)
        val w = max(d(4f), (e.stop - e.start) * pxPerSec - d(2f))
        val onNow = e.start <= n && n < e.stop
        val past = e.stop <= n
        rect.set(x0, y, x0 + w, y + rowHeight)
        paint.color = when {
            focused -> cHover
            onNow -> cNow
            past -> cPast
            else -> cCell
        }
        canvas.drawRoundRect(rect, d(6f), d(6f), paint)
        if (focused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = d(1f)
            paint.color = cAccent
            canvas.drawRoundRect(rect, d(6f), d(6f), paint)
            paint.style = Paint.Style.FILL
        }
        // the progress of the one on now (its start to now)
        if (onNow) {
            paint.color = cAccent
            canvas.drawRect(x0 + d(6f), y + rowHeight - d(4f), x0 + max(d(6f), (n - e.start) * pxPerSec), y + rowHeight - d(2f), paint)
        }
        if (w <= d(28f)) return
        // the text stays in sight while its start is left of the grid
        val inset = max(0f, gridLeft - x0)
        val tx = x0 + inset + d(10f)
        val tw = max(0f, x0 + w - tx - d(8f))
        title.color = if (onNow) cFg1 else if (past) cFgA3 else cFgA1
        val t = TextUtils.ellipsize(e.title, title, tw, TextUtils.TruncateAt.END).toString()
        canvas.drawText(t, tx, y + d(23f), title)
        small.color = cFgA3
        val time = TvTexts.time(context, e.start)
        canvas.drawText(TextUtils.ellipsize(time, small, tw, TextUtils.TruncateAt.END).toString(), tx, y + d(42f), small)
        var mx = tx + small.measureText(time) + d(6f)
        if (tw >= d(48f)) {
            val icon = when {
                past && LiveTv.isArchived(c, e, n) -> archiveIcon
                !past && LiveTv.hasReminder(c.key, e.start) -> reminderIcon
                else -> null
            }
            if (icon != null && mx + d(13f) < tx + tw) {
                icon.setBounds(mx.toInt(), (y + d(31f)).toInt(), (mx + d(13f)).toInt(), (y + d(44f)).toInt())
                icon.draw(canvas)
                mx += d(19f)
            }
        }
    }

    /* Keys: Up and Down the channels, Left and Right through the programmes, Enter plays or opens one */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val c = channels.getOrNull(focusRow)
        val n = now()
        if (focusTime == 0L) focusTime = n
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (focusRow == 0) return false
                focusRow--
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (focusRow < channels.size - 1) focusRow++ else return false
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // the time goes from left to right in every language
                val forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                val list = c?.between(focusTime - 86400, focusTime + 86400) ?: emptyList()
                val i = list.indexOfFirst { it.start <= focusTime && focusTime < it.stop }
                val target = if (i >= 0) list.getOrNull(if (forward) i + 1 else i - 1) else null
                focusTime = target?.start ?: (focusTime + if (forward) 1800 else -1800)
                val lt = leftTime
                val rt = lt + (shownHours * 3600).toLong()
                if (focusTime < lt || focusTime >= rt - 600) scrollToTime(focusTime - (shownHours * 900).toLong(), true)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                c ?: return false
                val e = c.between(focusTime, focusTime + 1).firstOrNull()
                if (e == null || (e.start <= n && n < e.stop)) onPlay(c) else onProgramme(c, e)
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> { jumpToNow(); focusTime = n }
            else -> return super.onKeyDown(keyCode, event)
        }
        // the row in sight
        val y = focusRow * (rowHeight + rowGap)
        if (y < rowsY) rowsY = y
        if (y + rowHeight > rowsY + height - timelineHeight - d(6f)) rowsY = y + rowHeight - (height - timelineHeight - d(6f))
        clampScroll()
        invalidate()
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus && focusTime == 0L) focusTime = now()
        invalidate()
    }
}
