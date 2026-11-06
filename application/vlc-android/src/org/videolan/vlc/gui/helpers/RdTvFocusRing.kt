/*
 * VLC Redesign: the D-pad focus of the TV layout as the TV app draws it (core.js .focus, the desktop's keyboard focus):
 * a 2 px line of FG/1 around whatever has the focus, on every page of the tablet layout, whatever the view's own
 * focused state shows (most of them were made for touch and show none, or a faint one).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.helpers

import android.app.Activity
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.Window
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R

object RdTvFocusRing {
    private val NONE = Any()

    fun attach(activity: Activity) = attach(activity.window)

    /** A dialog's or a sheet's window as well (its own view tree). */
    fun attach(window: Window?) {
        if (!Settings.showTvUi || window == null) return
        val decor = window.decorView
        if (decor.getTag(R.id.rd_tv_focus_listener) != null) return
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
            old?.let { clear(it) }
            new?.let { show(it) }
        }
        decor.setTag(R.id.rd_tv_focus_listener, listener)
        decor.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
        decor.findFocus()?.let { show(it) }
    }

    private fun ring(view: View) = GradientDrawable().apply {
        setColor(0)
        setStroke(2.dp, ContextCompat.getColor(view.context, R.color.rd_dark_fg_1))
        cornerRadius = 6.dp.toFloat()
    }

    private fun show(view: View) {
        if (view.getTag(R.id.rd_tv_focus_saved) != null) return
        // a text field shows its own caret and frame
        if (view is android.widget.EditText) return
        // a control that shows its focus itself (the player's play pill)
        if (view.getTag(R.id.rd_tv_focus_skip) == true) return
        // a list that has the focus itself (between its rows): its rows show the focus, not the whole list
        if (view is androidx.recyclerview.widget.RecyclerView) return
        view.setTag(R.id.rd_tv_focus_saved, view.foreground ?: NONE)
        view.foreground = ring(view)
    }

    private fun clear(view: View) {
        val saved = view.getTag(R.id.rd_tv_focus_saved) ?: return
        view.foreground = saved as? Drawable
        view.setTag(R.id.rd_tv_focus_saved, null)
    }
}
