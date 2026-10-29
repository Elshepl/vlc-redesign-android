/*
 * VLC Redesign: the effects of the desktop's Effects dialog (Audio and Video tabs) that VLC for Android can apply.
 *
 * Video: libvlc 3 has the adjust, gradfun, invert, sepia, transform, rotate, anaglyph, gaussianblur, antiflicker
 * and hqdn3d filters (not sharpen, grain, posterize, gradient, mirror, puzzle, erase, magnify, motion blur,
 * psychedelic, wave, ripple, motion detect: not built). libvlc 3's Android API cannot change a filter of a playing video, so they are options of the
 * media (":video-filter=..."), the media is opened again where it was when they change, and a video with filters is
 * decoded without direct rendering (the filters need the pictures in memory).
 *
 * Audio: libvlc 3 cannot add an audio filter (libvlc_media_player_set_equalizer() rewrites the player's whole
 * "audio-filter" list), so the compressor, spatializer and stereo widener are Android effects on VLC's audio session
 * (see LoudnessEffects): the parameters the Android effect has, under the desktop's names.
 *
 * Every value is kept in the app's preferences: "rd_fx_<filter>" (on/off) and "rd_fx_<option>" (a number, or a
 * text for a list).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.resources

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale

object RdEffects {

    /** A setting of a filter: a slider from [min] to [max] (shown times [scale], with [unit]), or a list of [choices]. */
    class Param(
        val option: String,
        val label: Int,
        val min: Float = 0f,
        val max: Float = 1f,
        val def: Float = 0f,
        val step: Float = 0f,
        val unit: Int = 0,
        val scale: Float = 1f,
        val choices: List<Pair<String, Int>>? = null,
        val defChoice: String = ""
    ) {
        val key get() = "rd_fx_$option"
    }

    class Filter(val id: String, val title: Int, val params: List<Param> = emptyList(), val video: Boolean = true) {
        val key get() = "rd_fx_$id"
    }

    // ---- Audio (Android effects on VLC's session) ----
    val compressor = Filter("compressor", R.string.rd_fx_compressor, listOf(
            Param("compressor-attack", R.string.rd_fx_attack, 1.5f, 400f, 25f, 0.5f, R.string.rd_fx_unit_ms),
            Param("compressor-release", R.string.rd_fx_release, 2f, 800f, 100f, 1f, R.string.rd_fx_unit_ms),
            Param("compressor-threshold", R.string.rd_fx_threshold, -30f, 0f, -11f, 0.5f, R.string.rd_fx_unit_db),
            Param("compressor-ratio", R.string.rd_fx_ratio, 1f, 20f, 4f, 0.5f, R.string.rd_fx_unit_ratio),
            Param("compressor-knee", R.string.rd_fx_knee, 1f, 10f, 5f, 0.5f, R.string.rd_fx_unit_db),
            Param("compressor-makeup-gain", R.string.rd_fx_makeup, 0f, 24f, 7f, 0.5f, R.string.rd_fx_unit_db)
    ), video = false)
    val spatializer = Filter("spatializer", R.string.rd_fx_spatializer, listOf(
            Param("spatializer-roomsize", R.string.rd_fx_size, 0f, 1.1f, 0.85f, 0.01f, scale = 10f),
            Param("spatializer-width", R.string.rd_fx_width, 0f, 1f, 1f, 0.01f, scale = 10f),
            Param("spatializer-wet", R.string.rd_fx_wet, 0f, 1f, 0.4f, 0.01f, scale = 10f),
            Param("spatializer-damp", R.string.rd_fx_damp, 0f, 1f, 0.5f, 0.01f, scale = 10f)
    ), video = false)
    val stereoWiden = Filter("stereo_widen", R.string.rd_fx_stereo_widen, listOf(
            Param("stereowiden-width", R.string.rd_fx_width, 0f, 1f, 0.5f, 0.01f, R.string.rd_fx_unit_percent, scale = 100f)
    ), video = false)
    val audio = listOf(compressor, spatializer, stereoWiden)

    // ---- Video (libvlc 3 filters, media options) ----
    private val transformTypes = listOf("90" to R.string.rd_fx_rotate_90, "180" to R.string.rd_fx_rotate_180,
            "270" to R.string.rd_fx_rotate_270, "hflip" to R.string.rd_fx_flip_h, "vflip" to R.string.rd_fx_flip_v,
            "transpose" to R.string.rd_fx_transpose, "antitranspose" to R.string.rd_fx_antitranspose)
    private val anaglyphSchemes = listOf("red-green" to R.string.rd_fx_anaglyph_rg, "red-blue" to R.string.rd_fx_anaglyph_rb,
            "red-cyan" to R.string.rd_fx_anaglyph_rc, "trioscopic" to R.string.rd_fx_anaglyph_gm, "magenta-cyan" to R.string.rd_fx_anaglyph_mc)

    /** the marq module's colours (its list on the desktop), as 0xRRGGBB */
    private val colours = listOf("0xFF0000" to R.string.rd_fx_red, "0xFFFF00" to R.string.rd_fx_yellow, "0x00FF00" to R.string.rd_fx_lime,
            "0x008000" to R.string.rd_fx_green, "0x00FFFF" to R.string.rd_fx_aqua, "0x0000FF" to R.string.rd_fx_blue,
            "0xFF00FF" to R.string.rd_fx_fuchsia, "0xFFFFFF" to R.string.rd_fx_white, "0x808080" to R.string.rd_fx_gray, "0x000000" to R.string.rd_fx_black)

    val essential = listOf(
            Filter("adjust", R.string.rd_fx_image_adjust, listOf(
                    Param("hue", R.string.rd_fx_hue, -180f, 180f, 0f, 1f),
                    Param("brightness", R.string.rd_fx_brightness, 0f, 2f, 1f, 0.01f),
                    Param("contrast", R.string.rd_fx_contrast, 0f, 2f, 1f, 0.01f),
                    Param("saturation", R.string.rd_fx_saturation, 0f, 3f, 1f, 0.01f),
                    Param("gamma", R.string.rd_fx_gamma, 0.01f, 10f, 1f, 0.01f))),
            Filter("gradfun", R.string.rd_fx_gradfun, listOf(
                    Param("gradfun-radius", R.string.rd_fx_gradfun, 4f, 32f, 16f, 1f)))
    )
    /** the desktop's Crop panel (VLC 4's crop-top...): libvlc 3's croppadd filter, in pixels of the video */
    val crop = listOf(
            Filter("croppadd", R.string.rd_fx_crop, listOf(
                    Param("croppadd-croptop", R.string.rd_fx_top, 0f, 540f, 0f, 1f, R.string.rd_fx_unit_px),
                    Param("croppadd-cropbottom", R.string.rd_fx_bottom, 0f, 540f, 0f, 1f, R.string.rd_fx_unit_px),
                    Param("croppadd-cropleft", R.string.rd_fx_left, 0f, 960f, 0f, 1f, R.string.rd_fx_unit_px),
                    Param("croppadd-cropright", R.string.rd_fx_right, 0f, 960f, 0f, 1f, R.string.rd_fx_unit_px)))
    )
    val colors = listOf(
            Filter("extract", R.string.rd_fx_extract, listOf(
                    Param("extract-component", R.string.rd_fx_color, choices = colours, defChoice = "0xFF0000"))),
            Filter("colorthres", R.string.rd_fx_colorthres, listOf(
                    Param("colorthres-color", R.string.rd_fx_color, choices = colours, defChoice = "0xFF0000"),
                    Param("colorthres-saturationthres", R.string.rd_fx_saturation, 0f, 100f, 20f, 1f),
                    Param("colorthres-similaritythres", R.string.rd_fx_similarity, 0f, 100f, 15f, 1f))),
            Filter("invert", R.string.rd_fx_invert),
            Filter("sepia", R.string.rd_fx_sepia, listOf(Param("sepia-intensity", R.string.rd_fx_sepia, 0f, 255f, 120f, 1f)))
    )
    val geometry = listOf(
            Filter("transform", R.string.rd_fx_transform, listOf(
                    Param("transform-type", R.string.rd_fx_transform_type, choices = transformTypes, defChoice = "90"))),
            Filter("rotate", R.string.rd_fx_rotate, listOf(Param("rotate-angle", R.string.rd_fx_angle, 0f, 359f, 30f, 1f)))
    )
    /*
     * The desktop's Overlay section (Add Logo, Add Text, Logo Erase) is not possible with libvlc 3 on Android:
     * the media player creates its own "logo-file", "marq-*" and "sub-source" variables (for libvlc's logo and
     * marquee API, which VLC for Android's libvlc does not expose), so an option of libvlc never reaches them
     * (checked in the -vv log: "no logo file specified", no sub source loaded); erase is not built.
     */
    val advanced = listOf(
            Filter("anaglyph", R.string.rd_fx_anaglyph, listOf(
                    Param("anaglyph-scheme", R.string.rd_fx_color_scheme, choices = anaglyphSchemes, defChoice = "red-cyan"))),
            Filter("gaussianblur", R.string.rd_fx_blur, listOf(Param("gaussianblur-sigma", R.string.rd_fx_blur_sigma, 0.01f, 10f, 2f, 0.01f))),
            Filter("antiflicker", R.string.rd_fx_antiflicker, listOf(
                    Param("antiflicker-window-size", R.string.rd_fx_window_size, 0f, 100f, 10f, 1f),
                    Param("antiflicker-softening-size", R.string.rd_fx_softening, 0f, 30f, 10f, 1f))),
            Filter("hqdn3d", R.string.rd_fx_denoiser, listOf(
                    Param("hqdn3d-luma-spat", R.string.rd_fx_luma_spat, 0f, 254f, 4f, 0.5f),
                    Param("hqdn3d-chroma-spat", R.string.rd_fx_chroma_spat, 0f, 254f, 3f, 0.5f),
                    Param("hqdn3d-luma-temp", R.string.rd_fx_luma_temp, 0f, 254f, 6f, 0.5f),
                    Param("hqdn3d-chroma-temp", R.string.rd_fx_chroma_temp, 0f, 254f, 4.5f, 0.5f)))
    )
    /** the desktop's sections that libvlc 3 can apply, in its order (Overlay: see above) */
    val videoSections = listOf(R.string.rd_fx_essential to essential, R.string.rd_fx_crop to crop, R.string.rd_fx_colors to colors,
            R.string.rd_fx_geometry to geometry, R.string.rd_fx_advanced to advanced)
    val video get() = essential + crop + colors + geometry + advanced

    fun isOn(prefs: SharedPreferences, f: Filter) = prefs.getBoolean(f.key, false)

    fun value(prefs: SharedPreferences, p: Param): Float = when (val v = prefs.all[p.key]) {
        is Float -> v
        is Int -> v.toFloat()
        is String -> v.toFloatOrNull() ?: p.def
        else -> p.def
    }.coerceIn(p.min, p.max)

    fun choice(prefs: SharedPreferences, p: Param): String =
            prefs.getString(p.key, p.defChoice)?.takeIf { v -> p.choices?.any { it.first == v } == true } ?: p.defChoice

    /** The settings of [f] back to their defaults (its on/off kept, as the desktop's Reset). */
    fun reset(prefs: SharedPreferences, f: Filter) = prefs.edit { f.params.forEach { remove(it.key) } }

    fun anyVideo(prefs: SharedPreferences) = video.any { isOn(prefs, it) }

    /**
     * The libvlc options of the video effects that are on: the filters (with the postproc of Settings > Performance,
     * which one --video-filter has to carry as well) and their settings. libvlc 3 reads them when it starts (a media's
     * own ":video-filter" never reaches the video output), so libvlc is started again when they change.
     */
    fun videoOptions(prefs: SharedPreferences, postproc: Boolean): List<String> {
        val on = video.filter { isOn(prefs, it) }
        if (on.isEmpty()) return if (postproc) listOf("--video-filter=postproc") else emptyList()
        val names = on.map { it.id }.toMutableList()
        if (postproc) names.add(0, "postproc")
        val out = mutableListOf("--video-filter=" + names.joinToString(":"))
        for (f in on) for (p in f.params) {
            out.add(if (p.choices != null) "--${p.option}=${choice(prefs, p)}"
                    else "--${p.option}=" + String.format(Locale.US, if (p.step >= 1f) "%.0f" else "%.3f", value(prefs, p)))
        }
        return out
    }

    /** The media options of a video with effects: decoded without direct rendering (the filters need the pictures) */
    fun videoMediaOptions(prefs: SharedPreferences) = if (anyVideo(prefs)) listOf(":no-mediacodec-dr", ":no-omxil-dr") else emptyList()
}
