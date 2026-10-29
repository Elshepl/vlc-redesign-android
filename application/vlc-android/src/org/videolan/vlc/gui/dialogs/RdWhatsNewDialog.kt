/*
 * VLC Redesign: the What's new card of this release, as the desktop's (shown once after an update, opened again
 * from About).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.dialogs

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import org.videolan.tools.KEY_SHOW_WHATS_NEW
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R

class RdWhatsNewDialog : VLCBottomSheetDialogFragment() {
    override fun getDefaultState(): Int = STATE_EXPANDED

    override fun needToManageOrientation(): Boolean = false

    private lateinit var title: TextView

    override fun initialFocusedView(): View = title

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.dialog_rd_whats_new, container, false)
        title = root.findViewById(R.id.rd_whats_new_title)
        title.text = getString(R.string.rd_about_whats_new, BuildConfig.RD_VERSION_NAME)
        val list = root.findViewById<LinearLayout>(R.id.rd_whats_new_list)
        val ctx = requireContext()
        resources.getStringArray(R.array.rd_whats_new_items).forEach { item ->
            list.addView(TextView(ctx).apply {
                text = "•  $item"
                setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_2))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = ResourcesCompat.getFont(ctx, R.font.inter)
                setLineSpacing(0f, 1.3f)
                // an item not translated yet reads in its own direction
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                setPadding(0, 6.dp, 0, 6.dp)
            })
        }
        root.findViewById<CheckBox>(R.id.rd_whats_new_never).apply {
            isChecked = !Settings.getInstance(ctx).getBoolean(KEY_SHOW_WHATS_NEW, true)
            setOnCheckedChangeListener { _, isChecked -> Settings.getInstance(ctx).putSingle(KEY_SHOW_WHATS_NEW, !isChecked) }
        }
        return root
    }
}
