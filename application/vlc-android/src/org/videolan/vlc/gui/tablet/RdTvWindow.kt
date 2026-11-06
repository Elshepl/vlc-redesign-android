/*
 * VLC Redesign: the TV layout's library as the design's window (Figma 6261:15078, 970:2703), the same page the
 * Samsung TV app draws at 1920 x 1080 (vlc-redesign-tizen app/css/shell.css): the title bar (40, the cone, the
 * menu, the window's title, the clock where a computer has the window buttons), the sidebar beside the page (in
 * TabletSidebar) and the library header over the page: back, forward, the address, the sort, the view, the search
 * field and the page's menu, the line under them. 1 design px = 1 dp at the TV's scale (RdTvDesignScale).
 *
 * The header is drawn from the activity's own menu: the toolbar stays the source (its items, their visibility as
 * each page sets them) but is not shown; the buttons run the same items.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.tablet

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import com.google.android.material.appbar.AppBarLayout
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.MainActivity
import org.videolan.vlc.gui.SearchActivity
import org.videolan.vlc.interfaces.Filterable

object RdTvWindow {

    /** The design's colours (VLCStyle.qml "redesign", dark): the page, the title bar, the sidebar, the line. */
    const val PAGE = 0xFF0E0E0E.toInt()
    const val TITLE_BAR = 0xFF1B1B1B.toInt()
    const val SIDEBAR = 0xFF191919.toInt()
    private const val LINE = 0xFF171717.toInt()
    private const val FIELD = 0xFF151515.toInt()
    const val FG_2 = 0xFF969696.toInt()
    const val ICON = 0xFF8F8F8F.toInt()
    private const val MUTED = 0xFF6B6B6B.toInt()
    private const val DISABLED = 0xFF404040.toInt()

    /** The design's sizes: the title bar, the main area's start (the sidebar 8 + 201), the header with its line. */
    const val TITLE_BAR_HEIGHT = 40
    const val MAIN_START = 209
    private const val HEADER_HEIGHT = 72

    /** The TV layout at the design's size: the design's own window (a 1920 dp page). */
    fun isOn(ctx: Context) = Settings.showTvUi && ctx.resources.configuration.screenWidthDp >= 1600

    private fun inter(ctx: Context): Typeface? = ResourcesCompat.getFont(ctx, R.font.inter)

    private fun text(ctx: Context, size: Float, color: Int) = TextView(ctx).apply {
        typeface = inter(ctx)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** An icon button of the header (.sh-ib): 32 x 32, the icon 24, radius 6, the hover colour on focus. */
    private fun iconButton(ctx: Context, icon: Int, tint: Int, desc: CharSequence?) = ImageView(ctx).apply {
        setImageResource(icon)
        setColorFilter(tint)
        setPadding(4.dp, 4.dp, 4.dp, 4.dp)
        contentDescription = desc
        isFocusable = true
        isClickable = true
        background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0) }
        onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            (v.background as GradientDrawable).setColor(if (has) 0xFF222222.toInt() else 0)
        }
    }

    fun MainActivity.applyTvWindow() {
        if (!isOn(this)) return
        val coordinator = findViewById<CoordinatorLayout>(R.id.coordinator) ?: return
        if (coordinator.findViewById<View>(R.id.rd_tv_titlebar) != null) return
        coordinator.setBackgroundColor(PAGE)
        window.decorView.setBackgroundColor(PAGE)
        coordinator.addView(titleBar(this), 0, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, TITLE_BAR_HEIGHT.dp).apply { gravity = Gravity.TOP })

        // the page and its header beside the sidebar, under the title bar
        val appbar = findViewById<AppBarLayout>(R.id.appbar)
        appbar.setBackgroundColor(PAGE)
        (appbar.layoutParams as ViewGroup.MarginLayoutParams).apply { marginStart = MAIN_START.dp; topMargin = TITLE_BAR_HEIGHT.dp }
        findViewById<Toolbar>(R.id.main_toolbar).visibility = View.GONE
        val header = header(this)
        appbar.addView(header, 0, AppBarLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, HEADER_HEIGHT.dp).apply { scrollFlags = 0 })
        findViewById<View>(R.id.fragment_placeholder).let { (it.layoutParams as ViewGroup.MarginLayoutParams).marginStart = MAIN_START.dp }
        // the header follows the page's menu and title as each page sets them
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener { sync(this) }
    }

    /** The title bar (Figma "topbar"): #1B1B1B, 40; the cone 13,8; the menu 51,6; the title in the middle; the clock. */
    private fun titleBar(activity: MainActivity): View {
        val ctx = activity
        val bar = FrameLayout(ctx).apply {
            id = R.id.rd_tv_titlebar
            setBackgroundColor(TITLE_BAR)
        }
        bar.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.rd_ic_logo)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(24.dp, 24.dp).apply { leftMargin = 13.dp; topMargin = 8.dp })
        val menu = iconButton(ctx, R.drawable.rd_tvd_more, ICON, ctx.getString(R.string.more)).apply {
            setPadding(2.dp, 2.dp, 2.dp, 2.dp)
            id = R.id.rd_tv_title_menu
            // the app's menu (the desktop's ⋯ of the title bar): the More page holds its entries
            setOnClickListener { activity.findViewById<View?>(R.id.nav_more)?.performClick() ?: openMore(activity) }
        }
        bar.addView(menu, FrameLayout.LayoutParams(28.dp, 28.dp).apply { leftMargin = 51.dp; topMargin = 6.dp })
        bar.addView(text(ctx, 14f, ICON).apply {
            id = R.id.rd_tv_title_text
            gravity = Gravity.CENTER
            text = ctx.getString(R.string.app_name)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40.dp).apply { leftMargin = 400.dp; rightMargin = 400.dp; topMargin = 1.dp })
        bar.addView(TextClock(ctx).apply {
            typeface = inter(ctx)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(ICON)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40.dp, Gravity.END).apply { rightMargin = 16.dp })
        return bar
    }

    private fun openMore(activity: MainActivity) {
        activity.findViewById<com.google.android.material.navigationrail.NavigationRailView>(R.id.navigation_rail)?.selectedItemId = R.id.nav_more
    }

    /** The library header (.sh-head, .sh-line): the row of 34 at 20 from the top, 28 from the sidebar, 20 from the end. */
    private fun header(activity: MainActivity): View {
        val ctx = activity
        val box = FrameLayout(ctx).apply { id = R.id.rd_tv_header }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        box.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 34.dp).apply { leftMargin = 28.dp; rightMargin = 20.dp; topMargin = 20.dp })
        box.addView(View(ctx).apply { setBackgroundColor(LINE) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2.dp).apply { leftMargin = 28.dp; rightMargin = 20.dp; topMargin = 70.dp })

        fun lp(left: Int, right: Int, w: Int = 32, h: Int = 32) = LinearLayout.LayoutParams(w.dp, h.dp).apply { leftMargin = left.dp; rightMargin = right.dp }
        row.addView(iconButton(ctx, R.drawable.rd_tvd_arrow_left, ICON, ctx.getString(androidx.appcompat.R.string.abc_action_bar_up_description)).apply {
            id = R.id.rd_tv_header_back
            setOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        }, lp(-4, -4))
        row.addView(iconButton(ctx, R.drawable.rd_tvd_arrow_right, DISABLED, null).apply {
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, lp(8, -4))
        row.addView(text(ctx, 16f, FG_2).apply {
            id = R.id.rd_tv_header_crumb
            typeface = Typeface.create(inter(ctx), Typeface.BOLD)
            setPadding(2.dp, 0, 2.dp, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 7.dp })
        row.addView(text(ctx, 14f, MUTED).apply { id = R.id.rd_tv_header_count; visibility = View.GONE },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 20.dp })
        row.addView(iconButton(ctx, R.drawable.rd_tvd_sort, MUTED, ctx.getString(R.string.sortby)).apply {
            id = R.id.rd_tv_header_sort
            setOnClickListener { v -> popup(activity, v, R.id.ml_menu_sortby) }
        }, lp(-4, -4))
        row.addView(iconButton(ctx, R.drawable.rd_tvd_grid, MUTED, ctx.getString(R.string.display_settings)).apply {
            id = R.id.rd_tv_header_view
            setOnClickListener { toggleView(activity) }
        }, lp(16, -4))
        row.addView(searchField(activity), LinearLayout.LayoutParams(325.dp, 34.dp).apply { leftMargin = 20.dp })
        row.addView(iconButton(ctx, R.drawable.rd_tvd_more, MUTED, ctx.getString(R.string.more)).apply {
            id = R.id.rd_tv_header_more
            setOnClickListener { v -> popup(activity, v, 0) }
        }, lp(8, -4))
        return box
    }

    /** The search field (.sh-search): 325 x 34, #151515, radius 6; the icon at 7,4; the text at 41. */
    private fun searchField(activity: MainActivity): View {
        val ctx = activity
        val field = FrameLayout(ctx).apply {
            id = R.id.rd_tv_header_search
            background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(FIELD) }
        }
        field.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.rd_tvd_search)
            setColorFilter(DISABLED)
        }, FrameLayout.LayoutParams(24.dp, 24.dp).apply { leftMargin = 7.dp; topMargin = 5.dp })
        val edit = EditText(ctx).apply {
            id = R.id.rd_tv_header_search_text
            background = null
            setPadding(0, 0, 0, 0)
            typeface = inter(ctx)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(ICON)
            setHintTextColor(DISABLED)
            hint = ctx.getString(R.string.search)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val f = activity.supportFragmentManager.findFragmentById(R.id.fragment_placeholder) as? Filterable ?: return
                    val q = s?.toString().orEmpty()
                    if (q.isEmpty()) f.restoreList() else f.filter(q)
                }
            })
            setOnEditorActionListener { v, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    // a page without a filter: the whole library's search, as the desktop's
                    if (activity.supportFragmentManager.findFragmentById(R.id.fragment_placeholder) !is Filterable)
                        activity.startActivity(Intent(Intent.ACTION_SEARCH, null, activity, SearchActivity::class.java)
                                .putExtra(android.app.SearchManager.QUERY, v.text.toString()))
                    true
                } else false
            }
        }
        field.addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = 41.dp; rightMargin = 8.dp })
        return field
    }

    private fun menu(activity: MainActivity): Menu? = activity.findViewById<Toolbar>(R.id.main_toolbar)?.menu

    private fun visible(item: MenuItem?) = item != null && item.isVisible && item.isEnabled

    /** The header's sort ([id] = the sort item: its choices) or menu ([id] = 0: the page's other items). */
    private fun popup(activity: MainActivity, anchor: View, id: Int) {
        val menu = menu(activity) ?: return
        val items: List<MenuItem> = if (id != 0) {
            val sub = menu.findItem(id)?.subMenu ?: return
            (0 until sub.size()).map { sub.getItem(it) }.filter { visible(it) }
        } else (0 until menu.size()).map { menu.getItem(it) }.filter { visible(it) && it.itemId !in HEADER_ITEMS && !it.hasSubMenu() }
        if (items.isEmpty()) return
        val popup = PopupMenu(activity, anchor, Gravity.END)
        items.forEachIndexed { i, item -> popup.menu.add(0, i, i, item.title).apply { isCheckable = item.isCheckable; isChecked = item.isChecked } }
        popup.setOnMenuItemClickListener { picked ->
            items.getOrNull(picked.itemId)?.let { activity.onOptionsItemSelected(it) }
            true
        }
        popup.show()
    }

    private fun toggleView(activity: MainActivity) {
        val menu = menu(activity) ?: return
        val target = listOf(R.id.ml_menu_display_grid, R.id.ml_menu_display_list, R.id.ml_menu_display_options)
                .firstNotNullOfOrNull { id -> menu.findItem(id)?.takeIf { visible(it) } } ?: return
        activity.onOptionsItemSelected(target)
    }

    /** The items the header shows as its own buttons (not in its menu). */
    private val HEADER_ITEMS = setOf(R.id.ml_menu_sortby, R.id.ml_menu_display_grid, R.id.ml_menu_display_list,
            R.id.ml_menu_display_options, R.id.ml_menu_filter, R.id.ml_menu_search, R.id.ml_menu_renderers)

    /** The header as the page set its title and menu. */
    private fun sync(activity: MainActivity) {
        val header = activity.findViewById<View>(R.id.rd_tv_header) ?: return
        val menu = menu(activity)
        header.findViewById<TextView>(R.id.rd_tv_header_crumb).let { crumb ->
            // the page's own title (the library's pages don't set the activity's)
            val page = activity.supportFragmentManager.findFragmentById(R.id.fragment_placeholder)
            val title = (page as? org.videolan.vlc.gui.BaseFragment)?.let { runCatching { it.getTitle() }.getOrNull() } ?: activity.title?.toString().orEmpty()
            if (crumb.text.toString() != title) crumb.text = title
        }
        fun show(id: Int, on: Boolean) = header.findViewById<View>(id).let { if (it.isVisible != on) it.isVisible = on }
        show(R.id.rd_tv_header_sort, visible(menu?.findItem(R.id.ml_menu_sortby)))
        val grid = visible(menu?.findItem(R.id.ml_menu_display_grid))
        val list = visible(menu?.findItem(R.id.ml_menu_display_list))
        show(R.id.rd_tv_header_view, grid || list || visible(menu?.findItem(R.id.ml_menu_display_options)))
        header.findViewById<ImageView>(R.id.rd_tv_header_view).let { v ->
            // the view the page is in (the desktop shows the grid's icon in the grid view)
            val icon = if (grid) R.drawable.rd_tvd_list else R.drawable.rd_tvd_grid
            if (v.getTag(R.id.rd_tv_header_view) != icon) { v.setTag(R.id.rd_tv_header_view, icon); v.setImageResource(if (grid) R.drawable.rd_tvd_list else R.drawable.rd_tvd_grid) }
        }
        val more = menu != null && (0 until menu.size()).any { menu.getItem(it).let { i -> visible(i) && i.itemId !in HEADER_ITEMS && !i.hasSubMenu() } }
        show(R.id.rd_tv_header_more, more)
    }
}
