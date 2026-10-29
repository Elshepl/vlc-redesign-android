/*
 * VLC Redesign: the cast list, the desktop's cast panel (CastButton.qml) as a bottom sheet (a centred framed
 * window on a tablet). It replaces the old "Displays" dialog.
 *
 * "Cast to" with a dot while it looks for devices; this phone (checked when it plays here); each device once,
 * a TV or a speaker ("Sound only"), checked when it plays there, "Connecting…" until it does, "Not playing:
 * choose it to try again" when it failed; "Looking for TVs and Chromecasts on your network…", then "No device
 * found…" when there are none. The whole row chooses; the sheet closes on a choice. The devices are looked for
 * while it shows.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.dialogs

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.RendererItem
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.RendererDelegate
import org.videolan.vlc.cast.CastController
import org.videolan.vlc.cast.CastState
import org.videolan.vlc.cast.canVideo
import org.videolan.vlc.cast.sameDevice
import org.videolan.vlc.gui.helpers.UiTools.isTablet
import org.videolan.vlc.gui.helpers.UiTools.showPinIfNeeded
import org.videolan.vlc.gui.helpers.rdAccent

class CastSheet : VLCBottomSheetDialogFragment() {

    private lateinit var title: TextView
    private lateinit var rows: LinearLayout
    private lateinit var empty: TextView
    private lateinit var dot: View
    private lateinit var scroll: NestedScrollView
    private var dotAnimation: ValueAnimator? = null
    private var searchJob: Job? = null
    private var shown = ""

    override fun getDefaultState() = BottomSheetBehavior.STATE_EXPANDED
    override fun needToManageOrientation() = false
    override fun initialFocusedView(): View = title
    // the player's landscape side sheet would be too tall for a short list
    override fun useSideSheet() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { if (requireActivity().showPinIfNeeded()) dismiss() }
        // the devices are looked for while the list shows
        RendererDelegate.observe(this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.dialog_rd_cast, container, false)
        title = root.findViewById(R.id.rd_cast_title)
        rows = root.findViewById(R.id.rd_cast_rows)
        empty = root.findViewById(R.id.rd_cast_empty)
        dot = root.findViewById(R.id.rd_cast_scanning)
        scroll = root.findViewById(R.id.rd_cast_scroll)
        shown = ""
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        RendererDelegate.renderers.observe(viewLifecycleOwner) { update() }
        RendererDelegate.scanning.observe(viewLifecycleOwner) { update() }
        CastState.live.observe(viewLifecycleOwner) { update() }
        // "Looking for…" until the search has run a while, then "No device found…"
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(1000L)
                update()
            }
        }
        update()
    }

    override fun onDestroyView() {
        dotAnimation?.cancel()
        dotAnimation = null
        super.onDestroyView()
    }

    private fun searchedLong() = SystemClock.elapsedRealtime() - RendererDelegate.scanStartedAt > RendererDelegate.SEARCH_TIME

    private fun update() {
        if (!isAdded || view == null) return
        val ctx = requireContext()
        val devices = RendererDelegate.renderers.getList()
        val state = CastState.state.value
        val current = PlaybackService.renderer.value
        val scanning = RendererDelegate.scanning.value == true

        // the dot breathes while it looks
        dot.isVisible = scanning
        if (scanning && dotAnimation == null) dotAnimation = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.25f).apply {
            duration = 600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        } else if (!scanning) {
            dotAnimation?.cancel()
            dotAnimation = null
        }

        // the rows again only when they change (not under a finger each second)
        val list = if (current != null && devices.none { sameDevice(it, current) }) listOf(current) + devices else devices
        empty.isVisible = list.isEmpty()
        empty.text = getString(if (scanning && !searchedLong()) R.string.rd_cast_looking else R.string.rd_cast_none_found)
        val signature = "${state}|${current?.displayName}|" + list.joinToString("|") { it.displayName + it.canVideo() }
        if (signature == shown) return
        shown = signature
        rows.removeAllViews()
        addRow(getString(if (ctx.isTablet()) R.string.rd_cast_this_tablet else R.string.rd_cast_this_phone), R.drawable.rd_ic_phone,
                null, false, current == null) {
            if (current != null) CastController.playHere()
            dismissAllowingStateLoss()
        }
        // (the device playing stays listed even when the search lost it)
        for (item in list) {
            val selected = sameDevice(item, current)
            val failed = selected && state.status == CastState.Status.FAILED
            val detail = when {
                failed -> getString(R.string.rd_cast_not_playing_retry)
                selected && state.status == CastState.Status.CONNECTING -> getString(R.string.rd_cast_connecting)
                !item.canVideo() -> getString(R.string.rd_cast_sound_only)
                else -> null
            }
            addRow(item.displayName, if (item.canVideo()) R.drawable.rd_ic_tv else R.drawable.rd_ic_music, detail, failed, selected) {
                // the one it plays on: again only when it failed
                if (!selected) choose(item) else if (failed) CastController.retry()
                dismissAllowingStateLoss()
            }
        }
    }

    private fun choose(item: RendererItem) {
        PlaybackService.renderer.value = item
    }

    private fun addRow(name: String, icon: Int, detail: String?, alert: Boolean, selected: Boolean, onClick: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_rd_cast_device, rows, false)
        val accent = requireContext().rdAccent()
        row.findViewById<TextView>(R.id.rd_cast_row_name).text = name
        row.findViewById<ImageView>(R.id.rd_cast_row_icon).apply {
            setImageResource(icon)
            ImageViewCompat.setImageTintList(this, if (selected) ColorStateList.valueOf(accent) else null)
        }
        row.findViewById<TextView>(R.id.rd_cast_row_detail).apply {
            isVisible = detail != null
            text = detail
            if (alert) setTextColor(accent)
        }
        row.findViewById<ImageView>(R.id.rd_cast_row_check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        row.isSelected = selected
        row.contentDescription = listOfNotNull(name, detail).joinToString(", ")
        row.setOnClickListener { onClick() }
        rows.addView(row)
    }

    companion object {
        private const val TAG = "cast"

        /** the cast list, once */
        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null && !manager.isStateSaved) CastSheet().show(manager, TAG)
        }
    }
}
