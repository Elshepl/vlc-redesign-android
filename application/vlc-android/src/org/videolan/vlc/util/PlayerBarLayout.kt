/*
 * VLC Redesign: the layout of the video player's control bar, as the desktop's Customize Player
 * (ToolbarEditorDialog.qml, dialogs/toolbar/defaults.cpp): a catalogue of controls placed in a left, a center and
 * a right zone, one layout for a narrow window (portrait: two rows) and one for a wide one (landscape: one row),
 * in profiles. The five profiles of the desktop are built in; a built-in profile that was edited keeps its edit
 * until it is reset; profiles of the user can be added, renamed and deleted.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.tools.putSingle
import org.videolan.vlc.R

object PlayerBarLayout {
    const val KEY_PROFILES = "rd_bar_profiles"
    const val KEY_PROFILE = "rd_bar_profile"

    /** A control of the catalogue: [view] for the bar's own buttons, else [action] (an option of the player's menu). */
    class Control(val key: String, @StringRes val label: Int, @DrawableRes val icon: Int, val view: Int = 0, val action: String? = null)

    val CATALOGUE = listOf(
            Control("previous", R.string.previous, R.drawable.ic_player_previous, R.id.playlist_previous),
            Control("backward", R.string.rd_bar_backward, R.drawable.ic_player_rewind_10, R.id.player_overlay_rewind),
            Control("play", R.string.play, R.drawable.rd_ic_play, R.id.player_overlay_play),
            Control("forward", R.string.rd_bar_forward, R.drawable.ic_player_forward_10, R.id.player_overlay_forward),
            Control("next", R.string.next, R.drawable.ic_player_next, R.id.playlist_next),
            Control("tracks", R.string.tracks, R.drawable.ic_player_audiotrack, R.id.player_overlay_tracks),
            Control("rotate", R.string.rd_bar_rotate, R.drawable.ic_player_rotate, R.id.orientation_toggle),
            Control("aspect", R.string.aspect_ratio, R.drawable.ic_player_ratio, R.id.player_resize),
            Control("settings", R.string.rd_bar_settings, R.drawable.rd_ic_player_bar_settings, R.id.player_overlay_adv_function),
            Control("speed", R.string.playback_speed, R.drawable.rd_ic_speed, action = "speed"),
            Control("sleep", R.string.sleep_title, R.drawable.rd_ic_sleep, action = "sleep"),
            Control("jump", R.string.jump_to_time, R.drawable.rd_ic_duo_go_to, action = "jump"),
            Control("lock", R.string.lock, R.drawable.rd_ic_lock, action = "lock"),
            Control("repeat", R.string.repeat_title, R.drawable.rd_ic_repeat, action = "repeat"),
            Control("bookmarks", R.string.bookmarks, R.drawable.rd_ic_bookmark, action = "bookmarks"),
            Control("popup", R.string.ctx_pip_title, R.drawable.rd_ic_pip, action = "popup"),
            Control("equalizer", R.string.equalizer, R.drawable.rd_ic_equalizer, action = "equalizer"),
            Control("queue", R.string.rd_bar_queue, R.drawable.rd_ic_queue, action = "queue"),
            Control("volume", R.string.volume, R.drawable.rd_ic_volume, action = "volume"),
            Control("record", R.string.rd_record, R.drawable.rd_ic_duo_record, action = "record"),
    )

    fun control(key: String) = CATALOGUE.firstOrNull { it.key == key }

    /** The controls of a layout's three zones. */
    data class Zones(val left: List<String>, val center: List<String>, val right: List<String>) {
        val all get() = left + center + right
        fun encode() = listOf(left, center, right).joinToString("|") { it.joinToString(",") }

        companion object {
            fun decode(text: String): Zones {
                val parts = (text.split('|') + listOf("", "", "")).take(3).map { z ->
                    z.split(',').map { it.trim() }.filter { it.isNotEmpty() && control(it) != null }
                }
                // each control once, the play button always (the desktop's player has it in every profile)
                val seen = HashSet<String>()
                val zones = parts.map { z -> z.filter { seen.add(it) } }
                return Zones(zones[0], zones[1], zones[2])
            }
        }
    }

    class Profile(val name: String, val portrait: Zones, val landscape: Zones, val builtIn: Boolean) {
        fun toJson() = JSONObject().put("name", name).put("portrait", portrait.encode()).put("landscape", landscape.encode())
    }

    /** The desktop's profiles (defaults.cpp), laid out for a phone. */
    val BUILT_IN = listOf(
            Profile("Default", Zones.decode("tracks,rotate|previous,play,next|aspect,settings"), Zones.decode("previous,play,next||tracks,rotate,aspect,settings"), true),
            Profile("Minimalist Style", Zones.decode("|previous,play,next|settings"), Zones.decode("play||settings"), true),
            Profile("One-liner Style", Zones.decode("previous,play,next||tracks,settings"), Zones.decode("previous,play,next||tracks,settings"), true),
            Profile("Simplest Style", Zones.decode("|play|"), Zones.decode("play||"), true),
            Profile("Classic Style", Zones.decode("lock,tracks|previous,play,next|queue,settings"), Zones.decode("previous,play,next||speed,tracks,rotate,aspect,queue,settings"), true),
    )

    /** The desktop's default bar (defaults.cpp, Figma 247:445), the TV layout's Default */
    val TV_DEFAULT = Zones.decode("previous,play,next||volume,equalizer,tracks,settings,queue")

    @StringRes
    fun builtInLabel(name: String) = when (name) {
        "Default" -> R.string.rd_profile_default
        "Minimalist Style" -> R.string.rd_profile_minimalist
        "One-liner Style" -> R.string.rd_profile_one_liner
        "Simplest Style" -> R.string.rd_profile_simplest
        "Classic Style" -> R.string.rd_profile_classic
        else -> 0
    }

    /** The stored profiles: the user's, and the built-in ones that were edited (same name). */
    fun stored(prefs: SharedPreferences): List<Profile> = try {
        val array = JSONArray(prefs.getString(KEY_PROFILES, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val name = o.getString("name")
            Profile(name, Zones.decode(o.optString("portrait")), Zones.decode(o.optString("landscape")), BUILT_IN.any { it.name == name })
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Every profile, the built-in ones first (as edited), then the user's. */
    fun profiles(prefs: SharedPreferences): List<Profile> {
        val stored = stored(prefs)
        return BUILT_IN.map { b -> stored.firstOrNull { it.name == b.name } ?: b } + stored.filter { s -> BUILT_IN.none { it.name == s.name } }
    }

    fun current(prefs: SharedPreferences): Profile {
        val name = prefs.getString(KEY_PROFILE, "Default")
        val all = profiles(prefs)
        return all.firstOrNull { it.name == name } ?: all.first()
    }

    fun save(prefs: SharedPreferences, profiles: List<Profile>, selected: String) {
        // built-in profiles are stored only when they differ from their defaults
        val keep = profiles.filter { p -> val b = BUILT_IN.firstOrNull { it.name == p.name }; b == null || b.portrait != p.portrait || b.landscape != p.landscape }
        prefs.putSingle(KEY_PROFILES, JSONArray().apply { keep.forEach { put(it.toJson()) } }.toString())
        prefs.putSingle(KEY_PROFILE, selected)
    }

    /**
     * The zones the bar shows: the profile's layout for the window, with the jump buttons beside the play button
     * when the player's own "seek buttons" setting asks for them and the layout has none.
     */
    fun effective(prefs: SharedPreferences, wide: Boolean, seekButtons: Boolean, tv: Boolean = false): Zones {
        val p = current(prefs)
        var z = if (wide) p.landscape else p.portrait
        // the TV layout at the design's size: the Default profile is the desktop's own bar (Figma 247:445):
        // Previous Play Next, the timeline, then the volume, the equalizer, the tracks, the settings and the queue
        if (tv && p.builtIn && p.name == "Default" && stored(prefs).none { it.name == p.name }) z = TV_DEFAULT
        if (seekButtons && "backward" !in z.all && "forward" !in z.all) {
            fun around(list: List<String>) = list.flatMap { if (it == "play") listOf("backward", "play", "forward") else listOf(it) }
            z = Zones(around(z.left), around(z.center), around(z.right))
        }
        if ("play" !in z.all) z = if (wide) Zones(listOf("play") + z.left, z.center, z.right) else Zones(z.left, z.center + "play", z.right)
        return z
    }
}
