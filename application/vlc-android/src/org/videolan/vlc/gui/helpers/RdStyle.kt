/*
 * VLC Redesign: the desktop's controls for the views the app inflates from its own and Material's classes
 * (check boxes and switches as the desktop's ON / OFF toggle, the library search well, choice chips as the
 * desktop's segmented choices).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.helpers

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.widget.CompoundButtonCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.theme.MaterialComponentsViewInflater
import org.videolan.vlc.R

private fun Context.rdColor(attr: Int): Int {
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) return 0
    return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
}

private fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

/**
 * The ON / OFF toggle of the design (SettingsToggle.qml): a 68 x 32 well holding a 33 x 26 label and a 26 x 26
 * square, swapped by the state (ON: the label on the start side in the accent's background, the square in the
 * dim accent). Drawn from the state of the view it is on (checked, enabled), mirrored right to left.
 */
class RdToggleDrawable(private val context: Context) : Drawable() {
    private var on = false
    private var enabled = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dp(12f)
        typeface = ResourcesCompat.getFont(context, R.font.inter) ?: Typeface.DEFAULT
    }
    private val onWord = context.getString(R.string.rd_toggle_on)
    private val offWord = context.getString(R.string.rd_toggle_off)
    val labelWidth = maxOf(context.dp(33f), Math.ceil(maxOf(text.measureText(onWord), text.measureText(offWord)).toDouble()).toFloat() + context.dp(8f))
    private val rect = RectF()

    override fun getIntrinsicWidth() = (context.dp(68f) + labelWidth - context.dp(33f)).toInt()
    override fun getIntrinsicHeight() = context.dp(32f).toInt()
    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val c = state.contains(android.R.attr.state_checked)
        val e = state.contains(android.R.attr.state_enabled)
        if (c == on && e == enabled) return false
        on = c; enabled = e
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val checked = on
        val d = { v: Float -> context.dp(v) }
        val w = intrinsicWidth.toFloat()
        val h = intrinsicHeight.toFloat()
        // centred in the bounds (a compound drawable gets its intrinsic size; a switch track the whole switch)
        val ox = b.left + (b.width() - w) / 2f
        val oy = b.top + (b.height() - h) / 2f
        val rtl = DrawableCompat.getLayoutDirection(this) == View.LAYOUT_DIRECTION_RTL
        fun place(x: Float, width: Float) = ox + if (rtl) w - width - x else x
        val alpha = if (enabled) 255 else 110
        paint.color = context.rdColor(R.attr.rd_well); paint.alpha = alpha
        rect.set(ox, oy, ox + w, oy + h)
        canvas.drawRoundRect(rect, d(6f), d(6f), paint)
        val lx = place(if (checked) d(3f) else d(32f), labelWidth)
        paint.color = if (checked) context.rdColor(R.attr.rd_accent_bright) else context.rdColor(R.attr.rd_panel); paint.alpha = alpha
        rect.set(lx, oy + d(3f), lx + labelWidth, oy + d(29f))
        canvas.drawRoundRect(rect, d(3f), d(3f), paint)
        val word = if (checked) onWord else offWord
        text.color = if (checked) context.rdColor(androidx.appcompat.R.attr.colorPrimary) else ContextCompat.getColor(context, R.color.rd_fg_a3); text.alpha = alpha
        val tw = text.measureText(word)
        val fm = text.fontMetrics
        canvas.drawText(word, lx + (labelWidth - tw) / 2f, oy + d(16f) - (fm.ascent + fm.descent) / 2f, text)
        val sx = place(if (checked) d(39f) + labelWidth - d(33f) else d(3f), d(26f))
        paint.color = if (checked) context.rdColor(R.attr.rd_accent_dim) else ContextCompat.getColor(context, R.color.rd_fg_a4); paint.alpha = alpha
        rect.set(sx, oy + d(3f), sx + d(26f), oy + d(29f))
        canvas.drawRoundRect(rect, d(3f), d(3f), paint)
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean { invalidateSelf(); return true }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

object RdStyle {

    /** A check box as the desktop's toggle: the label on the start side, the toggle at the end. */
    fun toggle(box: CompoundButton) {
        if (box is SwitchCompat) return toggle(box)
        val ctx = box.context
        val drawable = RdToggleDrawable(ctx)
        box.buttonDrawable = null
        CompoundButtonCompat.setButtonTintList(box, null)
        if (box is MaterialCheckBox) box.isUseMaterialThemeColors = false
        box.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, drawable, null)
        box.compoundDrawablePadding = ctx.dp(12f).toInt()
        // the text box keeps its padding at the start (the button's room is gone)
        box.setPaddingRelative(0, box.paddingTop, box.paddingEnd, box.paddingBottom)
        box.typeface = ResourcesCompat.getFont(ctx, R.font.inter) ?: box.typeface
        box.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        box.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    /** A switch as the desktop's toggle: the track draws the whole toggle, the thumb is only its half's room. */
    fun toggle(switch: SwitchCompat) {
        val ctx = switch.context
        val track = RdToggleDrawable(ctx)
        val thumb = GradientDrawable().apply {
            setColor(0)
            setSize(track.intrinsicWidth / 2, track.intrinsicHeight)
        }
        switch.showText = false
        switch.thumbDrawable = thumb
        switch.trackDrawable = track
        switch.thumbTintList = null
        switch.trackTintList = null
        switch.switchMinWidth = track.intrinsicWidth
        // no round ripple around the half of the toggle (the desktop has none; the row is the touch target)
        switch.background = null
        switch.typeface = ResourcesCompat.getFont(ctx, R.font.inter) ?: switch.typeface
    }

    /** The library search of the toolbar as the desktop header's well (LibraryHeader.qml: 34 high, radius 6, A5). */
    fun searchView(sv: SearchView) {
        val ctx = sv.context
        sv.maxWidth = Int.MAX_VALUE
        sv.findViewById<View>(androidx.appcompat.R.id.search_plate)?.let { plate ->
            plate.background = ContextCompat.getDrawable(ctx, R.drawable.rd_search_well)
            (plate.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.height = ctx.dp(34f).toInt()
                it.gravity = Gravity.CENTER_VERTICAL
                plate.layoutParams = it
            }
            plate.setPaddingRelative(ctx.dp(4f).toInt(), 0, 0, 0)
        }
        sv.findViewById<View>(androidx.appcompat.R.id.submit_area)?.background = null
        sv.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.let {
            it.typeface = ResourcesCompat.getFont(ctx, R.font.inter) ?: it.typeface
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            it.setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_a2))
            it.setHintTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_a4))
            it.background = null
        }
        sv.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)?.setImageResource(R.drawable.rd_ic_close)
    }

    /** A choice chip as one of the desktop's segmented choices (SettingsSegmented.qml): flat, the chosen one raised. */
    fun choiceChip(chip: Chip) {
        val ctx = chip.context
        chip.chipBackgroundColor = ContextCompat.getColorStateList(ctx, R.color.rd_choice_chip_bg)
        chip.setTextColor(ContextCompat.getColorStateList(ctx, R.color.rd_pref_choice_text_checked))
        chip.chipStrokeWidth = 0f
        chip.chipCornerRadius = ctx.dp(4f)
        chip.isCheckedIconVisible = false
        chip.isChipIconVisible = false
        chip.chipMinHeight = ctx.dp(30f)
        chip.setEnsureMinTouchTargetSize(false)
        chip.rippleColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rd_ripple_dark))
        chip.typeface = ResourcesCompat.getFont(ctx, R.font.inter) ?: chip.typeface
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        chip.chipStartPadding = ctx.dp(6f)
        chip.chipEndPadding = ctx.dp(6f)
        chip.elevation = 0f
        chip.stateListAnimator = null
    }
}

/**
 * The app's view inflater (theme attribute viewInflaterClass): Material's, then every check box and switch
 * becomes the desktop's toggle.
 */
class RdViewInflater : MaterialComponentsViewInflater() {
    // a menu's checkable item keeps its check (the desktop's menus mark the chosen option with a check)
    override fun createCheckBox(context: Context, attrs: AttributeSet?): AppCompatCheckBox =
        super.createCheckBox(context, attrs).also { if (it.id != androidx.appcompat.R.id.checkbox) RdStyle.toggle(it) }

    override fun createView(context: Context, name: String, attrs: AttributeSet?): View? = when (name) {
        "androidx.appcompat.widget.SwitchCompat", "Switch" -> SwitchCompat(context, attrs).also { RdStyle.toggle(it) }
        "com.google.android.material.checkbox.MaterialCheckBox" -> MaterialCheckBox(context, attrs).also { RdStyle.toggle(it) }
        else -> super.createView(context, name, attrs)
    }
}
