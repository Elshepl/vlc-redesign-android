/*
 * VLC Redesign: the menus of the TV layout at the design's size (Figma "file context menu" 2404:13340, "playlist
 * context menu" 2459:9389, "Sorting Options" 856:2215, "menu" 6062:13354; the Samsung TV app's shell.css .sh-menu):
 * a panel of 265 (the main menu 375), padding 4, radius 10, #191919 at 95 %, its shadow; rows of 48 with 14 at the
 * sides, the two-colour icon of 24 (its grey part #8F8F8F, its other part in the accent) 10 before the text (Inter 14,
 * #8F8F8F), the chosen one with the accent's check at the end; the focused row #1F1F1F, radius 6. It opens beside the
 * focused row (11 clear of it) or under its button (8 below), toward the end, flipped when it would leave the page.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.dialogs

import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.rdAccent
import org.videolan.vlc.util.ContextOption
import org.videolan.vlc.util.ContextOption.*

object RdTvMenu {

    const val GREY = 0xFF8F8F8F.toInt()
    private const val PANEL = 0xF2191919.toInt()
    private const val FOCUS = 0xFF1F1F1F.toInt()
    private const val DIVIDER = 0xFF222222.toInt()

    /** An entry: its text, its design icon ("play": rd_tvd_duo_play + _a), checked (the accent's check), its action. */
    class Entry(val title: CharSequence, val icon: String? = null, val checked: Boolean = false, val enabled: Boolean = true,
                val separatorBefore: Boolean = false, val action: () -> Unit)

    /** The design's icon of an item's action (the desktop's menus) */
    fun iconOf(id: ContextOption): String? = when (id) {
        CTX_PLAY, CTX_QUICK_PLAY, CTX_PLAY_ALL, CTX_PLAY_FROM_START -> "play"
        CTX_PLAY_SHUFFLE -> "shuffle"
        CTX_PLAY_AS_AUDIO -> "audio"
        CTX_APPEND -> "queue"
        CTX_PLAY_NEXT -> "next"
        CTX_STOP_AFTER_THIS -> "stop"
        CTX_DOWNLOAD_SUBTITLES -> "subtitles"
        CTX_INFORMATION, CTX_FIND_METADATA -> "info"
        CTX_GO_TO_ALBUM -> "album"
        CTX_GO_TO_ARTIST, CTX_GO_TO_ALBUM_ARTIST -> "profile"
        CTX_ADD_TO_PLAYLIST, CTX_ADD_FOLDER_PLAYLIST, CTX_ADD_FOLDER_AND_SUB_PLAYLIST -> "playlist"
        CTX_FAV_ADD, CTX_FAV_REMOVE -> "favorite"
        CTX_FAV_EDIT, CTX_RENAME, CTX_RENAME_GROUP -> "edit"
        CTX_ADD_SCANNED, CTX_ADD_GROUP -> "folder_add"
        CTX_REMOVE_FROM_PLAYLIST, CTX_DELETE, CTX_CUSTOM_REMOVE, CTX_REMOVE_GROUP, CTX_UNGROUP -> "remove"
        CTX_BAN_FOLDER -> "close"
        CTX_COPY -> "file"
        CTX_SHARE -> "export"
        CTX_ADD_SHORTCUT -> "add"
        CTX_GROUP_SIMILAR -> "lists"
        CTX_MARK_AS_PLAYED, CTX_MARK_AS_UNPLAYED, CTX_MARK_ALL_AS_PLAYED, CTX_MARK_ALL_AS_UNPLAYED -> "mark_watched"
        CTX_GO_TO_FOLDER -> "folder_open"
        CTX_PIN, CTX_UNPIN -> "pin"
        else -> null
    }

    /** The order of the design's context menus: play, watched, favorite, shuffle, queue, playlist, go to, the rest,
     *  the properties, then what removes */
    private val ORDER = listOf(CTX_PLAY, CTX_PLAY_ALL, CTX_QUICK_PLAY, CTX_PLAY_FROM_START, CTX_MARK_AS_PLAYED, CTX_MARK_AS_UNPLAYED,
            CTX_MARK_ALL_AS_PLAYED, CTX_MARK_ALL_AS_UNPLAYED, CTX_FAV_ADD, CTX_FAV_REMOVE, CTX_FAV_EDIT, CTX_PLAY_SHUFFLE, CTX_PLAY_AS_AUDIO,
            CTX_APPEND, CTX_PLAY_NEXT, CTX_STOP_AFTER_THIS, CTX_ADD_TO_PLAYLIST, CTX_ADD_FOLDER_PLAYLIST, CTX_ADD_FOLDER_AND_SUB_PLAYLIST,
            CTX_GO_TO_FOLDER, CTX_GO_TO_ALBUM, CTX_GO_TO_ARTIST, CTX_GO_TO_ALBUM_ARTIST, CTX_DOWNLOAD_SUBTITLES)
    private val LAST = listOf(CTX_INFORMATION, CTX_FIND_METADATA, CTX_REMOVE_FROM_PLAYLIST, CTX_CUSTOM_REMOVE, CTX_BAN_FOLDER,
            CTX_REMOVE_GROUP, CTX_UNGROUP, CTX_DELETE)

    private fun rank(id: ContextOption): Int {
        val i = ORDER.indexOf(id)
        if (i >= 0) return i
        val j = LAST.indexOf(id)
        return if (j >= 0) 1000 + j else 500
    }

    /** An item's context menu (the long OK, its "..." button) */
    fun show(activity: FragmentActivity, items: List<CtxMenuItem>, onPick: (ContextOption) -> Unit): PopupWindow? {
        val ordered = items.withIndex().sortedWith(compareBy({ rank(it.value.id) }, { it.index })).map { it.value }
        return show(activity, activity.currentFocus, ordered.map { item -> Entry(item.title, iconOf(item.id)) { onPick(item.id) } })
    }

    /** The two parts of a design icon: the grey one, the accent one over it */
    fun icon(activity: FragmentActivity, name: String, grey: Int = GREY): Drawable? {
        val res = activity.resources
        val pkg = activity.packageName
        val layers = mutableListOf<Drawable>()
        res.getIdentifier("rd_tvd_duo_$name", "drawable", pkg).takeIf { it != 0 }?.let { id ->
            ContextCompat.getDrawable(activity, id)?.mutate()?.apply { setTint(grey) }?.let { layers.add(it) }
        }
        res.getIdentifier("rd_tvd_duo_${name}_a", "drawable", pkg).takeIf { it != 0 }?.let { id ->
            ContextCompat.getDrawable(activity, id)?.mutate()?.apply { setTint(activity.rdAccent()) }?.let { layers.add(it) }
        }
        return when (layers.size) {
            0 -> null
            1 -> layers[0]
            else -> LayerDrawable(layers.toTypedArray())
        }
    }

    /**
     * [entries] as the design's menu at [anchor] (beside a row, under a button); [width] the panel's (265, the main
     * menu's 375).
     */
    fun show(activity: FragmentActivity, anchor: View?, entries: List<Entry>, width: Int = 265, below: Boolean? = null): PopupWindow? {
        // (a window that is going away can't show a popup: BadTokenException)
        if (entries.isEmpty() || activity.isFinishing || activity.isDestroyed || !activity.window.decorView.isAttachedToWindow) return null
        val ctx = activity
        val inter = ResourcesCompat.getFont(ctx, R.font.inter)
        val popup = PopupWindow(ctx)
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        var checkedRow: View? = null
        for (e in entries) {
            if (e.separatorBefore && column.childCount > 0) column.addView(View(ctx).apply { setBackgroundColor(DIVIDER) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp))
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(14.dp, 0, 14.dp, 0)
                isFocusable = e.enabled
                isClickable = e.enabled
                contentDescription = e.title
                background = StateListDrawable().apply {
                    val on = GradientDrawable().apply { cornerRadius = 6.dp.toFloat(); setColor(FOCUS) }
                    addState(intArrayOf(android.R.attr.state_focused), on)
                    addState(intArrayOf(android.R.attr.state_pressed), on)
                    addState(intArrayOf(), ColorDrawable(0))
                }
                setOnClickListener {
                    popup.dismiss()
                    e.action()
                }
            }
            e.icon?.let { name -> icon(activity, name, if (e.enabled) GREY else 0xFF404040.toInt()) }?.let { d ->
                row.addView(ImageView(ctx).apply {
                    setImageDrawable(d)
                    if (!e.enabled) alpha = 0.5f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginEnd = 10.dp })
            }
            row.addView(TextView(ctx).apply {
                text = e.title
                typeface = inter
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                setTextColor(if (e.enabled) GREY else 0xFF404040.toInt())
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (e.checked) row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.rd_tvd_widgets_check)
                setColorFilter(activity.rdAccent())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginStart = 10.dp })
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp))
            if (e.enabled && first == null) first = row
            if (e.enabled && e.checked && checkedRow == null) checkedRow = row
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            background = GradientDrawable().apply { cornerRadius = 10.dp.toFloat(); setColor(PANEL) }
            clipToOutline = true
        }
        // where: beside the focused row (11 clear), under a button (8 below), toward the end; inside the page
        val decor = activity.window.decorView
        val frame = Rect().also { decor.getWindowVisibleDisplayFrame(it) }
        val w = width.dp
        scroll.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val margin = 8.dp
        val h = minOf(scroll.measuredHeight, frame.height() - 2 * margin)
        val rtl = decor.layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x: Int
        var y: Int
        if (anchor != null && anchor.isAttachedToWindow) {
            val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
            val under = below ?: (anchor.width < 120.dp)
            if (under) {
                // from the button's start in the page's first half (the title bar's menu), else to its end
                val startSide = (at[0] + anchor.width / 2 < frame.centerX()) != rtl
                x = if (startSide == !rtl) at[0] else at[0] + anchor.width - w
                y = at[1] + anchor.height + 8.dp
                if (y + h > frame.bottom - margin) y = at[1] - 8.dp - h
            } else {
                x = if (!rtl) at[0] + anchor.width + 11.dp else at[0] - 11.dp - w
                if (x + w > frame.right - margin || x < frame.left + margin) x = if (!rtl) at[0] + anchor.width - w - 11.dp else at[0] + 11.dp
                y = at[1]
            }
        } else {
            x = frame.centerX() - w / 2
            y = frame.centerY() - h / 2
        }
        x = x.coerceIn(frame.left + margin, maxOf(frame.left + margin, frame.right - margin - w))
        y = y.coerceIn(frame.top + margin, maxOf(frame.top + margin, frame.bottom - margin - h))
        popup.apply {
            contentView = scroll
            this.width = w
            this.height = h
            isFocusable = true
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(0))
            elevation = 15.dp.toFloat()
            animationStyle = 0
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = popup.dismiss()
        }
        activity.lifecycle.addObserver(observer)
        val before = activity.currentFocus
        popup.setOnDismissListener {
            activity.lifecycle.removeObserver(observer)
            if (before?.isAttachedToWindow == true) before.requestFocus()
        }
        popup.showAtLocation(decor, Gravity.TOP or Gravity.LEFT, x, y)
        (checkedRow ?: first)?.requestFocus()
        return popup
    }
}
