/*
 * VLC Redesign: the player's top bar in the TV layout at the design's size (Figma 247:445 "topbar", the desktop's
 * player TopBar), as the Samsung TV app draws it (player.css .pl-top): 40 high, #1B1B1B; the cone at 13, 8; the
 * menu (⋯) at 51; the library panel's button at 89 (the queue); the divider at 117; the folder's icon and name at
 * 141; the title in the middle (460 to 1460); the clock 20 from the end where a computer has the window buttons.
 * The phone's top bar (its title, buttons and quick actions) stays under it, unseen.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import org.videolan.tools.dp
import org.videolan.vlc.R

object RdTvPlayerTopBar {

    private const val GREY = 0xFF8F8F8F.toInt()

    fun apply(player: VideoPlayerActivity, hud: ConstraintLayout, title: TextView, folder: () -> String?) {
        if (hud.findViewById<View>(R.id.rd_tv_titlebar) != null) return
        // the phone's bar: kept (the code shows and hides its parts) but not seen nor reached
        for (i in 0 until hud.childCount) hud.getChildAt(i).apply { alpha = 0f; isFocusable = false; isClickable = false }
        hud.setPadding(0, 0, 0, 0)
        hud.setBackgroundColor(0xFF1B1B1B.toInt())
        val ctx = player
        val inter = ResourcesCompat.getFont(ctx, R.font.inter)
        val bar = FrameLayout(ctx).apply { id = R.id.rd_tv_titlebar }
        fun text(size: Float) = TextView(ctx).apply {
            typeface = inter
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
            setTextColor(GREY)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        fun button(icon: Int, desc: Int, x: Int, onClick: () -> Unit) = ImageView(ctx).apply {
            setImageResource(icon)
            setColorFilter(GREY)
            setPadding(2.dp, 2.dp, 2.dp, 2.dp)
            contentDescription = ctx.getString(desc)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0) }
            setOnClickListener { onClick() }
            bar.addView(this, FrameLayout.LayoutParams(28.dp, 28.dp).apply { leftMargin = x.dp; topMargin = 6.dp })
        }
        bar.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.rd_ic_logo)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(24.dp, 24.dp).apply { leftMargin = 13.dp; topMargin = 8.dp })
        button(R.drawable.rd_tvd_more, R.string.more, 51) { player.showAdvancedOptions() }
        button(R.drawable.rd_tvd_sidebar, R.string.rd_bar_queue, 89) { player.onBarAction("queue") }
        bar.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.rd_tvd_divider)
            setColorFilter(0xFF373737.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(24.dp, 24.dp).apply { leftMargin = 117.dp; topMargin = 8.dp })
        bar.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.rd_tvd_folder)
            setColorFilter(GREY)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(24.dp, 24.dp).apply { leftMargin = 143.dp; topMargin = 8.dp })
        val folderName = text(14f)
        bar.addView(folderName, FrameLayout.LayoutParams(280.dp, 28.dp).apply { leftMargin = 173.dp; topMargin = 7.dp })
        val centre = text(14f).apply { gravity = Gravity.CENTER }
        bar.addView(centre, FrameLayout.LayoutParams(1000.dp, 28.dp).apply { leftMargin = 460.dp; topMargin = 7.dp })
        bar.addView(TextClock(ctx).apply {
            typeface = inter
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(GREY)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 28.dp, Gravity.END).apply { rightMargin = 20.dp; topMargin = 7.dp })
        fun sync() {
            centre.text = title.text
            folderName.text = folder().orEmpty()
        }
        title.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = sync()
        })
        sync()
        hud.addView(bar, ConstraintLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40.dp).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        })
        hud.minHeight = 40.dp
        hud.layoutParams = hud.layoutParams.apply { height = 40.dp }
    }
}
