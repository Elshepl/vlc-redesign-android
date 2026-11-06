/*
 * VLC Redesign: Settings in the TV layout as the design's dialog (Figma "Settings - *", 1048 x 865, the desktop's
 * SettingsDialog.qml) over the library, as the Samsung TV app draws it (vlc-redesign-tizen app/js/settings.js and
 * css/dialogs.css): the card #0E0E0E radius 14 with its 1 px #4C4A48 line, the bar of 50 (#090909) with the page's
 * title at 240 and the close button at 1002, 9; the sidebar #181818 radius 6 at 8, 58 (200 x 799); the page
 * #0F0F0F from 216 under the bar, its content at 240 (784 wide). The TV has no search in Settings (the owner's
 * choice, as on the Samsung TV app). PreferencesActivity hands itself over to this one in the TV layout; the
 * results go to whoever opened Settings (FLAG_ACTIVITY_FORWARD_RESULT).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.preferences

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.appbar.AppBarLayout
import org.videolan.tools.dp
import org.videolan.vlc.R

class TvPreferencesActivity : PreferencesActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val content = findViewById<ViewGroup>(android.R.id.content)
        val page = content.getChildAt(0)
        content.removeView(page)

        // the library under a veil of 50 % black (.dlg-veil); Back or the close button closes
        val veil = FrameLayout(this).apply { setBackgroundColor(0x80000000.toInt()) }
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = 14.dp.toFloat(); setColor(0xFF0E0E0E.toInt()) }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 14.dp.toFloat())
            }
            clipToOutline = true
        }
        // the page's background beside the sidebar
        card.addView(View(this).apply { setBackgroundColor(0xFF0F0F0F.toInt()) },
                FrameLayout.LayoutParams(832.dp, 815.dp).apply { leftMargin = 216.dp; topMargin = 50.dp })
        card.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = 14.dp.toFloat(); setColor(0); setStroke(1.dp, 0xFF4C4A48.toInt()) }
            isFocusable = false
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        veil.addView(card, FrameLayout.LayoutParams(1048.dp, 865.dp, Gravity.CENTER))
        content.addView(veil, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.setBackgroundColor(Color.TRANSPARENT)

        // the bar: 50 high, #090909, the title (Inter 16, FG/2) at 240, no back arrow; the close button
        val appbar = findViewById<AppBarLayout>(R.id.appbar)
        appbar.setBackgroundColor(0xFF090909.toInt())
        appbar.elevation = 0f
        appbar.post { appbar.elevation = 0f }
        val toolbar = findViewById<Toolbar>(R.id.main_toolbar)
        toolbar.layoutParams = toolbar.layoutParams.apply { height = 50.dp }
        toolbar.minimumHeight = 50.dp
        toolbar.navigationIcon = null
        toolbar.setContentInsetsAbsolute(0, 0)
        toolbar.setTitleTextColor(0xFF969696.toInt())
        toolbar.titleMarginStart = 232.dp
        toolbar.findViewById<View>(R.id.toolbar_icon)?.visibility = View.GONE
        toolbar.findViewById<View>(R.id.toolbar_vlc_title)?.visibility = View.GONE
        toolbar.post { styleTitle(toolbar) }
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.rd_ic_close)
            setColorFilter(0xFF8F8F8F.toInt())
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            contentDescription = getString(R.string.close)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply { cornerRadius = 4.dp.toFloat(); setColor(0) }
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(32.dp, 32.dp).apply { leftMargin = 1002.dp; topMargin = 9.dp })

        // the sidebar at 8, 8 under the bar (200 x 799, #181818, radius 6); the page's content at 240, 784 wide
        findViewById<View>(R.id.rd_settings_sidebar)?.let { side ->
            (side.layoutParams as LinearLayout.LayoutParams).apply { width = 200.dp; height = 799.dp; setMargins(8.dp, 8.dp, 0, 8.dp) }
            side.background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0xFF181818.toInt()) }
            side.clipToOutline = true
        }
        findViewById<View>(R.id.fragment_placeholder)?.let { pane ->
            (pane.layoutParams as LinearLayout.LayoutParams).apply { width = 784.dp; weight = 0f; marginStart = 32.dp }
        }
    }

    private fun styleTitle(toolbar: Toolbar) {
        for (i in 0 until toolbar.childCount) {
            val v = toolbar.getChildAt(i)
            if (v is TextView && v.text == toolbar.title) {
                v.typeface = ResourcesCompat.getFont(this, R.font.inter)
                v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                v.includeFontPadding = false
            }
        }
    }

    /** No search in the TV's Settings (as the Samsung TV app) */
    override fun onCreateOptionsMenu(menu: Menu) = true
}
