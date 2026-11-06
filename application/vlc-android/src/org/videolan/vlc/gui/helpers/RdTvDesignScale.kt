/*
 * VLC Redesign: the TV layout as the design's own pages (the tablet layout: the sidebar, Home, the library, Settings,
 * the players) at the design's size on a TV, as the TV app draws them on its 1920 x 1080 page: 1 design px = 1 dp
 * on 1920 px across, whatever the TV's own density (a 1080p Android TV is xhdpi, 960 dp across; a 4K one the same).
 * The activities of the TV layout take this configuration (density, size in dp) before their resources are made,
 * so the tablet's resources (sw600dp, the sidebar from 900 dp) and its sizes apply as on a 1920 px wide window.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.helpers

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.videolan.tools.Settings
import org.videolan.tools.rdDensityOverride

/** The design's page across, in px: 1 dp each at the TV's scale. */
private const val DESIGN_WIDTH = 1920

/** This context at the design's scale when the TV layout is on, else unchanged. */
fun Context.rdTvDesignScale(): Context {
    if (!Settings.showTvUi) {
        rdDensityOverride = null
        return this
    }
    // the whole screen (the bars are hidden in the TV layout), not the window less a tablet's navigation bar
    val metrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(it)
    }
    val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
    val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
    if (longSide <= 0) return this
    val dpi = (DisplayMetrics.DENSITY_DEFAULT * longSide / DESIGN_WIDTH).coerceAtLeast(DisplayMetrics.DENSITY_LOW)
    val config = Configuration(resources.configuration)
    val landscape = config.orientation != Configuration.ORIENTATION_PORTRAIT
    config.densityDpi = dpi
    config.screenWidthDp = (if (landscape) longSide else shortSide) * DisplayMetrics.DENSITY_DEFAULT / dpi
    config.screenHeightDp = (if (landscape) shortSide else longSide) * DisplayMetrics.DENSITY_DEFAULT / dpi
    config.smallestScreenWidthDp = shortSide * DisplayMetrics.DENSITY_DEFAULT / dpi
    // the sizes computed in code (Int.dp) at the same scale as the resources
    rdDensityOverride = dpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT
    return createConfigurationContext(config)
}

/** The TV layout over the whole screen, as on a TV: a tablet's status and navigation bars hidden, back with a swipe. */
fun Activity.rdTvFullScreenIfTv() {
    if (!Settings.showTvUi) return
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}
