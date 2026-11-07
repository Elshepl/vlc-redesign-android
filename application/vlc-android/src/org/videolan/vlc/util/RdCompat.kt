/*
 * VLC Redesign: the calls of newer Android versions the redesign uses, with what older ones have instead.
 *
 * The app installs from Android 5.0 (API 21): every call of a later API goes through a version check, here or
 * where it is made (lint's NewApi lists them).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale

object RdCompat {
    /** The first language of a configuration (a list of them since Android 7) */
    @JvmStatic
    fun primaryLocale(config: Configuration): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) config.locales[0] ?: Locale.getDefault()
        else @Suppress("DEPRECATION") (config.locale ?: Locale.getDefault())

    /** A colour of the resources (Context.getColor is Android 6) */
    @JvmStatic
    fun color(context: Context, resId: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) context.getColor(resId)
        else @Suppress("DEPRECATION") context.resources.getColor(resId)

    /** A view's foreground: every view since Android 6, a FrameLayout before (null elsewhere) */
    @JvmStatic
    fun foreground(view: View): Drawable? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> view.foreground
        view is FrameLayout -> view.foreground
        else -> null
    }

    /** Sets a view's foreground where the version has one (see [foreground]); false where it can't */
    @JvmStatic
    fun setForeground(view: View, drawable: Drawable?): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> { view.foreground = drawable; true }
        view is FrameLayout -> { view.foreground = drawable; true }
        else -> false
    }

    /** A long word hyphenated and lines balanced (Android 6); before, the plain line breaks */
    @JvmStatic
    fun hyphenate(text: TextView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            text.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL
            text.breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        }
    }
}
