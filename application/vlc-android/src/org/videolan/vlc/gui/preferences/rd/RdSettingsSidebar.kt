/*
 * VLC Redesign: the settings' categories as the desktop's sidebar (SettingsSidebar.qml): the headings App, Media
 * and System, then the pages with their icon (the current one on BG/A1 with its icon in the accent). On a narrow
 * window it is the first screen of the settings (a page opens over it); from 600 dp it stays at the start of the
 * window with the page beside it.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.preferences.rd

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import org.videolan.vlc.R
import org.videolan.vlc.gui.SecondaryActivity
import org.videolan.vlc.gui.preferences.PreferencesActivity

class RdSettingsSidebar : Fragment() {

    private val rows = mutableMapOf<String, View>()
    private var current: String? = null
    private val wide get() = arguments?.getBoolean(ARG_WIDE) ?: false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val d = { v: Float -> ctx.rdDp(v).toInt() }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(8f), d(8f), d(8f), d(16f))
        }
        var group = 0
        for (page in RdPage.entries) {
            // Live TV: its page while its add-on is on
            if (page == RdPage.TV && !org.videolan.vlc.livetv.LiveTv.let { it.init(ctx); it.enabled }) continue
            // Extensions: its page while it has something to set (its only row, the optional features, has no
            // feature in this version: the page was an empty heading)
            if (page == RdPage.EXTENSIONS && org.videolan.vlc.util.FeatureFlag.entries.isEmpty()) continue
            if (page.group != group) {
                group = page.group
                list.addView(heading(getString(group)))
            }
            list.addView(row(page.key, getString(page.label), page.icon) { open(page) })
        }
        list.addView(View(ctx), LinearLayout.LayoutParams(1, d(12f)))
        list.addView(row(ABOUT, getString(R.string.rd_set_about), R.drawable.rd_ic_set_about) {
            startActivity(Intent(ctx, SecondaryActivity::class.java).putExtra("fragment", SecondaryActivity.ABOUT))
        })
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(list)
            if (wide) background = ContextCompat.getDrawable(ctx, R.drawable.rd_settings_sidebar_bg)
        }
        current?.let { select(it) }
        return scroll
    }

    private fun heading(text: String) = TextView(requireContext()).apply {
        val d = { v: Float -> context.rdDp(v).toInt() }
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = ResourcesCompat.getFont(context, R.font.inter)
        setTextColor(ContextCompat.getColor(context, R.color.rd_fg_a3))
        setPadding(d(10f), d(14f), d(10f), d(6f))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        isFocusable = false
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun row(key: String, label: String, icon: Int, onClick: () -> Unit): View {
        val ctx = requireContext()
        val d = { v: Float -> ctx.rdDp(v).toInt() }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = d(if (wide) 44f else 50f)
            setPadding(d(6f), 0, d(8f), 0)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            background = ContextCompat.getDrawable(ctx, R.drawable.rd_settings_sidebar_item)
        }
        val image = ImageView(ctx).apply {
            setImageResource(icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            ImageViewCompat.setImageTintList(this, ContextCompat.getColorStateList(ctx, R.color.rd_fg_a2))
            tag = icon
        }
        row.addView(image, LinearLayout.LayoutParams(d(24f), d(24f)).apply { marginStart = d(4f); marginEnd = d(12f) })
        val text = TextView(ctx).apply {
            this.text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = ResourcesCompat.getFont(ctx, R.font.inter)
            setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_a2))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!wide) row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.rd_ic_chevron_right)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(d(20f), d(20f)))
        rows[key] = row
        return row
    }

    private fun open(page: RdPage) {
        (activity as? PreferencesActivity)?.openPage(page)
    }

    /** The current page of a two-pane window: BG/A1, its label in FG/1, its icon in the accent. */
    fun select(key: String?) {
        current = key
        if (!wide) return
        val ctx = context ?: return
        for ((k, row) in rows) {
            val on = k == key
            row.isActivated = on
            val group = row as ViewGroup
            val image = group.getChildAt(0) as ImageView
            val text = group.getChildAt(1) as TextView
            val page = RdPage.of(k)
            if (page != null) image.setImageResource(if (on) page.iconActive else page.icon)
            ImageViewCompat.setImageTintList(image, if (on) android.content.res.ColorStateList.valueOf(ctx.rdAttr(R.attr.colorPrimary))
            else ContextCompat.getColorStateList(ctx, R.color.rd_fg_a2))
            text.setTextColor(ContextCompat.getColor(ctx, if (on) R.color.rd_fg_1 else R.color.rd_fg_a2))
        }
    }

    companion object {
        const val ABOUT = "about"
        private const val ARG_WIDE = "wide"
        fun newInstance(wide: Boolean) = RdSettingsSidebar().apply { arguments = bundleOf(ARG_WIDE to wide) }
    }
}
