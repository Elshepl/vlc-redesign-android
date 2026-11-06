/*
 * VLC Redesign: the controls of the desktop's settings rows (SettingsToggle.qml, SettingsDropdown.qml,
 * SettingsSegmented.qml, SettingsAppearance.qml, SettingsActionRow.qml) as preference widgets.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.preferences.rd

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Checkable
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import org.videolan.vlc.R

internal fun Context.rdAttr(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
}

internal fun Context.rdDp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

/**
 * The ON / OFF toggle of the design: a 68 x 32 well holding a 33 x 26 label and a 26 x 26 square, swapped by
 * the state (ON: the label on the start side in the accent's background, the square in the dim accent).
 * It is the CheckBoxPreference's checkbox (android:id/checkbox), so every check box of the settings uses it.
 */
class RdToggleView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), Checkable {
    private var on = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.rdDp(12f)
        typeface = ResourcesCompat.getFont(context, R.font.inter) ?: Typeface.DEFAULT
    }
    private val onWord = context.getString(R.string.rd_toggle_on)
    private val offWord = context.getString(R.string.rd_toggle_off)
    private val labelWidth = maxOf(context.rdDp(33f), Math.ceil(maxOf(text.measureText(onWord), text.measureText(offWord)).toDouble()).toFloat() + context.rdDp(8f))
    private val rect = RectF()

    // The row (the preference's item, which TalkBack reads and clicks) said nothing of the state: it is a
    // switch with the state of this one, ON or OFF
    private var row: View? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        row = generateSequence(parent as? View) { it.parent as? View }.firstOrNull { it.isClickable }
        row?.let { r ->
            ViewCompat.setAccessibilityDelegate(r, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = on
                }
            })
        }
        describe()
    }

    private fun describe() {
        row?.let { ViewCompat.setStateDescription(it, if (on) onWord else offWord) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((context.rdDp(68f) + labelWidth - context.rdDp(33f)).toInt(), context.rdDp(32f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val c = context
        val d = { v: Float -> c.rdDp(v) }
        val rtl = ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL
        val w = width.toFloat()
        fun place(x: Float, width: Float) = if (rtl) w - width - x else x
        val alpha = if (isEnabled) 255 else 110
        paint.color = c.rdAttr(R.attr.rd_well); paint.alpha = alpha
        rect.set(0f, 0f, w, height.toFloat())
        canvas.drawRoundRect(rect, d(6f), d(6f), paint)
        // the label
        val lx = place(if (on) d(3f) else d(32f), labelWidth)
        paint.color = if (on) c.rdAttr(R.attr.rd_accent_bright) else c.rdAttr(R.attr.rd_panel); paint.alpha = alpha
        rect.set(lx, d(3f), lx + labelWidth, d(29f))
        canvas.drawRoundRect(rect, d(3f), d(3f), paint)
        val word = if (on) onWord else offWord
        text.color = if (on) c.rdAttr(R.attr.colorPrimary) else ContextCompat.getColor(c, R.color.rd_fg_a3); text.alpha = alpha
        val tw = text.measureText(word)
        val fm = text.fontMetrics
        canvas.drawText(word, lx + (labelWidth - tw) / 2f, d(16f) - (fm.ascent + fm.descent) / 2f, text)
        // the square
        val sx = place(if (on) d(39f) + labelWidth - d(33f) else d(3f), d(26f))
        paint.color = if (on) c.rdAttr(R.attr.rd_accent_dim) else ContextCompat.getColor(c, R.color.rd_fg_a4); paint.alpha = alpha
        rect.set(sx, d(3f), sx + d(26f), d(29f))
        canvas.drawRoundRect(rect, d(3f), d(3f), paint)
    }

    override fun setChecked(checked: Boolean) {
        if (on != checked) { on = checked; invalidate(); describe() }
    }

    override fun isChecked() = on
    override fun toggle() = setChecked(!on)
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); invalidate() }
}

/** Keeps a "%s" summary out: the value is in the well. */
private fun Preference.helpOnly(summary: CharSequence?): CharSequence? =
        summary?.takeUnless { it.contains("%s") || it.isBlank() }

/** The dropdown of the design: the value in a well with its chevron; the choices open under it. */
open class RdListPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ListPreference(context, attrs) {
    private val help: CharSequence? = helpOnly(super.getSummary())
    private var well: View? = null

    init {
        widgetLayoutResource = R.layout.rd_pref_well
        summaryProvider = null
    }

    override fun getSummary(): CharSequence? = help

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        well = holder.findViewById(R.id.rd_well)
        (holder.findViewById(R.id.rd_well_text) as? TextView)?.text = entry?.takeIf { it.isNotEmpty() } ?: "—"
        holder.findViewById(android.R.id.summary)?.visibility = if (help.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    override fun onClick() {
        val anchor = well
        val values = entryValues
        if (anchor == null || entries == null || values == null || !anchor.isAttachedToWindow) return super.onClick()
        val popup = ListPopupWindow(context)
        popup.anchorView = anchor
        popup.setAdapter(object : ArrayAdapter<CharSequence>(context, R.layout.rd_pref_choice, android.R.id.text1, entries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.isActivated = values[position] == value
                return v
            }
        })
        popup.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.rd_menu_background))
        popup.width = maxOf(anchor.width, context.rdDp(180f).toInt())
        popup.isModal = true
        val selected = findIndexOfValue(value)
        popup.setOnItemClickListener { _, _, position, _ ->
            val v = values[position].toString()
            if (callChangeListener(v)) value = v
            popup.dismiss()
        }
        popup.show()
        if (selected >= 0) popup.listView?.setSelection(selected)
    }
}

/** A text field of the design: the value (or what VLC uses when it is empty) in a well; it is edited in a dialog. */
class RdEditTextPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : EditTextPreference(context, attrs) {
    private val help: CharSequence? = helpOnly(super.getSummary())

    init {
        widgetLayoutResource = R.layout.rd_pref_field
        summaryProvider = null
    }

    override fun getSummary(): CharSequence? = help

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.rd_well_text) as? TextView)?.let {
            it.text = text?.takeIf { t -> t.isNotBlank() } ?: context.getString(R.string.rd_default_value)
            it.alpha = if (text.isNullOrBlank()) 0.6f else 1f
        }
        holder.findViewById(android.R.id.summary)?.visibility = if (help.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}

/** A row that opens another screen (the chevron of the design's menus). */
class RdLinkPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {
    init { widgetLayoutResource = R.layout.rd_pref_chevron }
}

/** SettingsActionRow of the design: the label, then a button (Open, Clear...). */
class RdActionPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {
    private val actionText: CharSequence

    init {
        widgetLayoutResource = R.layout.rd_pref_action
        val res = attrs?.getAttributeResourceValue("http://schemas.android.com/apk/res-auto", "actionText", 0) ?: 0
        actionText = if (res != 0) context.getText(res) else context.getString(R.string.rd_open)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.rd_action) as? TextView)?.let {
            it.text = actionText
            it.setOnClickListener { performClick() }
        }
    }
}

/** SettingsSegmented of the design (the Theme: Dark, Light, System). */
class RdSegmentedPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ListPreference(context, attrs) {
    init {
        widgetLayoutResource = R.layout.rd_pref_segmented
        summaryProvider = null
    }

    override fun getSummary(): CharSequence? = null
    override fun onClick() {}

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isClickable = false
        holder.itemView.background = null
        holder.findViewById(android.R.id.summary)?.visibility = View.GONE
        val box = holder.findViewById(R.id.rd_segments) as? LinearLayout ?: return
        box.removeAllViews()
        val ctx = context
        val accent = ctx.rdAttr(R.attr.colorPrimary)
        entries.forEachIndexed { i, label ->
            val v = entryValues[i].toString()
            val chosen = v == value
            val seg = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = ResourcesCompat.getFont(ctx, R.font.inter)
                setTextColor(if (chosen) accent else ContextCompat.getColor(ctx, R.color.rd_fg_a3))
                minWidth = ctx.rdDp(64f).toInt()
                setPadding(ctx.rdDp(10f).toInt(), 0, ctx.rdDp(10f).toInt(), 0)
                background = GradientDrawable().apply {
                    cornerRadius = ctx.rdDp(3f)
                    setColor(if (chosen) ctx.rdAttr(R.attr.rd_accent_bright) else ctx.rdAttr(R.attr.rd_panel))
                }
                isSelected = chosen
                contentDescription = label
                setOnClickListener { if (!chosen && callChangeListener(v)) value = v }
            }
            if (org.videolan.vlc.gui.tablet.RdTvWindow.isOn(ctx)) {
                // the TV layout at the design's size (.d-seg-o): the options share the 320 well, 2 apart, radius 4
                seg.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                seg.includeFontPadding = false
                seg.minWidth = 0
                (seg.background as GradientDrawable).cornerRadius = ctx.rdDp(4f)
                box.addView(seg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = ctx.rdDp(2f).toInt() })
                return@forEachIndexed
            }
            box.addView(seg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                if (i > 0) marginStart = ctx.rdDp(3f).toInt()
            })
        }
    }
}

/** The accent colours of the design: nine squares, the chosen one framed. */
class RdAccentPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ListPreference(context, attrs) {
    init {
        layoutResource = R.layout.rd_pref_accent
        summaryProvider = null
    }

    override fun getSummary(): CharSequence? = null
    override fun onClick() {}

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isClickable = false
        holder.itemView.background = null
        val box = holder.findViewById(R.id.rd_swatches) as? ViewGroup ?: return
        box.removeAllViews()
        val ctx = context
        entryValues.forEachIndexed { i, raw ->
            val v = raw.toString()
            val chosen = v == value
            val colour = ContextCompat.getColor(ctx, ctx.resources.getIdentifier("rd_accent_${v}_main", "color", ctx.packageName).takeIf { it != 0 } ?: R.color.rd_accent_orange_main)
            val cell = ImageView(ctx).apply {
                val inset = ctx.rdDp(6f).toInt()
                setPadding(inset, inset, inset, inset)
                setImageDrawable(GradientDrawable().apply { cornerRadius = ctx.rdDp(2f); setColor(colour) })
                background = GradientDrawable().apply {
                    cornerRadius = ctx.rdDp(4f)
                    setColor(if (chosen) ctx.rdAttr(R.attr.rd_accent_bright) else 0)
                    if (chosen) setStroke(ctx.rdDp(1f).toInt(), colour)
                }
                contentDescription = ctx.getString(R.string.rd_accent_named, entries[i])
                isSelected = chosen
                setOnClickListener { if (!chosen && callChangeListener(v)) value = v }
            }
            if (org.videolan.vlc.gui.tablet.RdTvWindow.isOn(ctx) && box is LinearLayout) {
                // the TV layout at the design's size (.d-sw): the nine share the 320 well, 4 apart, 32 high; the
                // colour 6 in from each side, 20 high, at 70 % but the chosen one's
                cell.setPadding(ctx.rdDp(6f).toInt(), ctx.rdDp(6f).toInt(), ctx.rdDp(6f).toInt(), ctx.rdDp(6f).toInt())
                cell.scaleType = ImageView.ScaleType.FIT_XY
                cell.imageAlpha = if (chosen) 255 else 178
                box.addView(cell, LinearLayout.LayoutParams(0, ctx.rdDp(32f).toInt(), 1f).apply { if (i > 0) marginStart = ctx.rdDp(4f).toInt() })
                return@forEachIndexed
            }
            val size = ctx.rdDp(32f).toInt()
            box.addView(cell, ViewGroup.MarginLayoutParams(size, size).apply { marginEnd = ctx.rdDp(4f).toInt() })
        }
    }
}

/**
 * "Advanced Options" of the design: a bar that shows or hides the page's advanced rows (the category whose key is
 * this bar's key without "_bar"); closed whenever the page opens, as on the desktop.
 */
class RdAdvancedPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {
    var expanded = false
        private set

    init {
        layoutResource = R.layout.rd_pref_advanced
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.rd_chevron) as? ImageView)?.rotation = if (expanded) 180f else 0f
        holder.itemView.isActivated = expanded
    }

    @SuppressLint("RestrictedApi")
    override fun onClick() {
        expanded = !expanded
        preferenceManager?.findPreference<PreferenceCategory>(key.removeSuffix("_bar"))?.isVisible = expanded
        notifyChanged()
    }

    fun expand() {
        if (!expanded) onClick()
    }
}
