/*
 * VLC Redesign: the desktop's Normalize loudness and Night mode (Settings > Audio > Loudness) and the audio effects
 * of its Effects dialog (Compressor, Spatializer, Stereo Widener) on Android.
 *
 * On the desktop they are VLC's normvol, compressor, spatializer and stereo_widen audio filters. libvlc 3 cannot
 * keep them: a media player's "audio-filter" variable does not inherit the instance's --audio-filter, and
 * libvlc_media_player_set_equalizer() (called before every media) rewrites it to "equalizer" or "", so there is no
 * way to add a filter from the Android libvlc API without rebuilding libvlc. Instead, Android's effects are attached
 * to the audio session VLC's AudioTrack plays in (VLCOptions.audiotrackSessionId), after VLC's own chain (equalizer
 * included):
 * - DynamicsProcessing (API 28): a compressor on every band and a limiter. The Effects dialog's Compressor gives its
 *   attack, release, threshold, ratio, knee and makeup gain (no RMS / peak: Android's compressor has none); else
 *   Night mode uses the desktop's compressor values (ratio 8, threshold -30 dB, makeup gain 9 dB), Normalize a
 *   gentler compressor whose makeup gain is the Target level.
 * - EnvironmentalReverb as an insert effect: the Spatializer (room size -> decay time, width -> diffusion,
 *   wet -> reverb level, damp -> high-frequency damping; the dry signal passes as it is: no Dry).
 * - Virtualizer: the Stereo Widener (its strength).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.videolan.resources.RdEffects
import org.videolan.resources.VLCOptions
import org.videolan.tools.KEY_AUDIO_DIGITAL_OUTPUT
import org.videolan.tools.Settings
import kotlin.math.log10
import kotlin.math.roundToInt

object LoudnessEffects {
    private const val TAG = "VLC/Loudness"
    val isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    private var effect: Any? = null
    private var reverb: EnvironmentalReverb? = null
    private var virtualizer: Virtualizer? = null
    private var session = 0
    /** What is applied now, for the log and the tests */
    var state = "off"
        private set

    /** Whether this device has the Android effect of one of the Effects dialog's audio effects */
    fun available(id: String): Boolean = when (id) {
        RdEffects.compressor.id -> isSupported
        RdEffects.spatializer.id -> hasEffect(AudioEffect.EFFECT_TYPE_ENV_REVERB)
        RdEffects.stereoWiden.id -> hasEffect(AudioEffect.EFFECT_TYPE_VIRTUALIZER)
        else -> false
    }

    private fun hasEffect(type: java.util.UUID) = try {
        AudioEffect.queryEffects()?.any { it.type == type } == true
    } catch (_: Throwable) {
        false
    }

    fun apply(context: Context) {
        val prefs = Settings.getInstance(context)
        val passthrough = prefs.getBoolean(KEY_AUDIO_DIGITAL_OUTPUT, false)
        val sessionId = VLCOptions.audiotrackSessionId
        if (passthrough || sessionId == 0) {
            release()
            return
        }
        if (session != sessionId) release()
        session = sessionId
        val parts = mutableListOf<String>()
        // the dynamics: the Effects dialog's compressor, else the loudness settings
        val compressor = RdEffects.isOn(prefs, RdEffects.compressor)
        val normalize = prefs.getBoolean("rd_normalize", false)
        val night = prefs.getBoolean("rd_night_mode", false)
        val level = prefs.getInt("rd_norm_level", 4).coerceIn(1, 10)
        if (isSupported && (compressor || normalize || night)) {
            try {
                parts.add(applyDynamics(sessionId, prefs, compressor, normalize, night, level))
            } catch (e: Throwable) {
                // an effect created on the session after it can take its control: a new one once
                Log.w(TAG, "DynamicsProcessing failed: ${e.message}, created again")
                releaseDynamics()
                try {
                    parts.add(applyDynamics(sessionId, prefs, compressor, normalize, night, level))
                } catch (e: Throwable) {
                    Log.w(TAG, "DynamicsProcessing failed: ${e.message}")
                    releaseDynamics()
                }
            }
        } else releaseDynamics()
        // the spatializer
        if (RdEffects.isOn(prefs, RdEffects.spatializer)) retry("EnvironmentalReverb", ::releaseReverb) {
            val r = reverb ?: EnvironmentalReverb(0, sessionId).also { reverb = it }
            val p = RdEffects.spatializer.params
            val size = RdEffects.value(prefs, p[0])
            val width = RdEffects.value(prefs, p[1])
            val wet = RdEffects.value(prefs, p[2])
            val damp = RdEffects.value(prefs, p[3])
            r.decayTime = (200 + size / 1.1f * 6800).roundToInt()
            r.diffusion = (width * 1000).roundToInt().toShort()
            r.density = 1000
            r.reverbLevel = (if (wet <= 0.001f) -9000 else (2000 * log10(wet)).roundToInt().coerceIn(-9000, 2000)).toShort()
            r.roomLevel = 0
            r.roomHFLevel = (-damp * 4000).roundToInt().toShort()
            r.decayHFRatio = (2000 - damp * 1900).roundToInt().toShort()
            r.enabled = true
            parts.add("spatializer decay ${r.decayTime} ms, diffusion ${r.diffusion}, reverb ${r.reverbLevel} mB, hf ${r.roomHFLevel} mB")
        } else releaseReverb()
        // the stereo widener
        if (RdEffects.isOn(prefs, RdEffects.stereoWiden)) retry("Virtualizer", ::releaseVirtualizer) {
            val v = virtualizer ?: Virtualizer(0, sessionId).also { virtualizer = it }
            val strength = (RdEffects.value(prefs, RdEffects.stereoWiden.params[0]) * 1000).roundToInt().toShort()
            if (v.strengthSupported) v.setStrength(strength)
            v.enabled = true
            parts.add("widener strength $strength (supported ${v.strengthSupported})")
        } else releaseVirtualizer()
        state = if (parts.isEmpty()) "off" else "on session $sessionId: " + parts.joinToString("; ")
        Log.i(TAG, "audio effects $state")
    }

    /** An effect created on the session after another can take the other's control: made again once */
    private inline fun retry(name: String, release: () -> Unit, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.w(TAG, "$name failed: ${e.message}, created again")
            release()
            try {
                block()
            } catch (e: Throwable) {
                Log.w(TAG, "$name failed: ${e.message}")
                release()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyDynamics(sessionId: Int, prefs: SharedPreferences, compressor: Boolean, normalize: Boolean, night: Boolean, level: Int): String {
        // the default configuration: its multi-band compressor and limiter, for every channel of the session
        val dp = (effect as? DynamicsProcessing) ?: DynamicsProcessing(0, sessionId, null).also { effect = it }
        val attack: Float
        val release: Float
        val ratio: Float
        val threshold: Float
        var knee = 3f
        val makeup: Float
        if (compressor) {
            val p = RdEffects.compressor.params
            attack = RdEffects.value(prefs, p[0])
            release = RdEffects.value(prefs, p[1])
            threshold = RdEffects.value(prefs, p[2])
            ratio = RdEffects.value(prefs, p[3])
            knee = RdEffects.value(prefs, p[4])
            makeup = RdEffects.value(prefs, p[5])
        } else {
            // Night mode: the desktop's compressor (ratio 8, threshold -30 dB, makeup 9 dB); Normalize: ratio 3 over
            // -24 dB, its makeup gain from the Target level (1..10 -> 1.5..15 dB); both: the night compressor and the
            // larger makeup gain
            attack = if (night) 3f else 10f
            release = if (night) 80f else 300f
            ratio = if (night) 8f else 3f
            threshold = if (night) -30f else -24f
            makeup = maxOf(if (night) 9f else 0f, if (normalize) level * 1.5f else 0f)
        }
        val bands = dp.getMbcByChannelIndex(0).bandCount
        dp.setMbcAllChannelsTo(DynamicsProcessing.Mbc(true, true, bands))
        for (b in 0 until bands) {
            val cutoff = dp.getMbcBandByChannelIndex(0, b).cutoffFrequency
            dp.setMbcBandAllChannelsTo(b, DynamicsProcessing.MbcBand(true, cutoff, attack, release,
                    ratio, threshold, knee, -90f, 1f, 0f, makeup))
        }
        dp.setPreEqAllChannelsTo(DynamicsProcessing.Eq(true, false, dp.getPreEqByChannelIndex(0).bandCount))
        dp.setPostEqAllChannelsTo(DynamicsProcessing.Eq(true, false, dp.getPostEqByChannelIndex(0).bandCount))
        // a limiter after it, so the makeup gain never clips
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        dp.enabled = true
        return "compressor (${if (compressor) "effects" else "normalize=$normalize night=$night level=$level"}) attack $attack release $release ratio $ratio threshold $threshold knee $knee makeup $makeup bands $bands"
    }

    private fun releaseDynamics() {
        if (effect != null && isSupported) try {
            (effect as DynamicsProcessing).release()
        } catch (_: Throwable) {
        }
        effect = null
    }

    private fun releaseReverb() {
        try { reverb?.release() } catch (_: Throwable) {}
        reverb = null
    }

    private fun releaseVirtualizer() {
        try { virtualizer?.release() } catch (_: Throwable) {}
        virtualizer = null
    }

    fun release() {
        val any = effect != null || reverb != null || virtualizer != null
        releaseDynamics()
        releaseReverb()
        releaseVirtualizer()
        if (any) Log.i(TAG, "audio effects off")
        session = 0
        state = "off"
    }
}
