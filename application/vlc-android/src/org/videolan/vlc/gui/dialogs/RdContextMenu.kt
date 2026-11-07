/*
 * VLC Redesign: the context menu of an item (long press, right click, the item's "..." button) as the desktop's
 * menu: a compact framed popup at the finger / pointer (or at the button), not a sheet as tall as the screen.
 *
 * - 220 to 300 dp wide (its longest entry), rows of 44 dp: a 20 dp icon and Inter 14 in the main text colour,
 *   the hover / press in the accent; the entries in groups (play, find, collection, remove) with a 1 px separator.
 * - The menu panel of the design (rd_menu_background: A4, radius 10, the 1 px window border), at most 70 % of the
 *   window high (it scrolls past that).
 * - Opened at the last touch of the window (the long-pressed row, the tapped "..."), below and toward the end of
 *   the point, flipped above / toward the start when it would leave the window; mirrored in right-to-left
 *   languages.
 * - A TV (D-pad, no touch point) keeps the sheet (ContextSheet).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.dialogs

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.WindowCallbackWrapper
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.videolan.resources.AndroidDevices
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.rdAccent
import org.videolan.vlc.util.ContextOption
import org.videolan.vlc.util.ContextOption.*
import java.lang.ref.WeakReference

object RdContextMenu {

    private const val MIN_WIDTH_DP = 220
    private const val MAX_WIDTH_DP = 300
    private const val MAX_HEIGHT_FRACTION = 0.7f
    /** a touch older than this is not where the menu was asked for (a key, a menu of the toolbar) */
    private const val TOUCH_MAX_AGE_MS = 4000L

    /* ---- the last touch of each window ---- */

    private var lastX = -1f
    private var lastY = -1f
    private var lastTime = 0L
    private var lastWindow: WeakReference<Window>? = null

    private class Tracker(private val window: Window, wrapped: Window.Callback) : WindowCallbackWrapper(wrapped) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val a = event.actionMasked
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP) {
                lastX = event.rawX; lastY = event.rawY; lastTime = SystemClock.uptimeMillis()
                lastWindow = WeakReference(window)
            }
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            // a mouse / trackpad pointer: its right click opens the menu where it is
            if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
                lastX = event.rawX; lastY = event.rawY; lastTime = SystemClock.uptimeMillis()
                lastWindow = WeakReference(window)
            }
            return super.dispatchGenericMotionEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            // a key: no point to open at (the menu goes to the middle)
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) lastTime = 0L
            return super.dispatchKeyEvent(event)
        }
    }

    /** Follows the touches of every activity of the app (installed once, from the application). */
    fun install(app: Application) {
        if (AndroidDevices.isTv) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val w = activity.window ?: return
                val cb = w.callback ?: return
                if (cb !is Tracker) w.callback = Tracker(w, cb)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** The popup is used everywhere but on a TV (D-pad navigation keeps the sheet). */
    fun wanted(activity: FragmentActivity) = org.videolan.vlc.gui.tablet.RdTvWindow.isOn(activity) ||
            (!AndroidDevices.isTv && activity.window?.callback is Tracker)

    /* ---- the groups of the desktop's menus ---- */

    private val PLAY = setOf(CTX_PLAY, CTX_QUICK_PLAY, CTX_PLAY_SHUFFLE, CTX_PLAY_FROM_START, CTX_PLAY_ALL, CTX_PLAY_AS_AUDIO,
            CTX_APPEND, CTX_PLAY_NEXT, CTX_STOP_AFTER_THIS)
    private val FIND = setOf(CTX_INFORMATION, CTX_DOWNLOAD_SUBTITLES, CTX_GO_TO_ALBUM, CTX_GO_TO_ARTIST, CTX_GO_TO_ALBUM_ARTIST,
            CTX_GO_TO_FOLDER, CTX_FIND_METADATA)
    private val REMOVE = setOf(CTX_DELETE, CTX_REMOVE_FROM_PLAYLIST, CTX_CUSTOM_REMOVE, CTX_BAN_FOLDER, CTX_UNGROUP, CTX_FAV_REMOVE)

    private fun group(id: ContextOption) = when (id) {
        in PLAY -> 0
        in FIND -> 1
        in REMOVE -> 3
        else -> 2
    }

    private var shown: WeakReference<PopupWindow>? = null

    /** Shows [items] as the desktop's menu; [onPick] gets the chosen one. */
    fun show(activity: FragmentActivity, items: List<CtxMenuItem>, onPick: (ContextOption) -> Unit) {
        if (items.isEmpty()) return
        shown?.get()?.dismiss()
        if (org.videolan.vlc.gui.tablet.RdTvWindow.isOn(activity)) {
            RdTvMenu.show(activity, items, onPick)?.let { shown = WeakReference(it) }
            return
        }
        val ctx: Context = activity
        val decor = activity.window.decorView
        val rtl = decor.layoutDirection == View.LAYOUT_DIRECTION_RTL
        // the desktop's groups, each in the order of VLC for Android's menu
        val ordered = items.withIndex().sortedWith(compareBy({ group(it.value.id) }, { it.index })).map { it.value }

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val inter = ResourcesCompat.getFont(ctx, R.font.inter)
        val textColor = UiTools.getColorFromAttribute(ctx, R.attr.font_default)
        val iconColor = UiTools.getColorFromAttribute(ctx, R.attr.font_default)
        val accent = ctx.rdAccent(0.24f)
        val popup = PopupWindow(ctx)
        var previous = -1
        for (item in ordered) {
            val g = group(item.id)
            if (previous >= 0 && g != previous) column.addView(View(ctx).apply {
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.rd_divider_dim))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 4.dp; bottomMargin = 4.dp })
            previous = g
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = 44.dp
                setPaddingRelative(12.dp, 0, 16.dp, 0)
                isClickable = true
                isFocusable = true
                contentDescription = item.title
                val mask = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(0xFFFFFFFF.toInt()) }
                // hover and press in the accent, as the desktop's menu rows
                background = RippleDrawable(ColorStateList.valueOf(accent), null, mask)
                setOnClickListener {
                    popup.dismiss()
                    onPick(item.id)
                }
            }
            if (item.icon != 0) row.addView(ImageView(ctx).apply {
                setImageResource(item.icon)
                imageTintList = ColorStateList.valueOf(iconColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(20.dp, 20.dp).apply { marginEnd = 12.dp })
            row.addView(TextView(ctx).apply {
                text = item.title
                typeface = inter
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(textColor)
                // the menu is as wide as its longest entry (up to 300 dp): a longer one (German, a large font) takes
                // more lines rather than an ellipsis, a long word ("Wiedergabewarteschlange") hyphenated
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                org.videolan.vlc.util.RdCompat.hyphenate(this)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(0, 4.dp, 0, 4.dp)
                maxWidth = (MAX_WIDTH_DP - 60).dp
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
            addView(column)
            background = ContextCompat.getDrawable(ctx, R.drawable.rd_menu_background)
            clipToOutline = true
        }

        // the size: its longest entry between 220 and 300 dp, at most 70 % of the window high
        val frame = Rect().also { decor.getWindowVisibleDisplayFrame(it) }
        val maxW = minOf(MAX_WIDTH_DP.dp, frame.width() - 16.dp)
        column.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val pad = scroll.paddingLeft + scroll.paddingRight
        val width = (column.measuredWidth + pad).coerceIn(minOf(MIN_WIDTH_DP.dp, maxW), maxW)
        val maxH = (frame.height() * MAX_HEIGHT_FRACTION).toInt()
        scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = minOf(scroll.measuredHeight, maxH)

        // where: the last touch of this window, else the middle
        val fresh = SystemClock.uptimeMillis() - lastTime < TOUCH_MAX_AGE_MS && lastWindow?.get() === activity.window
        val px = if (fresh) lastX.toInt() else frame.centerX()
        val py = if (fresh) lastY.toInt() else frame.centerY() - height / 2
        val margin = 8.dp
        // toward the end of the point (the right in LTR, the left in RTL); flipped when it would leave the window
        var x = if (!rtl) px else px - width
        if (x + width > frame.right - margin) x = px - width
        if (x < frame.left + margin) x = if (!rtl) frame.left + margin else minOf(px, frame.right - margin - width)
        x = x.coerceIn(frame.left + margin, maxOf(frame.left + margin, frame.right - margin - width))
        var y = py
        if (y + height > frame.bottom - margin) y = py - height
        y = y.coerceIn(frame.top + margin, maxOf(frame.top + margin, frame.bottom - margin - height))

        popup.apply {
            contentView = scroll
            this.width = width
            this.height = height
            isFocusable = true
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = 8.dp.toFloat()
            animationStyle = android.R.style.Animation_Dialog
        }
        // gone with the screen it was opened on
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = popup.dismiss()
        }
        activity.lifecycle.addObserver(observer)
        popup.setOnDismissListener { activity.lifecycle.removeObserver(observer) }
        popup.showAtLocation(decor, Gravity.TOP or Gravity.LEFT, x, y)
        shown = WeakReference(popup)
        column.getChildAt(0)?.let { first -> if (!fresh) first.requestFocus() }
    }
}
