/*
 * VLC Redesign: the desktop's Effects dialog (EffectsDialog.qml): "Effects" and Close, the Audio and Video tabs,
 * a panel per filter with its ON / OFF toggle, its sliders (FilterPanel.qml, ValueSlider.qml) and Reset, the video
 * filters in the desktop's sections (VideoEffectsPanel.qml). Only what VLC for Android can apply is listed
 * (RdEffects): the audio ones are Android effects on VLC's session (LoudnessEffects), the video ones options of the
 * media, which is opened again where it was when they change. The equalizer keeps its own panel (Equalizer row).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.dialogs

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.resources.RdEffects
import org.videolan.tools.Settings
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.RdStyle
import org.videolan.vlc.util.LoudnessEffects
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class RdEffectsDialog : VLCBottomSheetDialogFragment() {

    private lateinit var prefs: SharedPreferences
    private lateinit var body: LinearLayout
    private val tabViews = mutableListOf<TextView>()
    private val sectionViews = mutableListOf<TextView>()
    private var tab = 0
    private var section = 0
    private var reloadJob: Job? = null

    override fun getDefaultState() = BottomSheetBehavior.STATE_EXPANDED
    override fun needToManageOrientation() = true
    override fun initialFocusedView(): View = tabViews.firstOrNull() ?: requireView()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).roundToInt()
    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)
    private fun accent(): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, tv, true)
        return if (tv.resourceId != 0) color(tv.resourceId) else tv.data
    }

    private fun text(ctx: Context, s: CharSequence, sp: Float, c: Int, medium: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(c)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        prefs = Settings.getInstance(ctx)
        tab = savedInstanceState?.getInt("tab") ?: if (PlaybackService.serviceFlow.value?.isVideoPlaying == true) 1 else 0
        section = savedInstanceState?.getInt("section") ?: 0
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // the title row: "Effects" (18, medium) and Close (13, secondary)
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(20f), 0, dp(8f), 0)
            minimumHeight = dp(54f)
        }
        header.addView(text(ctx, getString(R.string.rd_fx_effects), 18f, color(R.color.rd_fg_a1), true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(text(ctx, getString(R.string.close), 13f, color(R.color.rd_fg_a3)).apply {
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            isFocusable = true
            setBackgroundResource(R.drawable.rd_option_row_bg)
            setOnClickListener { dismiss() }
        })
        root.addView(header)
        // the Audio / Video tabs: the accent and a 2 dp line under the chosen one
        val tabs = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(R.string.audio, R.string.video).forEachIndexed { i, label ->
            val t = text(ctx, getString(label), 14f, 0).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                minHeight = dp(45f)
                isFocusable = true
                setOnClickListener { if (tab != i) { tab = i; fill() } }
            }
            tabViews.add(t)
            tabs.addView(t, LinearLayout.LayoutParams(0, dp(45f), 1f))
        }
        root.addView(tabs)
        root.addView(View(ctx).apply { setBackgroundColor(color(R.color.rd_bg_a4)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)))
        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(28f))
        }
        val scroll = NestedScrollView(ctx).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        fill()
        return root
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putInt("section", section)
    }

    private fun fill() {
        val ctx = requireContext()
        tabViews.forEachIndexed { i, t ->
            t.setTextColor(if (i == tab) accent() else color(R.color.rd_fg_a3))
            t.background = if (i == tab) underline() else null
        }
        body.removeAllViews()
        if (tab == 0) {
            val service = PlaybackService.serviceFlow.value
            if (service == null || !service.hasMedia()) body.addView(note(getString(R.string.rd_fx_play_audio)))
            body.addView(equalizerRow(ctx))
            RdEffects.audio.forEach { body.addView(filterPanel(ctx, it, LoudnessEffects.available(it.id))) }
        } else {
            if (PlaybackService.serviceFlow.value?.isVideoPlaying != true || activity !is org.videolan.vlc.gui.video.VideoPlayerActivity) {
                body.addView(note(getString(R.string.rd_fx_play_video)))
                return
            }
            // the desktop's sections that libvlc 3 can apply: rows of three buttons, the chosen one in the accent
            sectionViews.clear()
            val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            var line: LinearLayout? = null
            RdEffects.videoSections.forEachIndexed { i, (label, _) ->
                if (i % 3 == 0) line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; grid.addView(this) }
                val b = text(ctx, getString(label), 14f, if (i == section) accent() else color(R.color.rd_fg_a3)).apply {
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    maxLines = 2
                    isFocusable = true
                    setBackgroundResource(R.drawable.rd_option_row_bg)
                    setOnClickListener { if (section != i) { section = i; fill() } }
                }
                sectionViews.add(b)
                line?.addView(b, LinearLayout.LayoutParams(0, dp(42f), 1f))
            }
            // a short last row keeps the columns of the others
            repeat((3 - RdEffects.videoSections.size % 3) % 3) { line?.addView(View(ctx), LinearLayout.LayoutParams(0, dp(42f), 1f)) }
            body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16f) })
            val filters = RdEffects.videoSections[section].second
            filters.forEach { body.addView(filterPanel(ctx, it, true, resetButton = false)) }
            // the desktop's Reset of the section: every filter's values back to their defaults
            body.addView(resetButton(ctx) {
                filters.forEach { RdEffects.reset(prefs, it) }
                if (filters.any { RdEffects.isOn(prefs, it) }) scheduleVideoReload()
                fill()
            })
        }
    }

    private fun underline() = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable().apply { setColor(accent()) })).apply {
        setLayerGravity(0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        setLayerHeight(0, dp(2f))
        setLayerInsetLeft(0, dp(16f))
        setLayerInsetRight(0, dp(16f))
    }

    private fun note(s: String) = text(requireContext(), s, 14f, color(R.color.rd_fg_a3)).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16f) }
    }

    private fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.rd_pref_well_bg)
        setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12f) }
    }

    /** The Equalizer: its own panel (presets, bands, two-pass), opened from here */
    private fun equalizerRow(ctx: Context) = card(ctx).apply {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44f)
            isFocusable = true
            setOnClickListener {
                val activity = requireActivity()
                dismiss()
                EqualizerFragmentDialog.newInstance().show(activity.supportFragmentManager, "equalizer")
            }
        }
        row.addView(text(ctx, getString(R.string.equalizer), 15f, color(R.color.rd_fg_a1), true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(ctx, getString(if (prefs.getBoolean("equalizer_enabled", false)) R.string.rd_toggle_on else R.string.rd_toggle_off), 13f, color(R.color.rd_fg_a3)))
        row.addView(android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.rd_ic_chevron_down)
            rotation = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 90f else -90f
            imageTintList = ColorStateList.valueOf(color(R.color.rd_fg_a3))
        }, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginStart = dp(8f) })
        addView(row)
    }

    /** A filter: its title and ON / OFF toggle, then (when on) its settings and Reset */
    private fun filterPanel(ctx: Context, f: RdEffects.Filter, available: Boolean, resetButton: Boolean = true) = card(ctx).apply {
        val on = RdEffects.isOn(prefs, f) && available
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44f)
        }
        head.addView(text(ctx, getString(f.title), 15f, color(if (available) R.color.rd_fg_a1 else R.color.rd_fg_a4), true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = AppCompatCheckBox(ctx).apply {
            isChecked = on
            isEnabled = available
            contentDescription = getString(R.string.rd_fx_enable, getString(f.title))
            RdStyle.toggle(this)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit { putBoolean(f.key, checked) }
                changed(f)
                fill()
            }
        }
        head.addView(toggle)
        head.setOnClickListener { if (available) toggle.toggle() }
        addView(head)
        if (!on) return@apply
        for (p in f.params) addView(if (p.choices != null) choiceRow(ctx, f, p) else sliderRow(ctx, f, p))
        if (resetButton && f.params.isNotEmpty()) addView(resetButton(ctx) {
            RdEffects.reset(prefs, f)
            changed(f)
            fill()
        })
    }

    private fun format(p: RdEffects.Param, v: Float): String {
        val shown = v * p.scale
        val decimals = when {
            p.step * p.scale >= 1f -> 0
            p.step * p.scale >= 0.1f -> 1
            else -> 2
        }
        val n = String.format(Locale.getDefault(), "%.${decimals}f", shown)
        if (p.unit == 0) return n
        val u = getString(p.unit)
        return if (u.startsWith(":") || u == "%") "$n$u".replace(":", " :") else "$n $u"
    }

    private fun sliderRow(ctx: Context, f: RdEffects.Filter, p: RdEffects.Param) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8f), 0, 0)
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        // a filter of one setting names it with its title (the desktop's "Sharpen Sigma")
        val label = if (f.params.size == 1 && p.label == f.title) "" else getString(p.label)
        top.addView(text(ctx, label, 14f, color(R.color.rd_fg_a2)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueText = text(ctx, format(p, RdEffects.value(prefs, p)), 14f, color(R.color.rd_fg_a1))
        top.addView(valueText)
        addView(top)
        val slider = Slider(ctx).apply {
            valueFrom = p.min
            valueTo = p.max
            val v = RdEffects.value(prefs, p)
            value = if (p.step > 0f) (p.min + ((v - p.min) / p.step).roundToInt() * p.step).coerceIn(p.min, p.max) else v
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            val a = accent()
            trackActiveTintList = ColorStateList.valueOf(a)
            thumbTintList = ColorStateList.valueOf(a)
            haloTintList = ColorStateList.valueOf(a and 0x33FFFFFF)
            trackInactiveTintList = ColorStateList.valueOf(color(R.color.rd_bg_a4))
            contentDescription = getString(p.label)
            addOnChangeListener { _, nv, _ -> valueText.text = format(p, nv) }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    if (abs(slider.value - RdEffects.value(prefs, p)) < 1e-4f) return
                    prefs.edit { putFloat(p.key, slider.value) }
                    changed(f)
                }
            })
        }
        addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A list setting: the desktop's dropdown (the well and its chevron), its choices in a menu */
    private fun choiceRow(ctx: Context, f: RdEffects.Filter, p: RdEffects.Param) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8f), 0, dp(10f))
        addView(text(ctx, getString(p.label), 14f, color(R.color.rd_fg_a2)))
        val choices = p.choices ?: return@apply
        val current = RdEffects.choice(prefs, p)
        val box = text(ctx, getString(choices.first { it.first == current }.second), 14f, color(R.color.rd_fg_a1)).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.rd_spinner_bg)
            setPaddingRelative(dp(14f), 0, dp(34f), 0)
            minHeight = dp(44f)
            isFocusable = true
            setOnClickListener { v ->
                val menu = PopupMenu(ctx, v)
                choices.forEachIndexed { i, c ->
                    val title = android.text.SpannableString(getString(c.second))
                    if (c.first == current) title.setSpan(android.text.style.ForegroundColorSpan(accent()), 0, title.length, 0)
                    menu.menu.add(0, i, i, title)
                }
                menu.setOnMenuItemClickListener { item ->
                    prefs.edit { putString(p.key, choices[item.itemId].first) }
                    changed(f)
                    fill()
                    true
                }
                menu.show()
            }
        }
        addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44f)).apply { topMargin = dp(6f) })
    }

    private fun resetButton(ctx: Context, action: () -> Unit) = FrameLayout(ctx).apply {
        val b = com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.rd_reset)
            isAllCaps = false
            contentDescription = getString(R.string.rd_fx_reset_defaults)
            setOnClickListener { action() }
        }
        addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END))
        setPadding(0, dp(4f), 0, dp(6f))
    }

    /** A filter changed: an audio one at once on the session, a video one by opening the media again */
    private fun changed(f: RdEffects.Filter) {
        if (!f.video) LoudnessEffects.apply(requireContext().applicationContext)
        else scheduleVideoReload()
    }

    private fun scheduleVideoReload() {
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch {
            delay(400)
            (activity as? org.videolan.vlc.gui.video.VideoPlayerActivity)?.reloadForEffects()
        }
    }

    override fun onDestroy() {
        // closed before the delay: the change is applied now
        if (reloadJob?.isActive == true) {
            reloadJob?.cancel()
            (activity as? org.videolan.vlc.gui.video.VideoPlayerActivity)?.reloadForEffects()
        }
        super.onDestroy()
    }

    companion object {
        fun newInstance() = RdEffectsDialog()
    }
}
