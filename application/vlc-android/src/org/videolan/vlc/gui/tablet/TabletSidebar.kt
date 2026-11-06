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
        val desktop = isDesktopPage(this)
        coordinator.addView(sidebar, coordinator.indexOfChild(rail) + 1,
                CoordinatorLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.navigation_margin) - if (desktop) 8.dp else 0, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.START
                    if (desktop) setMargins(8.dp, 8.dp, 0, 8.dp)
                })
        if (desktop) sidebar.background = GradientDrawable().apply {
            cornerRadius = 10.dp.toFloat(); setColor(androidx.core.content.ContextCompat.getColor(this@applyTabletSidebar, R.color.rd_dark_bg_a3))
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
        row.addView(indicator, LinearLayout.LayoutParams(3.dp, 20.dp).apply { marginEnd = 1.dp })
        val icon = ImageView(ctx).apply {
            id = R.id.rd_tablet_sidebar_icon
            setImageDrawable(item.icon?.constantState?.newDrawable(ctx.resources, ctx.theme)?.mutate())
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val iconSize = if (isDesktopPage(ctx)) 20.dp else 24.dp
        row.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginStart = 4.dp; marginEnd = 12.dp })
        val label = TextView(ctx).apply {
            id = R.id.rd_tablet_sidebar_label
            text = item.title
            typeface = ResourcesCompat.getFont(ctx, R.font.inter)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setTag(R.id.rd_tablet_sidebar_label, item.itemId)
        return row.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 2.dp } }
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
        val signature = entries.map { if (it is MenuItem) it.itemId.toString() else (it as Pins.Pin).let { p -> Pins.key(p) + "\n" + p.title } }
        if (sidebar.getTag(R.id.rd_tablet_sidebar_pins) == signature) return
        sidebar.setTag(R.id.rd_tablet_sidebar_pins, signature)
        for (i in sidebar.childCount - 1 downTo 0) {
            val tag = sidebar.getChildAt(i).tag
            if (tag == TAG_ROW || tag == TAG_PIN) sidebar.removeViewAt(i)
        }
        val tail = entries.indexOfLast { it is MenuItem } + 1
        var at = 1
        for ((i, e) in entries.withIndex()) {
            if (i == tail && e is Pins.Pin) sidebar.addView(pinnedTitle(activity), at++)
            sidebar.addView(if (e is MenuItem) row(activity, rail, e) else pinRow(activity, e as Pins.Pin), at++)
        }
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
            row.findViewById<View>(R.id.rd_tablet_sidebar_indicator).visibility = if (checked && light) View.VISIBLE else View.INVISIBLE
            row.findViewById<ImageView>(R.id.rd_tablet_sidebar_icon).drawable?.let { d ->
                d.state = if (checked) intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled) else intArrayOf(android.R.attr.state_enabled)
                d.jumpToCurrentState()
            }
        }
    }
}
