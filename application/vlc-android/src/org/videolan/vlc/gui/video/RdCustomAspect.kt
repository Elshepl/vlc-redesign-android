/*
 * VLC Redesign: custom aspect ratios, as the desktop's Settings > Video > Framing > Custom aspect ratios
 * (custom-aspect-ratios: a comma separated list, "21:9, 32:9, 1.85:1", added to the player's aspect ratio list).
 *
 * libvlc's VideoHelper only knows its fixed list (MediaPlayer.ScaleType), and sizes the video again on every
 * layout. A custom ratio is laid over it: the player keeps "Best fit" for VideoHelper, and after each layout the
 * video view is given the size of the custom ratio (or, while the video size is unknown and the view fills the
 * frame, libvlc's own aspect ratio is set).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import kotlin.math.ceil

class RdCustomAspect(private val player: VideoPlayerActivity) {

    companion object {
        /** the list of Settings > Video > Framing (the desktop's custom-aspect-ratios) */
        const val KEY_LIST = "rd_custom_aspect_ratios"
        /** the custom ratio chosen in the player ("" for one of the fixed list) */
        const val KEY_CURRENT = "rd_custom_aspect_current"
        private val FORMAT = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*[:/]\s*(\d+(?:[.,]\d+)?)\s*$""")

        /** "21:9, 32:9,1.85:1" -> ["21:9", "32:9", "1.85:1"]; anything else is left out */
        fun parse(text: String?): List<String> = text.orEmpty().split(',', ';').mapNotNull { token ->
            val m = FORMAT.find(token) ?: return@mapNotNull null
            val a = m.groupValues[1].replace(',', '.')
            val b = m.groupValues[2].replace(',', '.')
            if ((a.toDoubleOrNull() ?: 0.0) <= 0.0 || (b.toDoubleOrNull() ?: 0.0) <= 0.0) null else "$a:$b"
        }.distinct()

        fun ratioOf(value: String): Double {
            val (a, b) = value.split(':').map { it.toDouble() }
            return a / b
        }

        fun list(context: Context) = parse(Settings.getInstance(context).getString(KEY_LIST, ""))

        /** the custom ratio to start a video with, when it is still in the list */
        fun saved(context: Context): String? {
            val current = Settings.getInstance(context).getString(KEY_CURRENT, "").orEmpty()
            return if (current.isNotEmpty() && current in list(context)) current else null
        }
    }

    /** the custom ratio shown, or null for VideoHelper's own */
    var current: String? = null
        private set
    private var layout: VLCVideoLayout? = null
    private var appliedToLibvlc = false
    private val listener = ViewTreeObserver.OnGlobalLayoutListener { apply() }

    fun attach(videoLayout: VLCVideoLayout?) {
        if (layout === videoLayout) return
        detach()
        layout = videoLayout
        videoLayout?.viewTreeObserver?.addOnGlobalLayoutListener(listener)
    }

    fun detach() {
        layout?.viewTreeObserver?.let { if (it.isAlive) it.removeOnGlobalLayoutListener(listener) }
        layout = null
    }

    /** choose a custom ratio (null: back to the fixed list, whose choice the caller makes) */
    fun set(value: String?, persist: Boolean = true) {
        current = value
        appliedToLibvlc = false
        if (persist) Settings.getInstance(player).putSingle(KEY_CURRENT, value.orEmpty())
        val mp = player.service?.mediaplayer
        if (value != null && mp != null) {
            // VideoHelper lays out "Best fit", then the custom size goes over it
            mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            layout?.requestLayout()
        }
        apply()
    }

    private fun apply() {
        val value = current ?: return
        val frame = layout?.findViewById<View>(org.videolan.R.id.player_surface_frame) ?: return
        val video = frame.findViewById<View>(org.videolan.R.id.surface_video)
                ?: frame.findViewById(org.videolan.R.id.texture_video) ?: return
        val sw = frame.width
        val sh = frame.height
        if (sw <= 0 || sh <= 0) return
        val lp = video.layoutParams ?: return
        val ar = ratioOf(value)
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT && lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
            // the size of the video is not known yet (or an OpenGL output): libvlc places it with its aspect ratio
            if (!appliedToLibvlc) player.service?.mediaplayer?.let {
                it.aspectRatio = value
                it.scale = 0f
                appliedToLibvlc = true
            }
            return
        }
        appliedToLibvlc = false
        var dw = sw.toDouble()
        var dh = sh.toDouble()
        if (dw / dh < ar) dh = dw / ar else dw = dh * ar
        val w = ceil(dw).toInt()
        val h = ceil(dh).toInt()
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        video.layoutParams = lp
        frame.findViewById<View>(org.videolan.R.id.surface_subtitles)?.let { subs ->
            subs.layoutParams = subs.layoutParams.apply { width = w; height = h }
        }
    }
}
