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
            syncRows(this, existing, rail)
            refresh(existing, rail)
            return
        }
        val sidebar = build(this, rail)
        coordinator.addView(sidebar, coordinator.indexOfChild(rail) + 1,
                CoordinatorLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.navigation_margin), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.START
                })
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
        val menu = rail.menu
        for (i in 0 until menu.size()) sidebar.addView(row(activity, rail, menu.getItem(i)))
        sidebar.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        // the large play button of the rail's header, kept for "play all" of the current page
        rail.headerView?.let { header ->
            rail.removeHeaderView()
            (header.parent as? ViewGroup)?.removeView(header)
            sidebar.addView(FrameLayout(ctx).apply { addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.BOTTOM)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            minimumHeight = 40.dp
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
        row.addView(icon, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = 4.dp; marginEnd = 12.dp })
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
     * The rail's menu is rebuilt when the pages change (the Live TV add-on switched, Edit Sidebar, the TV page
     * added after the first start): the sidebar's rows follow it, the play button at the bottom stays.
     */
    private fun syncRows(activity: MainActivity, sidebar: LinearLayout, rail: NavigationRailView) {
        val menu = rail.menu
        val wanted = (0 until menu.size()).map { menu.getItem(it).itemId }
        val rows = (0 until sidebar.childCount).map { sidebar.getChildAt(it) }.filter { it.tag == TAG_ROW }
        if (rows.map { it.getTag(R.id.rd_tablet_sidebar_label) } == wanted) return
        rows.forEach { sidebar.removeView(it) }
        for (i in 0 until menu.size()) sidebar.addView(row(activity, rail, menu.getItem(i)), 1 + i)
    }

    /** VLC Redesign: called when the pages of the bars change. */
    fun MainActivity.refreshTabletSidebar() {
        val rail = findViewById<NavigationRailView>(R.id.navigation_rail) ?: return
        val sidebar = findViewById<CoordinatorLayout>(R.id.coordinator)?.findViewById<LinearLayout>(R.id.rd_tablet_sidebar) ?: return
        syncRows(this, sidebar, rail)
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
