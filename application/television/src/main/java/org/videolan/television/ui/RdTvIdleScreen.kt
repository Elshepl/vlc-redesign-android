/*
 * VLC Redesign: the idle screen of the TV layout (the TV app's idle.js). When nothing has been pressed for a while
 * (Settings > Interface > Idle screen, 3 minutes by default, or off), the designer's cat (IdleSceneView) becomes
 * the background of the page on screen: right under its rows, cards and lists, so they, every menu and every
 * dialog stay on top of it; never in the players. The page's words stay easy to read over it: the room darkens
 * toward its top and the texts get a soft dark edge while it shows. It moves a few pixels every minute, so nothing
 * stays burnt in one place. Any key takes it away, and the key works as usual.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.television.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import org.videolan.tools.Settings
import org.videolan.vlc.gui.view.IdleSceneView
import java.util.WeakHashMap

/** Settings > Interface > Idle screen: the minutes without a key before the cat shows, 0 for never. */
const val KEY_IDLE_SCREEN = "rd_idle_screen"

class RdTvIdleScreen(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private var scene: IdleSceneView? = null
    private var page: View? = null
    // the page's full-size layers that had a background of their own, with it
    private val layers = ArrayList<Pair<View, Drawable>>()
    private var last = SystemClock.uptimeMillis()
    private var resumed = false
    // the texts given a dark edge, with the shadow they had
    private class Shadow(val radius: Float, val dx: Float, val dy: Float, val color: Int)
    private val edged = WeakHashMap<TextView, Shadow>()
    private val check = Runnable { tick() }
    private val newViews = ViewTreeObserver.OnGlobalLayoutListener { page?.let { edge(it) } }

    private fun minutes() = Settings.getInstance(activity).getString(KEY_IDLE_SCREEN, "3")?.toIntOrNull()?.coerceAtLeast(0) ?: 3

    val shown get() = scene != null

    fun onResume() {
        resumed = true
        last = SystemClock.uptimeMillis()
        schedule()
    }

    fun onPause() {
        resumed = false
        handler.removeCallbacks(check)
        hide()
    }

    /** a key, a touch: the idle time again, the cat goes (the page was on top of it all along) */
    fun onInput() {
        last = SystemClock.uptimeMillis()
        if (scene != null) hide()
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(check)
        if (!resumed) return
        if (scene != null) { handler.postDelayed(check, 60_000); return }
        val m = minutes()
        if (m > 0) handler.postDelayed(check, maxOf(1000L, last + m * 60_000L - SystemClock.uptimeMillis()))
    }

    private fun tick() {
        if (!resumed) return
        val s = scene
        if (s != null) {
            // a few pixels every minute, in a small circle (the first place is the design's own)
            s.drift = s.drift + 1
        } else if (minutes() > 0 && SystemClock.uptimeMillis() - last >= minutes() * 60_000L - 500) {
            // a menu or a dialog over the page has the keys: the cat waits for them to close
            if (activity.hasWindowFocus()) show() else last = SystemClock.uptimeMillis()
        }
        schedule()
    }

    private fun show() {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val pg = content.getChildAt(0) ?: return
        val s = IdleSceneView(activity).apply { shaded = true }
        // the page's sidebar (the headers of the TV layout) covers a side of the room: the cat sits in the middle of the rest
        content.findViewById<View>(androidx.leanback.R.id.browse_headers_dock)?.takeIf { it.isShown && it.width > 0 }?.let { dock ->
            val at = IntArray(2)
            val base = IntArray(2)
            dock.getLocationInWindow(at)
            content.getLocationInWindow(base)
            val left = at[0] - base[0]
            val right = left + dock.width
            if (left <= 0) s.catCentre = (right + content.width) / 2 else if (right >= content.width) s.catCentre = left / 2
        }
        content.addView(s, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scene = s
        page = pg
        // the page's own backgrounds (its full-size layers: the activity's, its fragment's) would cover the room:
        // away while the cat shows
        clearLayers(pg, pg.width, pg.height)
        edge(pg)
        pg.viewTreeObserver.addOnGlobalLayoutListener(newViews)
    }

    private fun hide() {
        val s = scene ?: return
        scene = null
        (s.parent as? ViewGroup)?.removeView(s)
        page?.let { pg ->
            if (pg.viewTreeObserver.isAlive) pg.viewTreeObserver.removeOnGlobalLayoutListener(newViews)
        }
        for ((v, bg) in layers) v.background = bg
        layers.clear()
        for ((t, sh) in edged) t.setShadowLayer(sh.radius, sh.dx, sh.dy, sh.color)
        edged.clear()
        page = null
    }

    private fun clearLayers(v: View, w: Int, h: Int) {
        if (v.width < w * 9 / 10 || v.height < h * 9 / 10) return
        v.background?.let { layers.add(v to it); v.background = null }
        if (v is ViewGroup) for (i in 0 until v.childCount) clearLayers(v.getChildAt(i), w, h)
    }

    /** a soft dark edge around every text of the page (the rows bound while the cat shows too) */
    private fun edge(v: View) {
        if (v is TextView) {
            if (!edged.containsKey(v)) {
                edged[v] = Shadow(v.shadowRadius, v.shadowDx, v.shadowDy, v.shadowColor)
                val d = v.resources.displayMetrics.density
                v.setShadowLayer(4f * d, 0f, 1f * d, EDGE)
            }
        } else if (v is ViewGroup) for (i in 0 until v.childCount) edge(v.getChildAt(i))
    }

    private companion object {
        const val EDGE = 0xF2000000.toInt()
    }
}
