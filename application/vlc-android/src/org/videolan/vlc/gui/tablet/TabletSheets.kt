/*
 * ************************************************************************
 *  TabletSheets.kt
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

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.UiTools

/**
 * VLC Redesign, tablets: on a large screen a bottom sheet (context menu, sort and display options, speed, sleep
 * timer...) is a centred framed window, as the desktop's menus and dialogs, not a panel glued to the bottom edge
 * and as tall as the screen. Its width stays the sheet's width (`default_context_width`), its height follows its
 * content up to 78 % of the window, and it floats in the middle with the window border and full radius.
 */
object TabletSheets {

    private const val MAX_HEIGHT_FRACTION = 0.78f

    fun wanted(dialog: Dialog?) = dialog != null && dialog.context.resources.getBoolean(R.bool.is_tablet)

    fun applyCentered(dialog: Dialog, view: View) {
        val sheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val parent = sheet.parent as? View ?: return
        dialog.window?.setGravity(Gravity.CENTER)
        sheet.background = null
        // the sheet's own colour (light or dark theme) in the window frame: radius 14, 1 px window border
        val ctx = view.context
        view.background = GradientDrawable().apply {
            cornerRadius = 14.dp.toFloat()
            // opaque: the player's sheet colour is see-through, a centred window over the video must not be
            setColor(UiTools.getColorFromAttribute(ctx, R.attr.bottom_sheet_background) or 0xFF000000.toInt())
            setStroke(1, ContextCompat.getColor(ctx, R.color.rd_window_border_token))
        }
        view.clipToOutline = true
        val behavior = BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            isDraggable = false
            isFitToContents = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        // the behaviour keeps the sheet on the bottom edge: lift it so it is centred in the window
        val centre = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val max = (parent.height * MAX_HEIGHT_FRACTION).toInt()
            if (max > 0) {
                if (behavior.maxHeight != max) behavior.maxHeight = max
                // a list filled after the first layout can grow past the cap: keep it
                if (sheet.height > max) sheet.post { sheet.layoutParams = sheet.layoutParams.apply { height = max } }
            }
            val lift = -((parent.height - sheet.height) / 2f).coerceAtLeast(0f)
            if (sheet.translationY != lift) sheet.translationY = lift
        }
        sheet.addOnLayoutChangeListener(centre)
        parent.addOnLayoutChangeListener(centre)
        if (view.layoutParams?.height == ViewGroup.LayoutParams.MATCH_PARENT) view.layoutParams = view.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
    }

    /**
     * A full-screen picker (the media library's folders) as a centred framed window over the screen it came
     * from, as the desktop's dialog: 760 dp wide at most, 84 % of the height, the window border, the screen
     * behind dimmed, a tap outside closes it. API 30+ (the activity is made translucent at run time).
     */
    fun floatActivity(activity: android.app.Activity) {
        if (android.os.Build.VERSION.SDK_INT < 30 || !activity.resources.getBoolean(R.bool.is_tablet)) return
        if (org.videolan.resources.AndroidDevices.isTv) return
        if (!activity.setTranslucent(true)) return
        val window = activity.window
        val dm = activity.resources.displayMetrics
        window.setLayout(minOf(760.dp, (dm.widthPixels * 0.9f).toInt()), (dm.heightPixels * 0.84f).toInt())
        window.setGravity(Gravity.CENTER)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.5f)
        window.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = 14.dp.toFloat()
            setColor(UiTools.getColorFromAttribute(activity, R.attr.background_default))
            setStroke(1, ContextCompat.getColor(activity, R.color.rd_window_border_token))
        })
        window.decorView.clipToOutline = true
        activity.setFinishOnTouchOutside(true)
    }
}
