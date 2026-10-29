/*
 * VLC Redesign: the desktop's Normalize loudness and Night mode (Settings > Audio > Loudness) on Android.
 *
 * On the desktop they are VLC's normvol and compressor audio filters. libvlc 3 cannot keep them: a media player's
 * "audio-filter" variable does not inherit the instance's --audio-filter, and libvlc_media_player_set_equalizer()
 * (called before every media) rewrites it to "equalizer" or "", so there is no way to add a filter from the
 * Android libvlc API without rebuilding libvlc. Instead, Android's DynamicsProcessing effect (API 28) is attached
 * to the audio session VLC's AudioTrack plays in (VLCOptions.audiotrackSessionId): a compressor on every band and
 * a limiter, after VLC's own chain (equalizer included). Night mode uses the desktop's compressor values (ratio 8,
 * threshold -30 dB, makeup gain 9 dB); Normalize a gentler compressor whose makeup gain is the Target level.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.videolan.resources.VLCOptions
import org.videolan.tools.KEY_AUDIO_DIGITAL_OUTPUT
import org.videolan.tools.Settings

object LoudnessEffects {
    private const val TAG = "VLC/Loudness"
    val isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    private var effect: Any? = null
    private var session = 0
    /** What is applied now, for the log and the tests */
    var state = "off"
        private set

    fun apply(context: Context) {
        if (!isSupported) return
        val prefs = Settings.getInstance(context)
        val normalize = prefs.getBoolean("rd_normalize", false)
        val night = prefs.getBoolean("rd_night_mode", false)
        val level = prefs.getInt("rd_norm_level", 4).coerceIn(1, 10)
        val passthrough = prefs.getBoolean(KEY_AUDIO_DIGITAL_OUTPUT, false)
        val sessionId = VLCOptions.audiotrackSessionId
        if ((!normalize && !night) || passthrough || sessionId == 0) {
            release()
            return
        }
        try {
            applyDynamics(sessionId, normalize, night, level)
        } catch (e: Throwable) {
            Log.w(TAG, "DynamicsProcessing failed: ${e.message}")
            release()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyDynamics(sessionId: Int, normalize: Boolean, night: Boolean, level: Int) {
        if (effect == null || session != sessionId) {
            release()
            // the default configuration: its multi-band compressor and limiter, for every channel of the session
            effect = DynamicsProcessing(0, sessionId, null)
            session = sessionId
        }
        val dp = effect as DynamicsProcessing
        // Night mode: the desktop's compressor (ratio 8, threshold -30 dB, makeup 9 dB); Normalize: ratio 3 over
        // -24 dB, its makeup gain from the Target level (1..10 -> 1.5..15 dB); both: the night compressor and the
        // larger makeup gain
        val ratio = if (night) 8f else 3f
        val threshold = if (night) -30f else -24f
        val makeup = maxOf(if (night) 9f else 0f, if (normalize) level * 1.5f else 0f)
        val bands = dp.getMbcByChannelIndex(0).bandCount
        dp.setMbcAllChannelsTo(DynamicsProcessing.Mbc(true, true, bands))
        for (b in 0 until bands) {
            val cutoff = dp.getMbcBandByChannelIndex(0, b).cutoffFrequency
            dp.setMbcBandAllChannelsTo(b, DynamicsProcessing.MbcBand(true, cutoff, if (night) 3f else 10f, if (night) 80f else 300f,
                    ratio, threshold, 3f, -90f, 1f, 0f, makeup))
        }
        dp.setPreEqAllChannelsTo(DynamicsProcessing.Eq(true, false, dp.getPreEqByChannelIndex(0).bandCount))
        dp.setPostEqAllChannelsTo(DynamicsProcessing.Eq(true, false, dp.getPostEqByChannelIndex(0).bandCount))
        // a limiter after it, so the makeup gain never clips
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        dp.enabled = true
        state = "on session $sessionId: ratio $ratio threshold $threshold makeup $makeup bands $bands"
        Log.i(TAG, "loudness $state (normalize=$normalize night=$night level=$level)")
    }

    fun release() {
        if (effect != null && isSupported) {
            try {
                (effect as DynamicsProcessing).release()
            } catch (_: Throwable) {
            }
            Log.i(TAG, "loudness off")
        }
        effect = null
        session = 0
        state = "off"
    }
}
