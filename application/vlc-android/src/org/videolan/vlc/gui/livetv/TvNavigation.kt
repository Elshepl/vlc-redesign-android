/*
 * VLC Redesign: the pages of the bottom bar and the rail in the order the user gave them (the desktop's
 * SidebarItems.qml and Edit Sidebar), with TV a page of its own while the Live TV add-on is on (off, its place
 * is kept for when it comes back). A bottom bar holds five entries: the first four pages shown and More, the
 * others listed at the top of More; the rail holds every page shown.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.livetv

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.gui.MainActivity
import org.videolan.vlc.gui.MoreFragment
import org.videolan.vlc.gui.helpers.RdStyle
import org.videolan.vlc.livetv.KEY_IPTV_ENABLED
import org.videolan.vlc.livetv.LiveTv
import org.videolan.vlc.util.Pins

object TvNavigation {

    data class Page(val key: String, val id: Int, val title: Int, val icon: Int)

    /** every page, in the order of the design (TV before Network on Android: the bar keeps it in sight) */
    val pages = listOf(
        Page("home", R.id.nav_home, R.string.home, R.drawable.rd_nav_sel_home),
        Page("video", R.id.nav_video, R.string.rd_nav_videos, R.drawable.rd_nav_sel_videos),
        Page("music", R.id.nav_audio, R.string.rd_nav_music, R.drawable.rd_nav_sel_music),
        Page("tv", R.id.nav_tv, R.string.rd_nav_tv, R.drawable.rd_nav_sel_tv),
        Page("network", R.id.nav_directories, R.string.rd_nav_network, R.drawable.rd_nav_sel_browse),
        Page("playlists", R.id.nav_playlists, R.string.rd_tv_playlists, R.drawable.rd_nav_sel_playlists),
    )
    private const val ORDER = "rd_sidebar_order"
    private const val HIDDEN = "rd_sidebar_hidden"
    /** Playlists is reached from More by default (as before the order could be edited) */
    private const val DEFAULT_HIDDEN = "playlists"

    private fun keys(prefs: SharedPreferences, key: String, default: String = "") =
        (prefs.getString(key, default) ?: default).split(',').filter { it.isNotEmpty() }

    fun isPin(key: String) = key.startsWith("pin:")

    /**
     * Every key in the user's order, the pages' and the pins' (the desktop's sidebar order holds both): a page the
     * saved order does not know after the page it follows by default, a pin it does not know at the end.
     */
    fun order(context: Context): List<String> {
        val prefs = Settings.getInstance(context)
        val pins = Pins.list(context).map { Pins.key(it) }
        val saved = keys(prefs, ORDER).filter { k -> pages.any { it.key == k } || pins.contains(k) }.distinct()
        val out = saved.toMutableList()
        for ((i, p) in pages.withIndex()) {
            if (out.contains(p.key)) continue
            var at = 0
            for (j in i - 1 downTo 0) {
                val before = out.indexOf(pages[j].key)
                if (before >= 0) { at = before + 1; break }
            }
            out.add(at, p.key)
        }
        for (k in pins) if (!out.contains(k)) out.add(k)
        return out
    }

    fun hidden(context: Context) = keys(Settings.getInstance(context), HIDDEN, DEFAULT_HIDDEN)

    /** The pins shown (not hidden in Edit Sidebar), in the sidebar's order */
    fun shownPins(context: Context): List<Pins.Pin> {
        val hidden = hidden(context)
        val pins = Pins.list(context).associateBy { Pins.key(it) }
        return order(context).filter { isPin(it) && !hidden.contains(it) }.mapNotNull { pins[it] }
    }

    /** A pin made again leaves the old one's place and hidden state behind */
    fun forget(context: Context, key: String) {
        val prefs = Settings.getInstance(context)
        val order = keys(prefs, ORDER)
        val hidden = keys(prefs, HIDDEN, DEFAULT_HIDDEN)
        if (order.contains(key) || hidden.contains(key)) save(context, order - key, hidden - key)
    }

    /** the page can be in the bar (TV: its add-on is on); a pin always can */
    private fun available(context: Context, key: String) = key != "tv" || Settings.getInstance(context).getBoolean(KEY_IPTV_ENABLED, true)

    /** the pages shown, in their order (Home when none would be) */
    fun shown(context: Context): List<Page> {
        val hidden = hidden(context)
        val rows = order(context).filter { available(context, it) && !hidden.contains(it) }.mapNotNull { k -> pages.firstOrNull { it.key == k } }
        return rows.ifEmpty { listOf(pages[0]) }
    }

    fun save(context: Context, order: List<String>, hidden: List<String>) {
        Settings.getInstance(context).edit {
            putString(ORDER, order.joinToString(","))
            putString(HIDDEN, hidden.joinToString(","))
        }
    }

    /** The pages of a bar: four and More on a bottom bar, every one and More on the rail. */
    fun barPages(context: Context, view: NavigationBarView): List<Page> {
        val shown = shown(context)
        return if (view is BottomNavigationView) shown.take(4) else shown.take(view.maxItemCount - 1)
    }

    /** The pages that do not fit the bottom bar: listed at the top of More. */
    fun overflow(context: Context): List<Page> = shown(context).drop(4).filter { it.key != "playlists" }

    /** The menus of the bottom bar and the rail rebuilt in the user's order. */
    fun apply(activity: MainActivity, views: List<NavigationBarView>, checked: Int) {
        for (v in views) {
            val wanted = barPages(activity, v).map { it.id } + R.id.nav_more
            val current = (0 until v.menu.size()).map { v.menu.getItem(it).itemId }
            if (wanted == current) continue
            v.menu.clear()
            for ((i, p) in barPages(activity, v).withIndex()) v.menu.add(0, p.id, i, p.title).setIcon(p.icon).isCheckable = true
            v.menu.add(0, R.id.nav_more, 100, R.string.more).setIcon(R.drawable.rd_nav_sel_more).isCheckable = true
            v.menu.findItem(checked)?.isChecked = true
        }
    }

    fun goHome(activity: MainActivity) {
        activity.findViewById<NavigationBarView>(R.id.navigation)?.selectedItemId = R.id.nav_home
        activity.findViewById<NavigationBarView>(R.id.navigation_rail)?.selectedItemId = R.id.nav_home
    }

    private fun select(activity: MainActivity, id: Int) {
        for (vid in intArrayOf(R.id.navigation, R.id.navigation_rail)) {
            val v = activity.findViewById<NavigationBarView>(vid) ?: continue
            if (v.menu.findItem(id) != null) v.selectedItemId = id
        }
        // not in the bar: the navigator opens it in place of More
        activity.openPage(id)
    }

    /* More: the pages that do not fit the bar, then Edit Sidebar */

    private val moreCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (f is MoreFragment) decorateMore(f, v)
        }
    }

    fun install(activity: MainActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(moreCallbacks, false)
        LiveTv.init(activity)
    }

    private fun decorateMore(f: MoreFragment, root: View) {
        val ctx = f.requireContext()
        val activity = f.activity as? MainActivity ?: return
        val anchor = root.findViewById<View>(R.id.playlistsButton) ?: return
        val column = anchor.parent as? LinearLayout ?: return
        // the rows added before stay out (the page is made again)
        for (i in column.childCount - 1 downTo 0) if (column.getChildAt(i).getTag(R.id.nav_tv) == true) column.removeViewAt(i)
        val inflater = LayoutInflater.from(ctx)
        var at = column.indexOfChild(anchor)
        for (p in overflow(ctx)) {
            val row = inflater.inflate(R.layout.rd_tv_more_row, column, false) as TextView
            row.text = ctx.getString(p.title)
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(p.icon, 0, R.drawable.rd_ic_chevron_right, 0)
            row.setTag(R.id.nav_tv, true)
            row.setOnClickListener { select(activity, p.id) }
            column.addView(row, at++)
            column.addView(divider(ctx), at++)
        }
        val streams = root.findViewById<View>(R.id.streamsButton) ?: return
        val edit = inflater.inflate(R.layout.rd_tv_more_row, column, false) as TextView
        edit.text = ctx.getString(R.string.rd_tv_edit_sidebar)
        edit.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.rd_ic_sidebar, 0, R.drawable.rd_ic_chevron_right, 0)
        edit.setTag(R.id.nav_tv, true)
        edit.setOnClickListener { editSidebar(activity) }
        val i = column.indexOfChild(streams) + 1
        column.addView(divider(ctx), i)
        column.addView(edit, i + 1)
    }

    private fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(ctx.tvColor(R.color.rd_divider_dim))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.tvPx(1f)).apply { marginStart = ctx.tvPx(56f) }
        setTag(R.id.nav_tv, true)
    }

    /**
     * Edit Sidebar (SidebarEditDialog.qml): the pages and the pinned folders and playlists in their order, moved up
     * and down, each shown or hidden; at least one page stays. Reset to Default puts the design's order back, the
     * pins after the pages. Kept with Save. Unpinning stays in a pin's own menu.
     */
    fun editSidebar(activity: MainActivity) {
        val ctx = activity
        val pins = Pins.list(ctx).associateBy { Pins.key(it) }
        var order = order(ctx).filter { available(ctx, it) }.toMutableList()
        val hidden = hidden(ctx).toMutableSet()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.tvPx(20f), ctx.tvPx(4f), ctx.tvPx(20f), 0) }
        fun build() {
            list.removeAllViews()
            list.addView(ctx.tvText(ctx.getString(R.string.rd_tv_sidebar_pages), 14f, ctx.tvColor(R.color.rd_fg_2), medium = true))
            list.addView(ctx.tvText(ctx.getString(R.string.rd_tv_sidebar_pages_help), 12f, ctx.tvColor(R.color.rd_fg_a3), lines = 0),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(4f); bottomMargin = ctx.tvPx(8f) })
            order.forEachIndexed { i, key ->
                // a page, or a pinned folder or playlist (its own icon and name, as the desktop's dialog)
                val p = pages.firstOrNull { it.key == key }
                val pin = if (p == null) pins[key] ?: return@forEachIndexed else null
                val shown = !hidden.contains(key)
                val title = if (p != null) ctx.getString(p.title) else pin!!.title
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ctx.tvPx(48f) }
                row.contentDescription = title + if (shown) "" else ", " + ctx.getString(R.string.rd_tv_hidden)
                val icon = if (p != null) p.icon else Pins.icon(pin!!)
                row.addView(android.widget.ImageView(ctx).apply { setImageResource(icon); alpha = if (shown) 1f else 0.4f }, LinearLayout.LayoutParams(ctx.tvPx(24f), ctx.tvPx(24f)))
                row.addView(ctx.tvText(title, 14f, ctx.tvColor(if (shown) R.color.rd_fg_1 else R.color.rd_fg_a3)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.tvPx(14f) })
                row.addView(ctx.tvIconButton(R.drawable.rd_ic_chevron_down, ctx.getString(R.string.rd_tv_move_up)) {
                    if (i > 0) { order.add(i - 1, order.removeAt(i)); build() }
                }.apply { rotation = 180f; alpha = if (i > 0) 1f else 0.3f }, LinearLayout.LayoutParams(ctx.tvPx(40f), ctx.tvPx(40f)))
                row.addView(ctx.tvIconButton(R.drawable.rd_ic_chevron_down, ctx.getString(R.string.rd_tv_move_down)) {
                    if (i < order.size - 1) { order.add(i + 1, order.removeAt(i)); build() }
                }.apply { alpha = if (i < order.size - 1) 1f else 0.3f }, LinearLayout.LayoutParams(ctx.tvPx(40f), ctx.tvPx(40f)))
                val toggle = CheckBox(ctx).apply {
                    isChecked = shown
                    contentDescription = ctx.getString(R.string.rd_tv_show_page, title)
                    RdStyle.toggle(this)
                    setOnCheckedChangeListener { _, on ->
                        // the last page shown stays (a pin is not a page)
                        if (!on && p != null && order.count { !isPin(it) && !hidden.contains(it) } <= 1) { isChecked = true; return@setOnCheckedChangeListener }
                        if (on) hidden.remove(key) else hidden.add(key)
                        list.post { build() }
                    }
                }
                row.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(6f) })
                list.addView(row)
            }
        }
        build()
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.rd_tv_edit_sidebar)
            .setView(android.widget.ScrollView(ctx).apply { addView(list) })
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.rd_tv_reset_default, null)
            .setPositiveButton(R.string.rd_tv_save) { _, _ ->
                // the pages not listed (TV, its add-on off) keep their place
                val full = order(ctx).toMutableList()
                val kept = full.filterNot { available(ctx, it) }
                val merged = order.toMutableList()
                for (k in kept) {
                    val before = full.indexOf(k) - 1
                    val anchorKey = if (before >= 0) full[before] else null
                    val at = if (anchorKey == null) 0 else merged.indexOf(anchorKey) + 1
                    merged.add(at.coerceIn(0, merged.size), k)
                }
                save(ctx, merged, hidden.toList())
                Pins.layout.value++
                activity.refreshPages()
            }
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
            // the design's pages, then the pins in the order they were pinned, all shown
            order = (pages.map { it.key }.filter { available(ctx, it) } + pins.keys).toMutableList()
            hidden.clear(); hidden.add(DEFAULT_HIDDEN)
            build()
        }
    }
}
