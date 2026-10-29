/*
 * VLC Redesign: Customize Player, the desktop's toolbar editor (ToolbarEditorDialog.qml, Figma "Player -
 * customize") for a touch screen: the bar's left, center and right zones in their edit-mode outlines, the
 * catalogue of controls under them, a layout for portrait (two rows) and one for landscape (one row), in
 * profiles (the desktop's five, and the user's: new, rename, delete; reset).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.dialogs

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.util.PlayerBarLayout
import org.videolan.vlc.util.PlayerBarLayout.Profile
import org.videolan.vlc.util.PlayerBarLayout.Zones

class CustomizePlayerDialog : VLCBottomSheetDialogFragment() {
    override fun getDefaultState(): Int = STATE_EXPANDED

    override fun needToManageOrientation(): Boolean = true

    private lateinit var title: TextView
    private lateinit var profileButton: TextView
    private lateinit var tabs: List<TextView>
    private lateinit var zoneBoxes: List<LinearLayout>
    private lateinit var zoneRows: List<LinearLayout>
    private lateinit var saveButton: TextView

    private val profiles = ArrayList<Profile>()
    private var selected = "Default"
    private var wide = false
    private var zone = 1
    private var changed = false

    override fun initialFocusedView(): View = title

    /**
     * VLC Redesign, tablets: the desktop's editor, a centred window with the three zones side by side (a side
     * sheet stacks them)
     */
    private val sideBySide get() = resources.getBoolean(R.bool.is_tablet) && resources.configuration.screenWidthDp >= 720

    override fun useSideSheet() = !sideBySide && super.useSideSheet()

    override fun windowWidth() = if (sideBySide) minOf(920.dp, (resources.displayMetrics.widthPixels * 0.92f).toInt()) else super.windowWidth()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val prefs = Settings.getInstance(ctx)
        profiles.clear()
        profiles.addAll(PlayerBarLayout.profiles(prefs))
        selected = PlayerBarLayout.current(prefs).name
        wide = resources.configuration.screenWidthDp >= 600
        val inter = ResourcesCompat.getFont(ctx, R.font.inter)
        val medium = ResourcesCompat.getFont(ctx, R.font.inter_medium)
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(ContextCompat.getColor(ctx, color))
            typeface = if (bold) medium else inter
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 12.dp)
        }
        title = text(18f, R.color.rd_dark_fg_1, true).apply { setText(R.string.rd_customize_player) }
        column.addView(title)
        column.addView(text(13f, R.color.rd_dark_fg_2).apply {
            setText(R.string.rd_customize_hint)
            setLineSpacing(0f, 1.3f)
            setPadding(0, 6.dp, 0, 0)
        })

        // profile, and the layout being edited: portrait or landscape
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 14.dp, 0, 8.dp) }
        profileButton = text(14f, R.color.rd_dark_fg_1, true).apply {
            setBackgroundResource(R.drawable.rd_badge_background)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            setOnClickListener { showProfileMenu(it) }
        }
        row.addView(profileButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8.dp })
        tabs = listOf(R.string.screen_orientation_portrait, R.string.screen_orientation_landscape).mapIndexed { i, label ->
            text(13f, R.color.rd_dark_fg_2, true).apply {
                setText(label)
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                setOnClickListener { wide = i == 1; refresh() }
                row.addView(this)
            }
        }
        column.addView(row)

        // the bar in edit mode: three zones, the one that receives in the accent
        val labels = listOf(R.string.rd_zone_left, R.string.rd_zone_center, R.string.rd_zone_right)
        zoneRows = ArrayList()
        // left, center, right as on the bar, which keeps its order in right-to-left languages
        val zoneParent = if (sideBySide) LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; column.addView(this) } else column
        zoneBoxes = labels.mapIndexed { i, label ->
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10.dp, 6.dp, 10.dp, 6.dp)
                addView(text(11f, R.color.rd_dark_fg_a2, true).apply { text = getString(label).uppercase(); letterSpacing = 0.2f })
                // the bar keeps its order in right to left languages (as the desktop's): so does its picture
                val icons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; minimumHeight = 48.dp; layoutDirection = View.LAYOUT_DIRECTION_LTR }
                (zoneRows as ArrayList).add(icons)
                addView(HorizontalScrollView(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; addView(icons) })
                setOnClickListener { zone = i; refresh() }
                // drag and drop, as the desktop's editor: a control dropped in a zone goes where it was let go
                setOnDragListener { _, e -> onZoneDrag(i, icons, e) }
                if (sideBySide) zoneParent.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { topMargin = 6.dp; if (i > 0) marginStart = 8.dp })
                else column.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp })
            }
        }

        // the catalogue
        val grid = GridLayout(ctx).apply { columnCount = if (sideBySide) 8 else 4; setPadding(0, 14.dp, 0, 0) }
        PlayerBarLayout.CATALOGUE.forEach { c ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(4.dp, 8.dp, 4.dp, 8.dp)
                setBackgroundResource(R.drawable.rd_player_button_bg)
                contentDescription = getString(c.label)
                addView(icon(c.icon), LinearLayout.LayoutParams(24.dp, 24.dp))
                addView(text(11f, R.color.rd_dark_fg_2).apply {
                    text = getString(c.label)
                    gravity = Gravity.CENTER
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    maxLines = 2
                    setPadding(0, 6.dp, 0, 0)
                })
                setOnClickListener { add(c.key) }
                setOnLongClickListener { v -> startDrag(v, c.key); true }
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        column.addView(grid)

        // Cancel and Save, as the desktop's dialog
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, 12.dp, 0, 0) }
        buttons.addView(text(14f, R.color.rd_dark_fg_1, true).apply {
            setText(R.string.rd_cancel)
            setPadding(16.dp, 10.dp, 16.dp, 10.dp)
            setBackgroundResource(R.drawable.rd_player_button_bg)
            setOnClickListener { dismiss() }
        })
        saveButton = text(14f, R.color.rd_dark_fg_b1, true).apply {
            setText(R.string.rd_save)
            setPadding(16.dp, 10.dp, 16.dp, 10.dp)
            setBackgroundResource(R.drawable.rd_badge_background)
            backgroundTintList = ColorStateList.valueOf(accent())
            setOnClickListener { save() }
        }
        buttons.addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 8.dp })
        column.addView(buttons)

        refresh()
        return NestedScrollView(ctx).apply { addView(column) }
    }

    private fun accent(): Int = TypedValue().also { requireContext().theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, it, true) }.data

    private fun icon(res: Int) = ImageView(requireContext()).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.rd_dark_fg_a2))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private fun profile() = profiles.first { it.name == selected }
    private fun zones() = if (wide) profile().landscape else profile().portrait

    private fun setZones(z: Zones) {
        val p = profile()
        profiles[profiles.indexOf(p)] = if (wide) Profile(p.name, p.portrait, z, p.builtIn) else Profile(p.name, z, p.landscape, p.builtIn)
        changed = true
        refresh()
    }

    private fun lists(z: Zones) = arrayListOf(z.left.toMutableList(), z.center.toMutableList(), z.right.toMutableList())
    private fun zonesOf(l: List<List<String>>) = Zones(l[0], l[1], l[2])

    /** a control of the catalogue goes to the end of the chosen zone (from where it was) */
    private fun add(key: String) {
        val l = lists(zones())
        l.forEach { it.remove(key) }
        l[zone].add(key)
        setZones(zonesOf(l))
    }

    private fun refresh() {
        val name = profile().name
        val label = PlayerBarLayout.builtInLabel(name).let { if (it != 0) getString(it) else name }
        profileButton.text = getString(R.string.rd_profile) + ": " + label + "  ▾"
        tabs.forEachIndexed { i, t ->
            val on = (i == 1) == wide
            t.setTextColor(if (on) accent() else ContextCompat.getColor(requireContext(), R.color.rd_dark_fg_2))
            t.setBackgroundResource(if (on) R.drawable.rd_badge_background else 0)
        }
        val z = zones()
        listOf(z.left, z.center, z.right).forEachIndexed { i, keys ->
            zoneBoxes[i].setBackgroundResource(if (i == zone) R.drawable.rd_zone_box_selected else R.drawable.rd_zone_box)
            val icons = zoneRows[i]
            icons.removeAllViews()
            keys.forEachIndexed { pos, key ->
                val c = PlayerBarLayout.control(key) ?: return@forEachIndexed
                icons.addView(FrameLayout(requireContext()).apply {
                    setBackgroundResource(R.drawable.rd_player_button_bg)
                    contentDescription = getString(c.label)
                    androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
                    val ic = if (key == "play") ImageView(context).apply { setImageResource(R.drawable.ic_play_player); scaleType = ImageView.ScaleType.FIT_CENTER } else icon(c.icon)
                    addView(ic, FrameLayout.LayoutParams(if (key == "play") 54.dp else 24.dp, if (key == "play") 28.dp else 24.dp, Gravity.CENTER))
                    setOnClickListener { v -> showItemMenu(v, i, pos, key) }
                    setOnLongClickListener { v -> startDrag(v, key); true }
                }, LinearLayout.LayoutParams(if (key == "play") 62.dp else 44.dp, 44.dp))
            }
        }
        saveButton.isEnabled = changed
        saveButton.alpha = if (changed) 1f else 0.4f
    }

    private fun startDrag(v: View, key: String) {
        v.startDragAndDrop(android.content.ClipData.newPlainText("control", key), View.DragShadowBuilder(v), key, 0)
    }

    /** A control dropped in zone [zoneIndex]: before the control under the finger (the zone's end after the last) */
    private fun onZoneDrag(zoneIndex: Int, icons: LinearLayout, e: android.view.DragEvent): Boolean {
        val key = e.localState as? String ?: return false
        when (e.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> return true
            android.view.DragEvent.ACTION_DRAG_ENTERED -> { zone = zoneIndex; refresh() }
            android.view.DragEvent.ACTION_DROP -> {
                // the drop point in the row of icons (the zone box has a padding, the row may be scrolled)
                val loc = IntArray(2).also { icons.getLocationOnScreen(it) }
                val box = IntArray(2).also { zoneBoxes[zoneIndex].getLocationOnScreen(it) }
                val x = e.x + box[0] - loc[0]
                var at = icons.childCount
                for (c in 0 until icons.childCount) {
                    val child = icons.getChildAt(c)
                    if (x < child.left + child.width / 2) { at = c; break }
                }
                val l = lists(zones())
                val from = l.indexOfFirst { key in it }
                if (from == zoneIndex) {
                    val old = l[from].indexOf(key)
                    if (old < at) at--
                }
                l.forEach { it.remove(key) }
                l[zoneIndex].add(at.coerceIn(0, l[zoneIndex].size), key)
                zone = zoneIndex
                setZones(zonesOf(l))
                return true
            }
        }
        return true
    }

    private fun showItemMenu(anchor: View, zoneIndex: Int, pos: Int, key: String) {
        PopupMenu(requireContext(), anchor).apply {
            val keys = lists(zones())[zoneIndex]
            if (pos > 0) menu.add(0, 1, 0, R.string.rd_move_earlier)
            if (pos < keys.size - 1) menu.add(0, 2, 1, R.string.rd_move_later)
            if (key != "play") menu.add(0, 3, 2, R.string.rd_remove)
            setOnMenuItemClickListener { item ->
                val l = lists(zones())
                val list = l[zoneIndex]
                when (item.itemId) {
                    1 -> { list.removeAt(pos); list.add(pos - 1, key) }
                    2 -> { list.removeAt(pos); list.add(pos + 1, key) }
                    3 -> list.removeAt(pos)
                }
                setZones(zonesOf(l))
                true
            }
            show()
        }
    }

    private fun showProfileMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            profiles.forEachIndexed { i, p ->
                val label = PlayerBarLayout.builtInLabel(p.name).let { if (it != 0) getString(it) else p.name }
                menu.add(0, 100 + i, i, label).apply { isCheckable = true; isChecked = p.name == selected }
            }
            menu.add(1, 1, 100, R.string.rd_new_profile)
            if (!profile().builtIn) {
                menu.add(1, 2, 101, R.string.rd_rename_profile)
                menu.add(1, 3, 102, R.string.rd_delete_profile)
            }
            menu.add(1, 4, 103, R.string.rd_reset)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> askName(getString(R.string.rd_new_profile), "") { name ->
                        val p = profile()
                        profiles.add(Profile(name, p.portrait, p.landscape, false))
                        selected = name
                        changed = true
                        refresh()
                    }
                    2 -> askName(getString(R.string.rd_rename_profile), selected) { name ->
                        val p = profile()
                        profiles[profiles.indexOf(p)] = Profile(name, p.portrait, p.landscape, false)
                        selected = name
                        changed = true
                        refresh()
                    }
                    3 -> AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.rd_delete_named, selected))
                            .setPositiveButton(R.string.rd_delete_profile) { _, _ ->
                                profiles.remove(profile())
                                selected = profiles.first().name
                                changed = true
                                refresh()
                            }
                            .setNegativeButton(R.string.rd_cancel, null)
                            .show()
                    4 -> {
                        val p = profile()
                        val b = PlayerBarLayout.BUILT_IN.firstOrNull { it.name == p.name } ?: PlayerBarLayout.BUILT_IN.first()
                        profiles[profiles.indexOf(p)] = Profile(p.name, b.portrait, b.landscape, p.builtIn)
                        changed = true
                        refresh()
                    }
                    else -> {
                        selected = profiles[item.itemId - 100].name
                        changed = true
                        refresh()
                    }
                }
                true
            }
            show()
        }
    }

    private fun askName(titleText: String, start: String, done: (String) -> Unit) {
        val field = EditText(requireContext()).apply {
            setText(start)
            hint = getString(R.string.rd_profile_name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(requireContext()).apply { setPadding(24.dp, 8.dp, 24.dp, 0); addView(field) }
        AlertDialog.Builder(requireContext())
                .setTitle(titleText)
                .setView(box)
                .setPositiveButton(R.string.rd_save) { _, _ ->
                    val name = field.text.toString().trim()
                    if (name.isNotEmpty() && profiles.none { it.name == name }) done(name)
                }
                .setNegativeButton(R.string.rd_cancel, null)
                .show()
    }

    private fun save() {
        PlayerBarLayout.save(Settings.getInstance(requireContext()), profiles, selected)
        (activity as? VideoPlayerActivity)?.overlayDelegate?.refreshBarLayout()
        dismiss()
    }
}
