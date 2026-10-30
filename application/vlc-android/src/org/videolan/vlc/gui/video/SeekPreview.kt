/*
 * VLC Redesign: the frame preview of the seek bar (SeekFramePreview.qml of the desktop, "Show thumbnail while
 * scrubbing").
 *
 * While the seek bar of the video player is dragged, a card above the finger shows the frame of the video at
 * that time and the time under it: the menu surface (#191919 at 95 %, radius 12) in the window border, the frame
 * inset by 10 with radius 3, the time in the muted text colour. Local files only (as the desktop: a preview opens
 * the media again): the frames come from the platform's retriever on one worker thread, the key frame nearest the
 * time, the last one asked for first; the previous frame stays while the next one decodes, and the frames already
 * seen are kept (by second) while the bar is dragged.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.medialibrary.Tools

class SeekPreview(private val activity: VideoPlayerActivity) {
    private var card: LinearLayout? = null
    private var image: ImageView? = null
    private var label: TextView? = null
    private var uri: Uri? = null
    private var retriever: MediaMetadataRetriever? = null
    private val frames = LruCache<Long, Bitmap>(40)
    @Volatile private var wanted = -1L
    private var worker: Job? = null
    // one thread for the retriever: it is not made for several at once
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Shows the card for the media playing when it is a local video; false else (the time OSD then) */
    fun start(media: Uri?, hasVideo: Boolean): Boolean {
        if (media == null || !hasVideo || (media.scheme != "file" && media.scheme != "content")) return false
        if (media != uri) {
            frames.evictAll()
            closeRetriever()
            uri = media
        }
        ensureCard()
        return true
    }

    fun move(bar: SeekBar, time: Long) {
        val c = card ?: return
        label?.text = Tools.millisToString(time)
        val root = c.parent as? View ?: return
        // above the thumb, inside the window
        val loc = IntArray(2)
        bar.getLocationInWindow(loc)
        val track = bar.width - bar.paddingLeft - bar.paddingRight
        val x = loc[0] + bar.paddingLeft + if (bar.max > 0) track * bar.progress / bar.max else 0
        c.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = c.measuredWidth
        val h = c.measuredHeight
        c.translationX = (x - w / 2).coerceIn(8.dp, maxOf(8.dp, root.width - w - 8.dp)).toFloat()
        c.translationY = maxOf(8.dp, loc[1] - h - 12.dp).toFloat()
        c.visibility = View.VISIBLE
        frames.get(time / 1000)?.let { image?.setImageBitmap(it) }
        request(time)
    }

    fun stop() {
        card?.visibility = View.GONE
        wanted = -1L
    }

    fun release() {
        stop()
        worker?.cancel()
        // (the activity's scope is gone when it is destroyed)
        kotlinx.coroutines.CoroutineScope(dispatcher).launch { closeRetriever() }
        (card?.parent as? ViewGroup)?.removeView(card)
        card = null
        frames.evictAll()
    }

    private fun request(time: Long) {
        wanted = time
        if (worker?.isActive == true) return
        val media = uri ?: return
        val width = activity.resources.getDimensionPixelSize(R.dimen.rd_seek_preview_width)
        worker = activity.lifecycleScope.launch {
            while (wanted >= 0) {
                val t = wanted
                val frame = withContext(dispatcher) { frameAt(media, t, width) }
                if (media != uri) return@launch
                if (frame != null) {
                    frames.put(t / 1000, frame)
                    if (card?.visibility == View.VISIBLE) image?.setImageBitmap(frame)
                }
                if (wanted == t) break
            }
        }
    }

    private fun frameAt(media: Uri, time: Long, width: Int): Bitmap? = try {
        val r = retriever ?: MediaMetadataRetriever().also {
            if (media.scheme == "file") it.setDataSource(media.path) else it.setDataSource(activity.applicationContext, media)
            retriever = it
        }
        val us = time * 1000
        if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, width)
        else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
            if (b.width <= width) b else Bitmap.createScaledBitmap(b, width, b.height * width / b.width, true)
        }
    } catch (e: Exception) {
        null
    }

    private fun closeRetriever() {
        try { retriever?.release() } catch (_: Exception) {}
        retriever = null
    }

    private fun ensureCard() {
        if (card != null) return
        val ctx = activity
        val root = activity.window.decorView as? ViewGroup ?: return
        val width = ctx.resources.getDimensionPixelSize(R.dimen.rd_seek_preview_width)
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(10.dp, 10.dp, 10.dp, 8.dp)
            background = GradientDrawable().apply {
                cornerRadius = 12.dp.toFloat()
                setColor(0xF2191919.toInt())
                setStroke(1, ContextCompat.getColor(ctx, R.color.rd_dark_window_border_token))
            }
            elevation = 6.dp.toFloat()
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            id = R.id.rd_seek_preview
        }
        image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = 3.dp.toFloat(); setColor(0xFF0E0E0E.toInt()) }
            clipToOutline = true
        }
        c.addView(image, LinearLayout.LayoutParams(width, width * 9 / 16))
        label = TextView(ctx).apply {
            setTextColor(ContextCompat.getColor(ctx, R.color.rd_dark_fg_a2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = ResourcesCompat.getFont(ctx, R.font.inter)
            fontFeatureSettings = "tnum"
            textDirection = View.TEXT_DIRECTION_LTR
        }
        c.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp })
        root.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        card = c
    }
}
