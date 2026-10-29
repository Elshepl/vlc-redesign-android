/*
 * VLC Redesign: the casting screen of the video player (player_remote_control.xml), the desktop's casting card:
 * where it plays and how (connecting: the icon breathes; failed: why, Try again / Play here), what plays there
 * (its artwork and title), the way back (Stop casting). A tap on the card plays or pauses, as the desktop's.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.video

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.cast.CastController
import org.videolan.vlc.cast.CastState
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.gui.helpers.rdAccent
import org.videolan.vlc.media.PlaylistManager

class CastingCardDelegate(private val player: VideoPlayerActivity) {

    private var card: View? = null
    private var breathing: ValueAnimator? = null
    private var artFor: String? = null

    fun bind() {
        val card = player.findViewById<View>(R.id.rd_casting_card) ?: return
        this.card = card
        val accent = player.rdAccent()
        card.findViewById<MaterialButton>(R.id.rd_casting_retry).apply {
            backgroundTintList = ColorStateList.valueOf(accent)
            setOnClickListener { CastController.retry() }
        }
        card.findViewById<MaterialButton>(R.id.rd_casting_play_here).setOnClickListener { CastController.playHere() }
        card.findViewById<MaterialButton>(R.id.rd_casting_stop).setOnClickListener { CastController.playHere() }
        card.setOnClickListener {
            if (CastState.state.value.status != CastState.Status.FAILED) player.togglePlayPause()
        }
        // (no wider than the desktop's card)
        card.post {
            val max = 440.dp
            if (card.width > max) card.layoutParams = card.layoutParams.apply { width = max }
        }
        CastState.live.observe(player) { update() }
        PlaylistManager.currentPlayedMedia.observe(player) { update() }
    }

    private fun update() {
        val card = card ?: return
        val state = CastState.state.value
        val accent = player.rdAccent()
        val failed = state.status == CastState.Status.FAILED
        card.isVisible = state.status != CastState.Status.OFF
        card.findViewById<TextView>(R.id.rd_casting_title).text = CastState.title(player, state)
        val mw = player.service?.currentMediaWrapper
        card.findViewById<TextView>(R.id.rd_casting_media).apply {
            // (a file without a title: its name)
            text = mw?.title?.takeIf { it.isNotBlank() } ?: mw?.fileName ?: ""
            isVisible = text.isNotEmpty()
        }
        card.findViewById<TextView>(R.id.rd_casting_hint).isVisible = !failed
        card.findViewById<TextView>(R.id.rd_casting_message).apply {
            isVisible = failed
            text = if (failed) CastState.message(player, state) else ""
            setTextColor(accent)
        }
        card.findViewById<View>(R.id.rd_casting_failed_buttons).isVisible = failed
        card.findViewById<View>(R.id.rd_casting_stop).isVisible = !failed
        val icon = card.findViewById<ImageView>(R.id.rd_casting_icon)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(if (failed) 0xFF8F8F8F.toInt() else accent))
        // connecting: it breathes until the device plays
        if (state.status == CastState.Status.CONNECTING) {
            if (breathing == null) breathing = ObjectAnimator.ofFloat(icon.parent as View, View.ALPHA, 1f, 0.35f).apply {
                duration = 700L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            breathing?.cancel()
            breathing = null
            (icon.parent as View).alpha = 1f
        }
        loadArt(card, mw)
    }

    /** the artwork of what plays there, behind the icon (none: the icon alone) */
    private fun loadArt(card: View, mw: MediaWrapper?) {
        val art = card.findViewById<ImageView>(R.id.rd_casting_art)
        val mrl = mw?.artworkMrl
        if (mrl == artFor) return
        artFor = mrl
        if (mrl.isNullOrEmpty()) {
            art.isVisible = false
            card.findViewById<ImageView>(R.id.rd_casting_icon).isVisible = true
            return
        }
        player.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { AudioUtil.readCoverBitmap(android.net.Uri.decode(mrl), 96.dp) }.getOrNull() }
            if (artFor != mrl) return@launch
            art.setImageBitmap(bitmap)
            art.isVisible = bitmap != null
            art.clipToOutline = true
            art.background = GradientDrawable().apply { cornerRadius = 10.dp.toFloat(); setColor(0xFF1E1E1E.toInt()) }
            // the artwork shows what plays: the icon over it would hide it
            card.findViewById<ImageView>(R.id.rd_casting_icon).isVisible = bitmap == null
        }
    }

    /** the device's volume ([pct] percent), shown a moment in the card after a volume key */
    fun showVolume(pct: Int) {
        val card = card ?: return
        val row = card.findViewById<View>(R.id.rd_casting_volume)
        card.findViewById<android.widget.ProgressBar>(R.id.rd_casting_volume_bar).progress = pct
        card.findViewById<TextView>(R.id.rd_casting_volume_text).text = java.text.NumberFormat.getPercentInstance().format(pct / 100.0)
        row.contentDescription = player.getString(R.string.volume) + " " + pct
        row.isVisible = true
        row.removeCallbacks(hideVolume)
        row.postDelayed(hideVolume, 2000L)
    }

    private val hideVolume = Runnable { card?.findViewById<View>(R.id.rd_casting_volume)?.isVisible = false }

    fun release() {
        breathing?.cancel()
        breathing = null
    }
}
