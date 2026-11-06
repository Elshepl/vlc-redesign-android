/*
 * VLC Redesign: Open Network Stream of the TV layout at the design's size (Figma 6216:13614, the desktop's
 * NetworkStreamDialog; the Samsung TV app's dialogs.js openNetwork): a dialog of 1048 x 631 over the library (#191919,
 * radius 10), the bar of 50 (#0E0E0E: the title 14 in FG/A2 at 19, the close button at the end); "Network URL" with
 * its help and the field of 1004 x 190 for one link or several (one a line); the line; "Playlist Name (optional)"
 * with its help and its field of 1004 x 33; Cancel and Save at the bottom. Save plays the links (the first, the
 * others after it) and, with a name, keeps them as a playlist of that name.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.tablet

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.tools.AppScope
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.AudioPlayerContainerActivity
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.gui.dialogs.getPlaylistByName

object RdTvNetworkDialog {

    fun show(activity: AudioPlayerContainerActivity) {
        val ctx = activity
        val inter = ResourcesCompat.getFont(ctx, R.font.inter)
        val dialog = Dialog(ctx)
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = 10.dp.toFloat(); setColor(0xFF191919.toInt()) }
            clipToOutline = true
        }
        fun text(size: Float, color: Int, x: Int, y: Int, value: CharSequence) = TextView(ctx).apply {
            typeface = inter
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
            setTextColor(color)
            includeFontPadding = false
            text = value
            card.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = x.dp; topMargin = y.dp })
        }
        fun field(x: Int, y: Int, w: Int, h: Int, hint: String, lines: Boolean) = EditText(ctx).apply {
            typeface = inter
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(0xFF969696.toInt())
            setHintTextColor(0xFF404040.toInt())
            this.hint = hint
            gravity = Gravity.TOP or Gravity.START
            setPadding(12.dp, if (lines) 9.dp else 8.dp, 12.dp, 8.dp)
            includeFontPadding = false
            inputType = if (lines) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
            isSingleLine = !lines
            background = GradientDrawable().apply { cornerRadius = 2.dp.toFloat(); setColor(0xFF0A0A0A.toInt()) }
            card.addView(this, FrameLayout.LayoutParams(w.dp, h.dp).apply { leftMargin = x.dp; topMargin = y.dp })
        }
        // the bar
        card.addView(View(ctx).apply { setBackgroundColor(0xFF0E0E0E.toInt()) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 50.dp))
        text(14f, 0xFF8F8F8F.toInt(), 19, 17, ctx.getString(R.string.rd_open_network_stream))
        card.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.rd_ic_close)
            setColorFilter(0xFF8F8F8F.toInt())
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            contentDescription = ctx.getString(R.string.close)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply { cornerRadius = 4.dp.toFloat(); setColor(0) }
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(32.dp, 32.dp).apply { leftMargin = 1007.dp; topMargin = 9.dp })
        // the links
        text(16f, 0xFFC6C6C6.toInt(), 20, 78, ctx.getString(R.string.rd_net_url))
        text(14f, 0xFF6B6B6B.toInt(), 20, 111, ctx.getString(R.string.rd_net_url_help))
        val links = field(22, 152, 1004, 190, ctx.getString(R.string.rd_net_url_hint), true)
        card.addView(View(ctx).apply { setBackgroundColor(0xFF222222.toInt()) }, FrameLayout.LayoutParams(1008.dp, 1.dp).apply { leftMargin = 20.dp; topMargin = 364.dp })
        // the playlist's name (optional)
        val title = SpannableStringBuilder(ctx.getString(R.string.rd_net_playlist_name)).append(" ")
        val start = title.length
        title.append(ctx.getString(R.string.rd_net_optional))
        title.setSpan(ForegroundColorSpan(0xFF6B6B6B.toInt()), start, title.length, 0)
        text(16f, 0xFFC6C6C6.toInt(), 20, 390, title)
        text(14f, 0xFF6B6B6B.toInt(), 20, 423, ctx.getString(R.string.rd_net_playlist_help))
        val name = field(22, 466, 1004, 33, ctx.getString(R.string.rd_net_playlist_hint), false)
        // Cancel and Save (.d-btn: 100 x 32, #1E1E1E radius 6, Inter SemiBold 14)
        fun button(label: Int, x: Int, onClick: () -> Unit) = TextView(ctx).apply {
            typeface = android.graphics.Typeface.create(inter, android.graphics.Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(0xFF8F8F8F.toInt())
            gravity = Gravity.CENTER
            text = ctx.getString(label)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0xFF1E1E1E.toInt()) }
            setOnClickListener { onClick() }
            card.addView(this, FrameLayout.LayoutParams(100.dp, 32.dp).apply { leftMargin = x.dp; topMargin = 579.dp })
        }
        button(R.string.cancel, 20) { dialog.dismiss() }
        val save = button(R.string.save, 928) {
            val uris = links.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (uris.isEmpty()) return@button
            val medias = uris.map { MLServiceLocator.getAbstractMediaWrapper(Uri.parse(it)) }
            MediaUtils.openList(activity, medias, 0)
            val playlist = name.text.toString().trim()
            if (playlist.isNotEmpty()) AppScope.launch(Dispatchers.IO) {
                val ml = Medialibrary.getInstance()
                val p = ml.getPlaylistByName(playlist) ?: ml.createPlaylist(playlist, Settings.includeMissing, false) ?: return@launch
                for (u in uris) ml.addStream(u, Uri.parse(u).lastPathSegment ?: u)?.let { p.append(it.id) }
            }
            dialog.dismiss()
        }
        // Save is dim until there is a link (the design's disabled button)
        fun refresh() { save.alpha = if (links.text.isNullOrBlank()) 0.4f else 1f }
        links.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()
        dialog.setContentView(card, ViewGroup.LayoutParams(1048.dp, 631.dp))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(1048.dp, 631.dp)
            setDimAmount(0.4f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        org.videolan.vlc.gui.helpers.RdTvFocusRing.attach(dialog.window)
        dialog.show()
        links.requestFocus()
    }
}
