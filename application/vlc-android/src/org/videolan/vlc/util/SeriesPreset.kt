/*
 * VLC Redesign: the series preset (SubtitleSeriesPreset.qml of the desktop).
 *
 * The episodes of one series share their choices: the audio track (or none), the subtitle track (or none), the
 * subtitle delay and the subtitle size. A series is a local folder and, when the file name has an episode token
 * (S01E02), the name before it: two shows in one folder stay apart. Kept per series in the app's preferences
 * (rd_series_presets) as the desktop's JSON (version 2); a track is found again by its name, else by its language.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.core.content.edit
import org.json.JSONObject
import org.videolan.tools.Settings
import org.videolan.tools.SubtitleScale
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.gui.dialogs.adapters.VlcTrack
import java.io.File
import java.util.Locale

object SeriesPreset {
    private const val PREFS = "rd_series_presets"
    private val EPISODE = Regex("""^(.+?)[ ._-]+s\d{1,2}e\d{1,3}""", RegexOption.IGNORE_CASE)
    private val LANGUAGE = Regex("""\[([^\]]+)\]""")

    /** The series of a local file: "folder|name" (the name empty without an episode token), null for a stream */
    fun key(uri: Uri?): String? {
        if (uri?.scheme != "file") return null
        val file = File(uri.path ?: return null)
        val dir = file.parent ?: return null
        val series = EPISODE.find(file.name)?.groupValues?.get(1)?.replace(Regex("[._-]+"), " ")?.trim()?.lowercase(Locale.ROOT) ?: ""
        return "$dir|$series"
    }

    /** What the series is called: the name before the episode token, else the folder's name */
    fun label(key: String): String {
        val (dir, series) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return series.ifEmpty { File(dir).name }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun has(context: Context, key: String) = prefs(context).contains(key)

    fun remove(context: Context, key: String) = prefs(context).edit { remove(key) }

    /** Keeps the choices of the media playing for its series; false when it has none (a stream) */
    fun save(context: Context, service: PlaybackService): Boolean {
        val key = key(service.currentMediaWrapper?.uri) ?: return false
        val audio = service.audioTracks?.filter { it.getId() != "-1" } ?: emptyList()
        val spu = service.spuTracks?.filter { it.getId() != "-1" } ?: emptyList()
        val audioTrack = audio.firstOrNull { it.getId() == service.audioTrack }?.getName() ?: ""
        val subtitleTrack = spu.firstOrNull { it.getId() == service.spuTrack }?.getName() ?: ""
        val json = JSONObject()
                .put("version", 2)
                .put("audioTrack", audioTrack)
                .put("subtitleTrack", subtitleTrack)
                .put("audioOff", audio.isNotEmpty() && audioTrack.isEmpty())
                .put("subtitleOff", spu.isNotEmpty() && subtitleTrack.isEmpty())
                .put("subtitleDelayMS", service.spuDelay / 1000L)
                .put("subtitleSize", SubtitleScale.get(Settings.getInstance(context)))
        prefs(context).edit { putString(key, json.toString()) }
        return true
    }

    /** The result of [apply]: what to say (0: nothing), and the subtitle size to set (0: as it is) */
    class Applied(@StringRes val message: Int, val subtitleSize: Int)

    /** The choices of the series of the media playing, set on it; null when its series has none */
    fun apply(context: Context, service: PlaybackService): Applied? {
        val key = key(service.currentMediaWrapper?.uri) ?: return null
        val raw = prefs(context).getString(key, null) ?: return null
        val preset = try {
            JSONObject(raw).takeIf { it.optInt("version") in 1..2 } ?: return null
        } catch (e: Exception) {
            return null
        }
        val v2 = preset.optInt("version") == 2
        service.setSpuDelay(preset.optLong("subtitleDelayMS") * 1000L)
        val audioOk = choose(service.audioTracks, preset.optString("audioTrack"), v2 && preset.optBoolean("audioOff")) { service.setAudioTrack(it) }
        val subtitleOk = choose(service.spuTracks, preset.optString("subtitleTrack"), v2 && preset.optBoolean("subtitleOff")) { service.setSpuTrack(it) }
        val size = preset.optInt("subtitleSize", 0).takeIf { it > 0 && it != SubtitleScale.get(Settings.getInstance(context)) } ?: 0
        return Applied(if (audioOk && subtitleOk) R.string.rd_series_applied else R.string.rd_series_unavailable, size)
    }

    /** Selects the track called [name] (else one in the same language), or none when [off]; false if missing */
    private fun choose(tracks: Array<out VlcTrack>?, name: String, off: Boolean, select: (String) -> Unit): Boolean {
        val list = tracks ?: return name.isEmpty()
        if (off) {
            list.firstOrNull { it.getId() == "-1" }?.let { select(it.getId()) } ?: select("-1")
            return true
        }
        if (name.isEmpty()) return true
        val real = list.filter { it.getId() != "-1" }
        val language = LANGUAGE.find(name)?.groupValues?.get(1)
        val track = real.firstOrNull { it.getName() == name }
                ?: language?.let { l -> real.firstOrNull { LANGUAGE.find(it.getName())?.groupValues?.get(1) == l } }
                ?: return false
        select(track.getId())
        return true
    }
}
