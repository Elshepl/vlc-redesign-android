/*
 * VLC Redesign: the desktop's artist banner (ArtistTopBanner.qml) over an artist's (or a genre's) albums and
 * tracks: the blurred artwork under a veil (black in the dark theme, white in the light one), the round picture,
 * the name in Inter 22, "N albums · N tracks", Play all and Shuffle.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.audio

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.media.Artist
import org.videolan.medialibrary.interfaces.media.Genre
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.media.MediaUtils

object RdArtistBanner {

    /** Adds the banner at the top of [root] (the artist page's RelativeLayout); the search well and lists go under it. */
    fun install(root: RelativeLayout, item: MediaLibraryItem, scope: LifecycleCoroutineScope) {
        val ctx = root.context
        // a phone in landscape keeps its height for the lists
        if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                ctx.resources.configuration.screenHeightDp < 500) return
        if (root.findViewById<View>(R.id.rd_artist_banner) != null) return
        val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val height = if (ctx.resources.getBoolean(R.bool.is_tablet)) 176.dp else 144.dp
        val banner = FrameLayout(ctx).apply { id = R.id.rd_artist_banner; clipChildren = true }
        val backdrop = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.INVISIBLE }
        banner.addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(24.dp, 16.dp, 24.dp, 16.dp)
        }
        val size = height - 48.dp
        val picture = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_artist_big)
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.rd_divider_dim))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(picture, LinearLayout.LayoutParams(size, size))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val inter = ResourcesCompat.getFont(ctx, R.font.inter)
        fun text(sp: Float, color: Int, weight: Int) = TextView(ctx).apply {
            typeface = if (android.os.Build.VERSION.SDK_INT >= 28) android.graphics.Typeface.create(inter, weight, false) else inter
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(ContextCompat.getColor(ctx, color))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        texts.addView(text(22f, R.color.rd_fg_1, 600).apply { text = item.title })
        val counts = when (item) {
            is Artist -> listOf(ctx.resources.getQuantityString(R.plurals.albums_quantity, item.albumsCount, item.albumsCount),
                    ctx.resources.getQuantityString(R.plurals.track_quantity, item.tracksCount, item.tracksCount))
            is Genre -> listOf(ctx.resources.getQuantityString(R.plurals.track_quantity, item.tracksCount, item.tracksCount))
            else -> emptyList()
        }
        texts.addView(text(14f, R.color.rd_fg_a1, 400).apply { text = counts.joinToString(" · ") },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 2.dp })
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: Int, accent: Boolean, shuffle: Boolean) = text(14f, if (accent) android.R.color.white else R.color.rd_fg_1, 500).apply {
            setText(label)
            gravity = Gravity.CENTER
            setPaddingRelative(16.dp, 0, 16.dp, 0)
            setBackgroundResource(if (accent) R.drawable.rd_onboarding_next_bg else R.drawable.rd_pref_button_bg)
            isFocusable = true
            setOnClickListener {
                scope.launch {
                    val tracks = withContext(Dispatchers.IO) { item.tracks }
                    if (!tracks.isNullOrEmpty()) MediaUtils.openList(ctx, tracks.toList(), 0, shuffle)
                }
            }
        }
        buttons.addView(button(R.string.play_all, true, false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36.dp))
        buttons.addView(button(R.string.shuffle_play, false, true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36.dp).apply { marginStart = 8.dp })
        texts.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 20.dp })
        banner.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(banner, 0, RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) })
        // the search well and the lists start under the banner
        root.findViewById<View>(R.id.searchButton)?.let { s ->
            (s.layoutParams as? RelativeLayout.LayoutParams)?.addRule(RelativeLayout.BELOW, R.id.rd_artist_banner)
        }
        root.findViewById<View>(R.id.swipeLayout)?.let { s ->
            (s.layoutParams as? RelativeLayout.LayoutParams)?.addRule(RelativeLayout.BELOW, R.id.searchButton)
        }
        val mrl = item.artworkMrl
        if (!mrl.isNullOrEmpty()) scope.launch {
            val cover = withContext(Dispatchers.IO) { AudioUtil.readCoverBitmap(Uri.decode(mrl), 512) } ?: return@launch
            picture.setImageBitmap(cover)
            UiTools.blurView(backdrop, cover, 20f, if (dark) Color.argb(153, 0, 0, 0) else Color.argb(170, 255, 255, 255))
            backdrop.visibility = View.VISIBLE
        }
    }
}
