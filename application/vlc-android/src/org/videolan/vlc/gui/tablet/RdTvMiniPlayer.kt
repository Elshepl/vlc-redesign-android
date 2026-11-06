/*
 * VLC Redesign: the mini player of the TV layout at the design's size (Figma "Miniplayer" 970:3091, the desktop's
 * MiniPlayer.qml) as the Samsung TV app draws it (shell.css .sh-mini): a card of 391 at 6 from the window's bottom
 * end, padding 8, radius 14, #1B1B1B with its shadow; the picture 375 x 211 (radius 6, a 2 px #090909 line); the seek
 * well 16 high 4 under it; the title (Inter 16, FG/2) 23 under the well; the folder and the artist with their icons
 * and the queue button on the last row. OK on the card opens the player; the phone's bar at the bottom is not shown
 * (the sheet's peek is 0), the music player still opens over the page.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.tablet

import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.dp
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.gui.AudioPlayerContainerActivity
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.gui.helpers.UiTools

object RdTvMiniPlayer {

    private class Views(val card: View, val picture: ImageView, val fill: View, val well: View, val title: TextView,
                        val folder: TextView, val artist: TextView) {
        var media: String? = null
    }

    fun attach(activity: AudioPlayerContainerActivity, coordinator: CoordinatorLayout) {
        if (!RdTvWindow.isOn(activity) || coordinator.findViewById<View>(R.id.rd_tv_mini) != null) return
        val ctx = activity
        val inter = ResourcesCompat.getFont(ctx, R.font.inter)
        val accent = UiTools.getColorFromAttribute(ctx, androidx.appcompat.R.attr.colorPrimary)
        val card = LinearLayout(ctx).apply {
            id = R.id.rd_tv_mini
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            background = GradientDrawable().apply { cornerRadius = 14.dp.toFloat(); setColor(0xFF1B1B1B.toInt()) }
            elevation = 12.dp.toFloat()
            isFocusable = true
            isClickable = true
            contentDescription = ctx.getString(R.string.rd_mini_player)
            visibility = View.GONE
            setOnClickListener { open(activity) }
        }
        // the picture: the video's frame or the song's cover (radius 6, the 2 px #090909 line over it)
        val pictureBox = FrameLayout(ctx).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 6.dp.toFloat())
            }
            clipToOutline = true
            setBackgroundColor(0xFF000000.toInt())
        }
        val picture = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        pictureBox.addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pictureBox.addView(View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0); setStroke(2.dp, 0xFF090909.toInt()) }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(pictureBox, LinearLayout.LayoutParams(375.dp, 211.dp))
        // the seek well (.sh-mini-seek): 16, radius 6, #090909; the fill inset 2 in the accent's background
        val well = FrameLayout(ctx).apply { background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0xFF090909.toInt()) } }
        val fill = View(ctx).apply { background = GradientDrawable().apply { cornerRadius = 4.dp.toFloat(); setColor(UiTools.getColorFromAttribute(ctx, R.attr.rd_accent_bright)) } }
        well.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(2.dp, 2.dp, 2.dp, 2.dp) })
        card.addView(well, LinearLayout.LayoutParams(375.dp, 16.dp).apply { topMargin = 4.dp })
        fun text(size: Float, color: Int) = TextView(ctx).apply {
            typeface = inter
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
            setTextColor(color)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val title = text(16f, RdTvWindow.FG_2)
        card.addView(title, LinearLayout.LayoutParams(359.dp, 19.dp).apply { setMargins(8.dp, 23.dp, 8.dp, 0) })
        // the folder, the artist, the queue (.sh-mini-row: 32 high, 14 under the title)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun chip(icon: Int): TextView {
            row.addView(ImageView(ctx).apply { setImageResource(icon); setColorFilter(RdTvWindow.ICON) }, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginEnd = 8.dp })
            return text(16f, RdTvWindow.FG_2).also { row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 20.dp }) ; it.maxWidth = 120.dp }
        }
        val folder = chip(R.drawable.rd_tvd_folder)
        val artist = chip(R.drawable.rd_ic_artist)
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.rd_ic_queue)
            setColorFilter(RdTvWindow.ICON)
            setPadding(3.dp, 4.dp, 3.dp, 4.dp)
            contentDescription = ctx.getString(R.string.rd_bar_queue)
            isFocusable = true
            isClickable = true
            background = GradientDrawable().apply { cornerRadius = 4.dp.toFloat(); setColor(0) }
            onFocusChangeListener = View.OnFocusChangeListener { v, has -> (v.background as GradientDrawable).setColor(if (has) 0xFF222222.toInt() else 0) }
            setOnClickListener { open(activity) }
        }, LinearLayout.LayoutParams(30.dp, 32.dp))
        card.addView(row, LinearLayout.LayoutParams(359.dp, 32.dp).apply { setMargins(8.dp, 14.dp, 8.dp, 4.dp) })
        coordinator.addView(card, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, 6.dp, 6.dp)
        })
        val views = Views(card, picture, fill, well, title, folder, artist)
        // the playback followed twice a second while the library is on screen (no listener to keep in step);
        // nothing runs while it is behind another screen (the players) or the app is in the background
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (activity.isDestroyed) return
                update(activity, views)
                handler.postDelayed(this, 500L)
            }
        }
        activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = handler.removeCallbacks(tick)
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) = handler.removeCallbacks(tick)
        })
    }

    private fun open(activity: AudioPlayerContainerActivity) {
        val service = PlaybackService.instance ?: return
        if (service.isVideoPlaying || service.currentMediaWrapper?.type == MediaWrapper.TYPE_VIDEO) {
            service.currentMediaWrapper?.let { org.videolan.vlc.gui.video.VideoPlayerActivity.start(activity, it.uri) }
        } else {
            // the design's music player (Figma 4767:23726): the TV's own, drawn as the frame (tv_audio_player.xml)
            activity.startActivity(android.content.Intent().setClassName(activity, org.videolan.resources.TV_AUDIOPLAYER_ACTIVITY))
        }
    }

    private fun update(activity: AudioPlayerContainerActivity, v: Views) {
        val service = PlaybackService.instance
        val media = service?.currentMediaWrapper
        // shown while something plays and the music player is not open over the page
        val shown = media != null && !activity.isAudioPlayerExpanded
        if (v.card.visibility != (if (shown) View.VISIBLE else View.GONE)) v.card.visibility = if (shown) View.VISIBLE else View.GONE
        if (!shown || media == null) return
        val length = service.length.takeIf { it > 0 } ?: media.length
        val time = service.getTime()
        val full = v.well.width - 4.dp
        val w = if (length > 0 && full > 0) (full * time / length).toInt().coerceIn(0, full) else 0
        (v.fill.layoutParams as FrameLayout.LayoutParams).let { if (it.width != w) { it.width = w; v.fill.layoutParams = it } }
        val key = media.uri.toString()
        if (v.media == key) return
        v.media = key
        v.title.text = media.title
        v.folder.text = media.uri.path?.let { java.io.File(it).parentFile?.name }.orEmpty()
        v.artist.text = media.artistName?.takeIf { it.isNotBlank() } ?: media.albumName?.takeIf { it.isNotBlank() } ?: v.folder.text
        v.picture.setImageDrawable(null)
        activity.lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                runCatching {
                    if (media.type == MediaWrapper.TYPE_VIDEO) org.videolan.vlc.util.ThumbnailsProvider.getMediaThumbnail(media, 375.dp)
                    else AudioUtil.readCoverBitmap(Uri.decode(media.artworkMrl), 375.dp)
                }.getOrNull()
            }
            if (v.media == key) v.picture.setImageBitmap(bitmap)
        }
    }
}
