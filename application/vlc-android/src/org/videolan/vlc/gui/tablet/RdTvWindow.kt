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
import org.videolan.vlc.gui.ContentActivity
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

    fun ContentActivity.applyTvWindow() {
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
        findViewById<View>(R.id.fragment_placeholder).let { ph ->
            (ph.layoutParams as ViewGroup.MarginLayoutParams).apply { marginStart = MAIN_START.dp }
            // (a screen over the library had the Leanback TV's margins and no scrolling behaviour)
            if (this !is MainActivity) (ph.layoutParams as CoordinatorLayout.LayoutParams).apply {
                topMargin = 0; leftMargin = MAIN_START.dp; rightMargin = 0; bottomMargin = 0
                behavior = AppBarLayout.ScrollingViewBehavior()
            }
        }
        if (this !is MainActivity) coordinator.addView(sidebar(this), 1, CoordinatorLayout.LayoutParams(201.dp, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START
            setMargins(8.dp, (TITLE_BAR_HEIGHT + 8).dp, 0, 8.dp)
        })
        if (this is MainActivity) backHome()
        // the first focus on the current page's row, once the sidebar is laid out
        coordinator.postDelayed({ if (currentFocus == null || currentFocus?.id == R.id.rd_tv_title_menu) focusSidebar(this) }, 600)
        // the header follows the page's menu and title as each page sets them
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener { sync(this) }
    }

    /** The title bar (Figma "topbar"): #1B1B1B, 40; the cone 13,8; the menu 51,6; the title in the middle; the clock. */
    private fun titleBar(activity: ContentActivity): View {
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
            // the app's menu (the desktop's ⋯ of the title bar, the Samsung TV app's: shell.js mainMenu)
            setOnClickListener { v -> appMenu(activity, v) }
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

    private fun openMore(activity: ContentActivity) {
        open(activity, R.id.nav_more)
    }

    /**
     * The title bar's menu (the desktop's main menu as the TV has it): Open Network Stream, Recent, Playlists, Edit
     * Sidebar, Settings, About, and More (the page with the rest).
     */
    private fun appMenu(activity: ContentActivity, anchor: View) {
        val popup = PopupMenu(activity, anchor, Gravity.START)
        val m = popup.menu
        fun secondary(fragment: String) = activity.startActivity(android.content.Intent(activity, org.videolan.vlc.gui.SecondaryActivity::class.java).putExtra("fragment", fragment))
        val actions = mutableListOf<() -> Unit>()
        fun add(title: Int, action: () -> Unit) { m.add(0, actions.size, actions.size, title); actions.add(action) }
        add(R.string.rd_open_network_stream) { RdTvNetworkDialog.show(activity) }
        add(R.string.rd_recent) { secondary(org.videolan.vlc.gui.SecondaryActivity.HISTORY) }
        add(R.string.rd_tv_playlists) { open(activity, R.id.nav_playlists) }
        if (activity is MainActivity) add(R.string.rd_tv_edit_sidebar) { org.videolan.vlc.gui.livetv.TvNavigation.editSidebar(activity) }
        add(R.string.rd_settings) {
            activity.startActivityForResult(android.content.Intent(activity, org.videolan.vlc.gui.preferences.PreferencesActivity::class.java), org.videolan.resources.ACTIVITY_RESULT_PREFERENCES)
        }
        add(R.string.rd_set_about) { secondary(org.videolan.vlc.gui.SecondaryActivity.ABOUT) }
        add(R.string.more) { openMore(activity) }
        popup.setOnMenuItemClickListener { actions.getOrNull(it.itemId)?.invoke(); true }
        popup.show()
    }

    /**
     * Back in the library on a page other than Home goes Home first (the TV's way; the desktop has no Back), then
     * leaves as before.
     */
    private fun MainActivity.backHome() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val rail = findViewById<com.google.android.material.navigationrail.NavigationRailView>(R.id.navigation_rail)
                val checked = rail?.menu?.let { m -> (0 until m.size()).map { m.getItem(it) }.firstOrNull { it.isChecked }?.itemId }
                if (supportFragmentManager.backStackEntryCount == 0 && !isAudioPlayerExpanded && checked != null && checked != R.id.nav_home) {
                    open(this@backHome, R.id.nav_home)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    /** The page of the library's sidebar last shown (a screen over it shows it as current, and as its first crumb) */
    var lastPage = R.id.nav_home
        private set
    private var lastPageTitle: String? = null
    /** A page asked from a screen over the library: the library opens it when it comes back */
    private var pendingPage = 0

    /** Opens a page of the sidebar: in the library, or back to it from a screen over it */
    fun open(activity: ContentActivity, id: Int) {
        if (activity is MainActivity) {
            val rail = activity.findViewById<com.google.android.material.navigationrail.NavigationRailView>(R.id.navigation_rail)
            if (rail?.menu?.findItem(id) != null) rail.selectedItemId = id else activity.openPage(id)
        } else {
            pendingPage = id
            activity.finish()
        }
    }

    /** The library back from a screen over it: the page asked there */
    fun MainActivity.applyPendingPage() {
        val id = pendingPage.takeIf { it != 0 } ?: return
        pendingPage = 0
        open(this, id)
    }

    /**
     * The design's sidebar on a screen over the library (a folder, a group, the history): the library's pages, Recent
     * and Add item, the page it was opened from as the current one; a row goes back to the library on that page.
     */
    private fun sidebar(activity: ContentActivity): View {
        val ctx = activity
        val side = LinearLayout(ctx).apply {
            id = R.id.rd_tablet_sidebar
            orientation = LinearLayout.VERTICAL
            setPadding(6.dp, 5.dp, 6.dp, 0)
            background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(SIDEBAR) }
            if (android.os.Build.VERSION.SDK_INT >= 26) isKeyboardNavigationCluster = true
        }
        val accent = org.videolan.vlc.gui.helpers.UiTools.getColorFromAttribute(ctx, androidx.appcompat.R.attr.colorPrimary)
        fun row(icon: Int, label: String, current: Boolean, onClick: () -> Unit) {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                contentDescription = label
                background = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0) }
                onFocusChangeListener = View.OnFocusChangeListener { v, has -> (v.background as GradientDrawable).setColor(if (has) 0xFF222222.toInt() else 0) }
                setOnClickListener { onClick() }
            }
            r.addView(ImageView(ctx).apply {
                setImageResource(icon)
                setColorFilter(if (current) accent else ICON)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = 13.dp; marginEnd = 11.dp })
            r.addView(text(ctx, 14f, if (current) 0xFFC6C6C6.toInt() else ICON).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (current) r.setTag(R.id.rd_tv_header_view, true)
            side.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 46.dp))
        }
        val own = ownPage(activity)
        for (p in org.videolan.vlc.gui.livetv.TvNavigation.shown(ctx)) {
            val current = own == null && p.id == lastPage
            row(TabletSidebar.designIcon(p.id, current).takeIf { it != 0 } ?: p.icon, ctx.getString(p.title), current) { open(activity, p.id) }
        }
        row(if (own == R.string.rd_recent) R.drawable.rd_tvd_recent_active else R.drawable.rd_tvd_recent, ctx.getString(R.string.rd_recent), own == R.string.rd_recent) {
            activity.startActivity(android.content.Intent(activity, org.videolan.vlc.gui.SecondaryActivity::class.java).putExtra("fragment", org.videolan.vlc.gui.SecondaryActivity.HISTORY))
        }
        row(R.drawable.rd_tvd_add, ctx.getString(R.string.rd_add_item), own == R.string.rd_open_network_stream) { RdTvNetworkDialog.show(activity) }
        return side
    }

    /** The library header (.sh-head, .sh-line): the row of 34 at 20 from the top, 28 from the sidebar, 20 from the end. */
    private fun header(activity: ContentActivity): View {
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
            // the current crumb in bold (a span), the earlier ones regular
            typeface = inter(ctx)
            setPadding(2.dp, 0, 2.dp, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 7.dp })
        row.addView(text(ctx, 14f, MUTED).apply { id = R.id.rd_tv_header_count; visibility = View.GONE },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 20.dp })
        row.addView(iconButton(ctx, R.drawable.rd_tvd_sort, MUTED, ctx.getString(R.string.sortby)).apply {
            id = R.id.rd_tv_header_sort
            setOnClickListener { v ->
                // the sort's choices, or the page's display options that hold them
                val sort = menu(activity)?.findItem(R.id.ml_menu_sortby)
                if (visible(sort)) popup(activity, v, R.id.ml_menu_sortby)
                else menu(activity)?.findItem(R.id.ml_menu_display_options)?.let { activity.onOptionsItemSelected(it) }
            }
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
    private fun searchField(activity: ContentActivity): View {
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

    private fun menu(activity: ContentActivity): Menu? = activity.findViewById<Toolbar>(R.id.main_toolbar)?.menu

    private fun visible(item: MenuItem?) = item != null && item.isVisible && item.isEnabled

    /** A screen that is a row of the sidebar itself (Recent, Add item): its name, the title of its own row */
    private fun ownPage(activity: ContentActivity): Int? = when (activity.intent?.getStringExtra("fragment")) {
        org.videolan.vlc.gui.SecondaryActivity.HISTORY -> R.string.rd_recent
        org.videolan.vlc.gui.SecondaryActivity.STREAMS -> R.string.rd_open_network_stream
        else -> null
    }

    /** "Videos › The Wand": the earlier crumbs in FG/A2, the separator in #404040 (.sh-sep), the current one bold */
    private fun crumbsOf(first: String?, current: String): CharSequence {
        val b = android.text.SpannableStringBuilder()
        if (first.isNullOrEmpty()) return b.append(current, android.text.style.StyleSpan(Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.append(first, android.text.style.StyleSpan(Typeface.NORMAL), 0)
        b.setSpan(android.text.style.ForegroundColorSpan(ICON), 0, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val sep = b.length
        b.append("   \u203A   ")
        b.setSpan(android.text.style.ForegroundColorSpan(DISABLED), sep, b.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.append(current, android.text.style.StyleSpan(Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return b
    }

    /**
     * The remote in the design's window (the Samsung TV app's focus rules): Up from the top of the page goes to the
     * header's control above it (not to the sidebar), Down from the header goes into the page. True when handled.
     */
    fun dispatchKey(activity: ContentActivity, event: android.view.KeyEvent): Boolean {
        if (event.action != android.view.KeyEvent.ACTION_DOWN || !isOn(activity)) return false
        val focused = activity.currentFocus ?: return false
        val page = activity.findViewById<ViewGroup>(R.id.fragment_placeholder) ?: return false
        val header = activity.findViewById<ViewGroup>(R.id.rd_tv_header) ?: return false
        fun inside(v: View?, parent: View): Boolean {
            var p: View? = v
            while (p != null) { if (p === parent) return true; p = p.parent as? View }
            return false
        }
        val root = activity.window.decorView as ViewGroup
        val side = activity.findViewById<View>(R.id.rd_tablet_sidebar)
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                // from the page's start back to the current page's row of the sidebar
                if (!inside(focused, page)) return false
                val next = android.view.FocusFinder.getInstance().findNextFocus(root, focused, View.FOCUS_LEFT)
                if (next != null && inside(next, page) && !inside(focused, next)) return false
                focusSidebar(activity)
                return activity.currentFocus !== focused
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // from the sidebar into the page (not along the header)
                if (side == null || !inside(focused, side)) return false
                val first = page.getFocusables(View.FOCUS_RIGHT).firstOrNull { it.isShown } ?: return false
                first.requestFocus()
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                if (!inside(focused, page)) return false
                val next = android.view.FocusFinder.getInstance().findNextFocus(root, focused, View.FOCUS_UP)
                // (a scrolling container of the focused view itself is not "above" it)
                if (next != null && inside(next, page) && !inside(focused, next)) return false
                // the header's control nearest above
                val at = IntArray(2).also { focused.getLocationOnScreen(it) }
                val x = at[0] + focused.width / 2
                val candidates = header.getFocusables(View.FOCUS_UP).filter { it.isShown }.sortedBy { v ->
                    val p = IntArray(2).also { v.getLocationOnScreen(it) }
                    kotlin.math.abs(p[0] + v.width / 2 - x)
                }
                val ok = candidates.any { it.requestFocus(View.FOCUS_UP) }
                if (!ok) return false
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!inside(focused, header)) return false
                val next = android.view.FocusFinder.getInstance().findNextFocus(root, focused, View.FOCUS_DOWN)
                if (next != null && inside(next, page)) return false
                val first = page.getFocusables(View.FOCUS_DOWN).firstOrNull { it.isShown } ?: return false
                first.requestFocus()
                return true
            }
        }
        return false
    }

    /** The first focus of the library: the current row of the sidebar (not the title bar's menu) */
    fun focusSidebar(activity: ContentActivity) {
        val side = activity.findViewById<LinearLayout>(R.id.rd_tablet_sidebar) ?: return
        val row = (0 until side.childCount).map { side.getChildAt(it) }.firstOrNull { (it.isSelected || it.getTag(R.id.rd_tv_header_view) == true) && it.isFocusable }
                ?: (0 until side.childCount).map { side.getChildAt(it) }.firstOrNull { it.isFocusable } ?: return
        row.requestFocus()
    }

    /** The page's main list: the first vertical list in it (a grid or the rows of a library page) */
    private fun mainList(v: View): androidx.recyclerview.widget.RecyclerView? {
        if (v is androidx.recyclerview.widget.RecyclerView && v.isShown) {
            val lm = v.layoutManager
            val vertical = (lm as? androidx.recyclerview.widget.LinearLayoutManager)?.orientation != androidx.recyclerview.widget.RecyclerView.HORIZONTAL
            if (vertical) return v
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) mainList(v.getChildAt(i))?.let { return it }
        return null
    }

    /** The header's sort ([id] = the sort item: its choices) or menu ([id] = 0: the page's other items). */
    private fun popup(activity: ContentActivity, anchor: View, id: Int) {
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

    private fun toggleView(activity: ContentActivity) {
        val menu = menu(activity) ?: return
        val target = listOf(R.id.ml_menu_display_grid, R.id.ml_menu_display_list, R.id.ml_menu_display_options)
                .firstNotNullOfOrNull { id -> menu.findItem(id)?.takeIf { visible(it) } } ?: return
        activity.onOptionsItemSelected(target)
    }

    /** The items the header shows as its own buttons (not in its menu). */
    private val HEADER_ITEMS = setOf(R.id.ml_menu_sortby, R.id.ml_menu_display_grid, R.id.ml_menu_display_list,
            R.id.ml_menu_display_options, R.id.ml_menu_filter, R.id.ml_menu_search, R.id.ml_menu_renderers)

    /** The header as the page set its title and menu. */
    private fun sync(activity: ContentActivity) {
        val header = activity.findViewById<View>(R.id.rd_tv_header) ?: return
        val menu = menu(activity)
        header.findViewById<TextView>(R.id.rd_tv_header_crumb).let { crumb ->
            // the page's own title (the library's pages don't set the activity's)
            val page = activity.supportFragmentManager.findFragmentById(R.id.fragment_placeholder)
            // a page of the sidebar: its name there (Videos, Music); a page opened from it: its own title
            val rail = activity.findViewById<com.google.android.material.navigationrail.NavigationRailView>(R.id.navigation_rail)
            val railTitle = rail?.menu?.let { m -> (0 until m.size()).map { m.getItem(it) }.firstOrNull { it.isChecked }?.title?.toString() }
            val title = if (page is org.videolan.vlc.gui.MoreFragment) activity.getString(R.string.more)
                else if (activity.supportFragmentManager.backStackEntryCount == 0 && railTitle != null) railTitle
                else (page as? org.videolan.vlc.gui.BaseFragment)?.let { runCatching { it.getTitle() }.getOrNull() } ?: activity.title?.toString().orEmpty()
            val crumbs: CharSequence = if (activity is MainActivity) {
                lastPage = rail?.menu?.let { m -> (0 until m.size()).map { m.getItem(it) }.firstOrNull { it.isChecked }?.itemId } ?: lastPage
                lastPageTitle = railTitle ?: lastPageTitle
                crumbsOf(null, title)
            } else ownPage(activity)?.let { crumbsOf(null, activity.getString(it)) } ?: crumbsOf(lastPageTitle, title)
            if (crumb.text.toString() != crumbs.toString()) crumb.text = crumbs
        }
        fun show(id: Int, on: Boolean) = header.findViewById<View>(id).let { if (it.isVisible != on) it.isVisible = on }
        // the page's count of items (the desktop's "110 items"): the main list of a library page
        val shownPage = activity.supportFragmentManager.findFragmentById(R.id.fragment_placeholder)
        val mainList = if (shownPage is org.videolan.vlc.gui.HomeFragment) null else shownPage?.view?.let { mainList(it) }
        val count = mainList?.adapter?.itemCount
        header.findViewById<TextView>(R.id.rd_tv_header_count).let { c ->
            val text = count?.let { activity.resources.getQuantityString(R.plurals.rd_items_count, it, it) }
            if (c.text?.toString() != text) c.text = text
            show(R.id.rd_tv_header_count, text != null)
        }
        val options = visible(menu?.findItem(R.id.ml_menu_display_options))
        show(R.id.rd_tv_header_sort, visible(menu?.findItem(R.id.ml_menu_sortby)) || options)
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
