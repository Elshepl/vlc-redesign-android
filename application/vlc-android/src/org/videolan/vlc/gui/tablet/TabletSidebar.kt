/*
 * ************************************************************************
 *  TabletSidebar.kt
 * *************************************************************************
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * *************************************************************************
 */

package org.videolan.vlc.gui.tablet

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.navigationrail.NavigationRailView
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.MainActivity
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.dialogs.RdContextMenu
import org.videolan.vlc.gui.dialogs.Simple
import org.videolan.vlc.gui.livetv.TvNavigation
import org.videolan.vlc.util.ContextOption
import org.videolan.vlc.util.Pins
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * VLC Redesign, tablets: the desktop's expanded sidebar (SideNavigationPane / SideNavigationDelegate) instead of
 * the phone's navigation rail when the window is a tablet one and wide enough (`rd_tablet_sidebar`,
 * values-sw600dp-w900dp). Rows of icon + label: the current page has its icon in the accent and its label in the
 * main text colour, the others are muted; pressed rows get the hover background; in the light theme the current
 * row also has the accent indicator at its start, as on the desktop.
 *
 * The rail stays the source of truth (the [org.videolan.vlc.gui.helpers.Navigator] listens to it): a row only
 * selects the rail's item, and the rows follow the rail's checked item whenever a fragment resumes. The rail's
 * header (the large play button) moves to the bottom of the sidebar.
 */
object TabletSidebar {

    private const val TAG_ROW = "rd_tablet_sidebar_row"
    private const val TAG_PIN = "rd_tablet_sidebar_pin"

    /** The desktop's own page: a window of 1600 dp and more (the TV layout at the design's size, RdTvDesignScale). */
    private fun isDesktopPage(ctx: android.content.Context) = ctx.resources.configuration.screenWidthDp >= 1600

    fun MainActivity.applyTabletSidebar() {
        val rail = findViewById<NavigationRailView>(R.id.navigation_rail) ?: return
        if (!resources.getBoolean(R.bool.rd_tablet_sidebar)) {
            // a narrower tablet window keeps the rail, its entries at the top as the desktop sidebar's
            if (resources.getBoolean(R.bool.is_tablet)) rail.menuGravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            return
        }
        val coordinator = findViewById<CoordinatorLayout>(R.id.coordinator) ?: return
        rail.visibility = View.GONE
        val existing = coordinator.findViewById<LinearLayout>(R.id.rd_tablet_sidebar)
        if (existing != null) {
            layoutRows(this, existing, rail)
            refresh(existing, rail)
            return
        }
        val sidebar = build(this, rail)
        // VLC Redesign: on the desktop's own page (the TV layout, RdTvDesignScale) the sidebar is the desktop's panel
        // (Figma 6261:15078): A3, radius 10, 8 from the window's edges, its rows of 46
        // (the Samsung TV app's .sh-side: 8 from the window's start, under the title bar, 201 wide, #191919 radius 6)
        val desktop = isDesktopPage(this)
        coordinator.addView(sidebar, coordinator.indexOfChild(rail) + 1,
                CoordinatorLayout.LayoutParams(if (desktop) 201.dp else resources.getDimensionPixelSize(R.dimen.navigation_margin), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.START
                    if (desktop) setMargins(8.dp, (RdTvWindow.TITLE_BAR_HEIGHT + 8).dp, 0, 8.dp)
                })
        if (desktop) sidebar.background = GradientDrawable().apply {
            cornerRadius = 6.dp.toFloat(); setColor(RdTvWindow.SIDEBAR)
        }
        refresh(sidebar, rail)
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                sidebar.post { refresh(sidebar, rail) }
            }
        }, false)
    }

    private fun build(activity: MainActivity, rail: NavigationRailView): LinearLayout {
        val ctx = activity
        val sidebar = LinearLayout(ctx).apply {
            id = R.id.rd_tablet_sidebar
            orientation = LinearLayout.VERTICAL
            background = ctx.obtainStyledAttributes(intArrayOf(R.attr.bottom_navigation_background)).let { a -> a.getDrawable(0).also { a.recycle() } }
            // the rows line up with the top bar of the page beside it
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            isFocusable = false
            if (android.os.Build.VERSION.SDK_INT >= 26) isKeyboardNavigationCluster = true
        }
        val top = 8.dp
        if (isDesktopPage(ctx)) {
            // the design's list: 4 from the panel's top, the rows 6 from its sides; no play button at the bottom
            sidebar.setPadding(6.dp, 5.dp, 6.dp, 0)
            sidebar.addView(View(ctx), LinearLayout.LayoutParams(1, 0))
            layoutRows(activity, sidebar, rail)
            rail.removeHeaderView()
            activity.lifecycleScope.launch {
                Pins.pins(activity).collect { list -> if (list != null) { layoutRows(activity, sidebar, rail); refresh(sidebar, rail) } }
            }
            return sidebar
        }
        ViewCompat.setOnApplyWindowInsetsListener(sidebar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(top = top + bars.top, bottom = 8.dp + bars.bottom)
            if (v.layoutDirection == View.LAYOUT_DIRECTION_RTL) v.updatePadding(right = 8.dp + bars.right) else v.updatePadding(left = 8.dp + bars.left)
            insets
        }
        // the toolbar's height: the first row starts level with the page's title
        sidebar.addView(View(ctx), LinearLayout.LayoutParams(1, 4.dp))
        sidebar.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        // the large play button of the rail's header, kept for "play all" of the current page
        rail.headerView?.let { header ->
            rail.removeHeaderView()
            (header.parent as? ViewGroup)?.removeView(header)
            sidebar.addView(FrameLayout(ctx).apply { addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.BOTTOM)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        layoutRows(activity, sidebar, rail)
        // the folders and playlists pinned or unpinned (the desktop's "Pin to Sidebar")
        activity.lifecycleScope.launch {
            Pins.pins(activity).collect { list -> if (list != null) { layoutRows(activity, sidebar, rail); refresh(sidebar, rail) } }
        }
        return sidebar
    }

    private fun row(activity: MainActivity, rail: NavigationRailView, item: MenuItem): View {
        val ctx = activity
        val radius = 6.dp.toFloat()
        val hover = UiTools.getColorFromAttribute(ctx, R.attr.rd_hover)
        val mask = GradientDrawable().apply { cornerRadius = radius; setColor(0xFFFFFFFF.toInt()) }
        val row = LinearLayout(ctx).apply {
            tag = TAG_ROW
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = if (isDesktopPage(ctx)) 44.dp else 40.dp
            setPaddingRelative(4.dp, 4.dp, 8.dp, 4.dp)
            background = RippleDrawable(ColorStateList.valueOf(hover), null, mask)
            isClickable = true
            isFocusable = true
            contentDescription = item.title
            setOnClickListener {
                // the Navigator does the navigation (and the "already selected: back to the root" case)
                rail.selectedItemId = item.itemId
                refresh(parent as LinearLayout, rail)
            }
        }
        // the current page's indicator: light theme only, as the desktop's CurrentIndicator
        val indicator = View(ctx).apply {
            id = R.id.rd_tablet_sidebar_indicator
            background = GradientDrawable().apply { cornerRadius = 3.dp.toFloat(); setColor(UiTools.getColorFromAttribute(ctx, androidx.appcompat.R.attr.colorPrimary)) }
            visibility = View.INVISIBLE
        }
        val desktop = isDesktopPage(ctx)
        row.addView(indicator, LinearLayout.LayoutParams(3.dp, 20.dp).apply { marginEnd = 1.dp })
        val icon = ImageView(ctx).apply {
            id = R.id.rd_tablet_sidebar_icon
            setImageDrawable(item.icon?.constantState?.newDrawable(ctx.resources, ctx.theme)?.mutate())
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (desktop) {
            // the design's row (.sh-nav): 46 high, the icon 24 at 13 (12 + the focus border), the label 11 after it
            indicator.visibility = View.GONE
            indicator.tag = "gone"
            row.minimumHeight = 46.dp
            row.setPaddingRelative(0, 0, 8.dp, 0)
        }
        row.addView(icon, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = if (desktop) 13.dp else 4.dp; marginEnd = if (desktop) 11.dp else 12.dp })
        val label = TextView(ctx).apply {
            id = R.id.rd_tablet_sidebar_label
            text = item.title
            typeface = ResourcesCompat.getFont(ctx, R.font.inter)
            if (desktop) includeFontPadding = false
            setTextSize(if (desktop) TypedValue.COMPLEX_UNIT_DIP else TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setTag(R.id.rd_tablet_sidebar_label, item.itemId)
        return row.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (desktop) 46.dp else ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = if (desktop) 0 else 2.dp } }
    }

    /**
     * The rows in Edit Sidebar's order: the pages of the rail and the pins shown, mixed as the user placed them
     * (the desktop's sidebar), More after the last page. The pins after it get the "Pinned" title, as before.
     */
    private fun layoutRows(activity: MainActivity, sidebar: LinearLayout, rail: NavigationRailView) {
        val menu = rail.menu
        val items = (0 until menu.size()).map { menu.getItem(it) }
        val pins = TvNavigation.shownPins(activity).associateBy { Pins.key(it) }
        val entries = mutableListOf<Any>()
        for (k in TvNavigation.order(activity)) {
            val page = TvNavigation.pages.firstOrNull { it.key == k }
            if (page != null) items.firstOrNull { it.itemId == page.id }?.let { entries.add(it) }
            else pins[k]?.let { entries.add(it) }
        }
        // the rail's items the order does not hold (More) after its last page
        val lastPage = entries.indexOfLast { it is MenuItem }
        entries.addAll(lastPage + 1, items.filter { item -> entries.none { it === item } })
        if (isDesktopPage(activity)) {
            // the design's sidebar: More is the title bar's menu; Recent and Add item after the pages
            entries.removeAll { it is MenuItem && it.itemId == R.id.nav_more }
            val last = entries.indexOfLast { it is MenuItem }
            entries.addAll(last + 1, listOf(EXTRA_RECENT, EXTRA_ADD))
        }
        val signature = entries.map { if (it is MenuItem) it.itemId.toString() else if (it is String) it else (it as Pins.Pin).let { p -> Pins.key(p) + "\n" + p.title } }
        if (sidebar.getTag(R.id.rd_tablet_sidebar_pins) == signature) return
        sidebar.setTag(R.id.rd_tablet_sidebar_pins, signature)
        for (i in sidebar.childCount - 1 downTo 0) {
            val tag = sidebar.getChildAt(i).tag
            if (tag == TAG_ROW || tag == TAG_PIN) sidebar.removeViewAt(i)
        }
        val tail = entries.indexOfLast { it is MenuItem || it is String } + 1
        var at = 1
        for ((i, e) in entries.withIndex()) {
            if (i == tail && e is Pins.Pin) sidebar.addView(pinnedTitle(activity), at++)
            sidebar.addView(when (e) {
                is MenuItem -> row(activity, rail, e)
                is String -> extraRow(activity, e)
                else -> pinRow(activity, e as Pins.Pin)
            }, at++)
        }
    }

    private const val EXTRA_RECENT = "recent"
    private const val EXTRA_ADD = "add"

    /** The design's icons of the sidebar (the desktop's SVGs), the current page's filled one */
    private fun designIcon(id: Int, active: Boolean) = when (id) {
        R.id.nav_home -> if (active) R.drawable.rd_tvd_home_active else R.drawable.rd_tvd_home
        R.id.nav_video -> if (active) R.drawable.rd_tvd_videos_active else R.drawable.rd_tvd_videos
        R.id.nav_audio -> if (active) R.drawable.rd_tvd_songs_active else R.drawable.rd_tvd_songs
        R.id.nav_directories -> if (active) R.drawable.rd_tvd_streaming_active else R.drawable.rd_tvd_streaming
        R.id.nav_playlists -> if (active) R.drawable.rd_tvd_playlist_active else R.drawable.rd_tvd_playlist
        R.id.nav_tv -> if (active) R.drawable.rd_tvd_tv_active else R.drawable.rd_tvd_tv
        else -> 0
    }

    private fun tintIcon(icon: ImageView, id: Int, active: Boolean) {
        val res = designIcon(id, active)
        if (res == 0) return
        if (icon.getTag(R.id.rd_tablet_sidebar_icon) != res) { icon.setTag(R.id.rd_tablet_sidebar_icon, res); icon.setImageResource(res) }
        icon.setColorFilter(if (active) UiTools.getColorFromAttribute(icon.context, androidx.appcompat.R.attr.colorPrimary) else RdTvWindow.ICON)
    }

    /** Recent (the history) and Add item (Open Network Stream) of the design's sidebar */
    private fun extraRow(activity: MainActivity, key: String): View {
        val ctx = activity
        val row = LinearLayout(ctx).apply {
            tag = TAG_PIN
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(0, 0, 8.dp, 0)
            val mask = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0xFFFFFFFF.toInt()) }
            background = RippleDrawable(ColorStateList.valueOf(UiTools.getColorFromAttribute(ctx, R.attr.rd_hover)), null, mask)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val i = android.content.Intent(activity, org.videolan.vlc.gui.SecondaryActivity::class.java)
                i.putExtra("fragment", if (key == EXTRA_RECENT) org.videolan.vlc.gui.SecondaryActivity.HISTORY else org.videolan.vlc.gui.SecondaryActivity.STREAMS)
                activity.startActivityForResult(i, org.videolan.vlc.gui.SecondaryActivity.ACTIVITY_RESULT_SECONDARY)
            }
        }
        val title = ctx.getString(if (key == EXTRA_RECENT) R.string.rd_recent else R.string.rd_add_item)
        row.contentDescription = title
        row.addView(ImageView(ctx).apply {
            setImageResource(if (key == EXTRA_RECENT) R.drawable.rd_tvd_recent else R.drawable.rd_tvd_add)
            setColorFilter(RdTvWindow.ICON)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = 13.dp; marginEnd = 11.dp })
        row.addView(TextView(ctx).apply {
            text = title
            typeface = ResourcesCompat.getFont(ctx, R.font.inter)
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(UiTools.getColorFromAttribute(ctx, R.attr.rd_icon))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dp)
        return row
    }

    private fun pinnedTitle(ctx: MainActivity) = TextView(ctx).apply {
        tag = TAG_PIN
        setText(R.string.rd_pinned)
        typeface = ResourcesCompat.getFont(ctx, R.font.inter)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(UiTools.getColorFromAttribute(ctx, R.attr.rd_icon))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPaddingRelative(12.dp, 16.dp, 8.dp, 6.dp)
    }

    /** A pin's row: a tap opens it; a long press offers Unpin from Sidebar */
    private fun pinRow(activity: MainActivity, pin: Pins.Pin): View {
        val ctx = activity
        val muted = UiTools.getColorFromAttribute(ctx, R.attr.rd_icon)
        val hover = UiTools.getColorFromAttribute(ctx, R.attr.rd_hover)
        run {
            val mask = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0xFFFFFFFF.toInt()) }
            val row = LinearLayout(ctx).apply {
                tag = TAG_PIN
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 40.dp
                setPaddingRelative(8.dp, 4.dp, 8.dp, 4.dp)
                background = RippleDrawable(ColorStateList.valueOf(hover), null, mask)
                isClickable = true
                isFocusable = true
                contentDescription = pin.title
                setOnClickListener { Pins.open(activity, pin) }
                setOnLongClickListener {
                    RdContextMenu.show(activity, listOf(Simple(ContextOption.CTX_UNPIN, Pins.label(ctx, true), R.drawable.rd_ic_pin))) { Pins.unpin(ctx, pin) }
                    true
                }
            }
            row.addView(ImageView(ctx).apply {
                setImageResource(Pins.icon(pin))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginEnd = 12.dp })
            row.addView(TextView(ctx).apply {
                text = pin.title
                typeface = ResourcesCompat.getFont(ctx, R.font.inter)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 2.dp }
            return row
        }
    }

    /**
     * VLC Redesign: called when the pages of the bars change (the Live TV add-on switched, Edit Sidebar, the TV
     * page added after the first start): the sidebar's rows follow, the play button at the bottom stays.
     */
    fun MainActivity.refreshTabletSidebar() {
        val rail = findViewById<NavigationRailView>(R.id.navigation_rail) ?: return
        val sidebar = findViewById<CoordinatorLayout>(R.id.coordinator)?.findViewById<LinearLayout>(R.id.rd_tablet_sidebar) ?: return
        layoutRows(this, sidebar, rail)
        refresh(sidebar, rail)
    }

    private fun checkedId(rail: NavigationRailView): Int {
        val menu = rail.menu
        for (i in 0 until menu.size()) if (menu.getItem(i).isChecked) return menu.getItem(i).itemId
        return rail.selectedItemId
    }

    private fun refresh(sidebar: LinearLayout, rail: NavigationRailView) {
        val ctx = sidebar.context
        val current = checkedId(rail)
        val main = UiTools.getColorFromAttribute(ctx, R.attr.font_default)
        val muted = UiTools.getColorFromAttribute(ctx, R.attr.rd_icon)
        val light = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
        for (i in 0 until sidebar.childCount) {
            val row = sidebar.getChildAt(i)
            if (row.tag != TAG_ROW) continue
            val checked = row.getTag(R.id.rd_tablet_sidebar_label) == current
            row.isSelected = checked
            row.findViewById<TextView>(R.id.rd_tablet_sidebar_label).setTextColor(if (checked) main else muted)
            row.findViewById<View>(R.id.rd_tablet_sidebar_indicator).let { if (it.tag != "gone") it.visibility = if (checked && light) View.VISIBLE else View.INVISIBLE }
            if (isDesktopPage(ctx)) { tintIcon(row.findViewById(R.id.rd_tablet_sidebar_icon), row.getTag(R.id.rd_tablet_sidebar_label) as Int, checked); continue }
            row.findViewById<ImageView>(R.id.rd_tablet_sidebar_icon).drawable?.let { d ->
                d.state = if (checked) intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled) else intArrayOf(android.R.attr.state_enabled)
                d.jumpToCurrentState()
            }
        }
    }
}
