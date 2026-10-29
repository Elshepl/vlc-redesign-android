/*
 * VLC Redesign, Live TV: the pieces of the desktop's TV views (TvBadge, TvLogo, the segmented choices, the chips,
 * the buttons and text wells of the redesign) built in code for the TV page, the guide, the sources and the
 * banner over the video.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.livetv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import org.videolan.vlc.R
import org.videolan.vlc.livetv.TvLogos
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

internal fun Context.tvDp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
internal fun Context.tvPx(v: Float) = tvDp(v).toInt()
internal fun Context.tvColor(res: Int) = ContextCompat.getColor(this, res)
internal fun Context.tvAttr(attr: Int): Int {
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) return 0
    return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
}
internal val Context.tvAccent get() = tvAttr(androidx.appcompat.R.attr.colorPrimary)
internal val Context.inter: Typeface get() = ResourcesCompat.getFont(this, R.font.inter) ?: Typeface.DEFAULT
internal val Context.interMedium: Typeface get() = ResourcesCompat.getFont(this, R.font.inter_medium) ?: Typeface.DEFAULT
internal val Context.isRtl get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

internal fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

internal fun Context.tvText(text: CharSequence = "", sp: Float = 14f, color: Int = tvColor(R.color.rd_fg_a1), medium: Boolean = false,
                            lines: Int = 1) = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    setTextColor(color)
    typeface = if (medium) interMedium else inter
    includeFontPadding = false
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
}

/** A button of the design (RedesignButton.qml): accent, or neutral in the window border. */
internal fun Context.tvButton(text: CharSequence, accent: Boolean = false, onClick: (View) -> Unit): MaterialButton {
    val b = LayoutInflater.from(this).inflate(if (accent) R.layout.rd_tv_button_accent else R.layout.rd_tv_button, null, false) as MaterialButton
    b.text = text
    b.setOnClickListener(onClick)
    return b
}

/** An icon button of the design: the glyph alone, the neutral rounded pressed state. */
internal fun Context.tvIconButton(icon: Int, description: CharSequence, tint: Int = tvColor(R.color.rd_fg_a2), onClick: (View) -> Unit) = ImageView(this).apply {
    setImageResource(icon)
    imageTintList = ColorStateList.valueOf(tint)
    contentDescription = description
    scaleType = ImageView.ScaleType.CENTER
    isClickable = true
    isFocusable = true
    background = RippleDrawable(ColorStateList.valueOf(tvColor(R.color.rd_ripple_dark)), null, rounded(Color.WHITE, tvDp(6f)))
    setOnClickListener(onClick)
}

/** A text well of the design (RedesignTextArea.qml). */
internal fun Context.tvField(hint: CharSequence, password: Boolean = false, multiLine: Boolean = false) = EditText(this).apply {
    this.hint = hint
    background = ContextCompat.getDrawable(this@tvField, R.drawable.rd_text_well)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    typeface = inter
    setTextColor(tvColor(R.color.rd_fg_a1))
    setHintTextColor(tvColor(R.color.rd_fg_a4))
    setPadding(tvPx(12f), tvPx(9f), tvPx(12f), tvPx(9f))
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    inputType = when {
        password -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        multiLine -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_VARIATION_URI
        else -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    if (!multiLine) isSingleLine = true else { minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START }
}

/**
 * The segmented choices of the design (SettingsSegmented.qml): a B2 well, 3 inside, the segments radius 3, the
 * chosen one on the accent's background in the accent.
 */
internal class TvSegmented(context: Context, private val onChosen: (String) -> Unit) : LinearLayout(context) {
    private var options: List<Pair<String, String>> = emptyList()
    var value = ""
        set(v) { field = v; refresh() }

    init {
        orientation = HORIZONTAL
        background = ContextCompat.getDrawable(context, R.drawable.rd_pref_field_bg)
        val p = context.tvPx(3f)
        setPadding(p, p, p, p)
    }

    fun setOptions(list: List<Pair<String, String>>) {
        if (list == options) return
        options = list
        removeAllViews()
        list.forEachIndexed { i, (label, v) ->
            val seg = context.tvText(label, 14f).apply {
                gravity = Gravity.CENTER
                minWidth = context.tvPx(64f)
                setPadding(context.tvPx(12f), 0, context.tvPx(12f), 0)
                isFocusable = true
                isClickable = true
                contentDescription = label
                setOnClickListener { if (value != v) { value = v; onChosen(v) } }
            }
            addView(seg, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { if (i > 0) marginStart = context.tvPx(3f) })
        }
        refresh()
    }

    private fun refresh() {
        for (i in 0 until childCount) {
            val seg = getChildAt(i) as TextView
            val chosen = options.getOrNull(i)?.second == value
            seg.isSelected = chosen
            seg.setTextColor(if (chosen) context.tvAccent else context.tvColor(R.color.rd_fg_a3))
            seg.background = rounded(if (chosen) context.tvAttr(R.attr.rd_accent_bright) else context.tvAttr(R.attr.rd_panel), context.tvDp(3f))
        }
    }
}

/** A chip of the groups (the segmented controls' look): selected on the accent's background in the accent. */
internal fun Context.tvChip(label: CharSequence, selected: Boolean, onClick: () -> Unit) = tvText(label, 13f).apply {
    gravity = Gravity.CENTER
    setPadding(tvPx(12f), 0, tvPx(12f), 0)
    isFocusable = true
    isClickable = true
    setTextColor(if (selected) tvAccent else tvColor(R.color.rd_fg_a2))
    background = RippleDrawable(ColorStateList.valueOf(tvColor(R.color.rd_ripple_dark)),
        rounded(if (selected) tvAttr(R.attr.rd_accent_bright) else tvColor(R.color.rd_bg_a4), tvDp(6f)), null)
    isSelected = selected
    setOnClickListener { onClick() }
}

/** TvBadge.qml: a small mark (the archive, the guide, films, free): an icon and a word on the controls' grey. */
internal fun Context.tvBadge(text: CharSequence, icon: Int = 0, accent: Boolean = false, dark: Boolean = false): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(tvPx(6f), 0, tvPx(6f), 0)
        minimumHeight = tvPx(20f)
        background = rounded(if (accent) tvAttr(R.attr.rd_accent_bright) else if (dark) 0xFF191919.toInt() else tvColor(R.color.rd_bg_a4), tvDp(4f))
    }
    val color = if (accent) tvAccent else if (dark) 0xFF8F8F8F.toInt() else tvColor(R.color.rd_fg_a2)
    if (icon != 0) row.addView(ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color)
    }, LinearLayout.LayoutParams(tvPx(14f), tvPx(14f)).apply { marginEnd = tvPx(4f) })
    row.addView(tvText(text, 11f, color))
    return row
}

/** TvLogo.qml: the logo on the thumbnail well, the initials until it comes. */
internal class TvLogoView(context: Context, private val sizeDp: Float, private val dark: Boolean = false) : FrameLayout(context) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val initials = context.tvText("", sizeDp / 3.2f, if (dark) 0xFF6B6B6B.toInt() else context.tvColor(R.color.rd_fg_a3), medium = true).apply { gravity = Gravity.CENTER }
    var wellVisible = true
        set(v) { field = v; background = if (v) rounded(if (dark) 0xFF171717.toInt() else context.tvColor(R.color.rd_tv_logo_well), context.tvDp(6f)) else null }

    init {
        wellVisible = true
        val pad = context.tvPx(sizeDp / 10f)
        addView(initials, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(pad, pad, pad, pad) })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun set(url: String, name: String) {
        initials.text = TvLogos.initials(name)
        initials.visibility = VISIBLE
        TvLogos.load(context, url, image, context.tvPx(sizeDp * 2)) { ok -> initials.visibility = if (ok) INVISIBLE else VISIBLE }
    }
}

/** The progress of what is on: a time axis, from the left in every language (as the seek bar). */
internal class TvProgress(context: Context, private val track: Int, private val fill: Int) : View(context) {
    var fraction = 0f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    init { layoutDirection = LAYOUT_DIRECTION_LTR }
    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = height / 2f
        paint.color = track
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        paint.color = fill
        canvas.drawRoundRect(0f, 0f, width * fraction, height.toFloat(), r, r, paint)
    }
}

/**
 * A row that wraps (the desktop's Flow): badges and buttons go on to a new line instead of being cut at the edge
 * of a narrow window or a long translation. Lines follow the layout direction; [alignEnd] puts them at the end.
 */
internal class TvFlow(context: Context, private val hGap: Int, private val vGap: Int, private val alignEnd: Boolean = false) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val childW = if (unbounded) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) else MeasureSpec.makeMeasureSpec(maxOf(0, maxW), MeasureSpec.AT_MOST)
        var lineW = 0
        var lineH = 0
        var totalH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(childW, getChildMeasureSpec(heightMeasureSpec, 0, c.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT))
            if (lineW > 0 && !unbounded && lineW + hGap + c.measuredWidth > maxW) {
                totalH += lineH + vGap
                widest = maxOf(widest, lineW)
                lineW = c.measuredWidth
                lineH = c.measuredHeight
            } else {
                lineW += (if (lineW > 0) hGap else 0) + c.measuredWidth
                lineH = maxOf(lineH, c.measuredHeight)
            }
        }
        totalH += lineH
        widest = maxOf(widest, lineW)
        setMeasuredDimension(resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(totalH + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = width - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val lines = mutableListOf<MutableList<View>>(mutableListOf())
        var lineW = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (lineW > 0 && lineW + hGap + c.measuredWidth > maxW) {
                lines.add(mutableListOf())
                lineW = 0
            }
            lineW += (if (lineW > 0) hGap else 0) + c.measuredWidth
            lines.last().add(c)
        }
        var y = paddingTop
        for (line in lines) {
            if (line.isEmpty()) continue
            val w = line.sumOf { it.measuredWidth } + hGap * (line.size - 1)
            val h = line.maxOf { it.measuredHeight }
            // the start of the line, from the left: the start side, or the end side when aligned to the end
            val fromStart = if (alignEnd) maxOf(0, maxW - w) else 0
            var x = 0
            for (c in line) {
                val left = if (rtl) paddingLeft + maxW - fromStart - x - c.measuredWidth else paddingLeft + fromStart + x
                val top = y + (h - c.measuredHeight) / 2
                c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
                x += c.measuredWidth + hGap
            }
            y += h + vGap
        }
    }
}

internal object TvTexts {
    /** the language of the app (it can differ from the phone's), as the desktop's QLocale().toString(time, ShortFormat) */
    private fun locale(context: Context) = context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()

    fun time(context: Context, secs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT, locale(context)).format(Date(secs * 1000))

    fun duration(context: Context, secs: Long): String {
        val minutes = (maxOf(0L, secs) + 59) / 60
        if (minutes < 60) return context.resources.getQuantityString(R.plurals.rd_tv_min, minutes.toInt(), minutes.toInt())
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) context.resources.getQuantityString(R.plurals.rd_tv_h, hours.toInt(), hours.toInt())
        else context.getString(R.string.rd_tv_h_min, hours.toString(), rest.toString())
    }

    fun day(context: Context, secs: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = secs * 1000 }
        val today = Calendar.getInstance()
        val diff = ((Calendar.getInstance().apply { set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), 0, 0, 0) }.timeInMillis -
                Calendar.getInstance().apply { set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH), 0, 0, 0) }.timeInMillis) / 86_400_000.0).let { Math.round(it) }
        return when (diff) {
            0L -> context.getString(R.string.rd_tv_today)
            1L -> context.getString(R.string.rd_tv_tomorrow)
            -1L -> context.getString(R.string.rd_tv_yesterday)
            else -> android.text.format.DateFormat.format(android.text.format.DateFormat.getBestDateTimePattern(locale(context), "EEE d MMM"), c).toString()
        }
    }

    fun span(context: Context, start: Long, stop: Long) = time(context, start) + " – " + time(context, stop)
}
