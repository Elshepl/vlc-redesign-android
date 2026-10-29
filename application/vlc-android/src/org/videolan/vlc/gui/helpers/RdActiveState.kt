package org.videolan.vlc.gui.helpers

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import org.videolan.vlc.R

/**
 * VLC Redesign (#10, suggested in #10): the "open" state of a player button whose panel, sheet or dialog is open,
 * and the ON state of a toggle (rotation lock, repeat, lock...): the glyph in the accent and the dim accent square
 * behind it (rd_player_button_bg / rd_player_top_button_bg, state_activated), no border, both in 120 ms. The
 * keyboard / D-pad focus stays the neutral square of the ripple, drawn over it, so the two never look alike.
 *
 * One call for every button: [set]. The video player is always dark: the glyph is the accent lifted towards white
 * so that it keeps its contrast on the dim accent square with every accent of the settings.
 */
object RdActiveState {
    const val DURATION = 120L

    fun set(view: View?, active: Boolean) {
        view ?: return
        val known = view.getTag(R.id.rd_active_state) as? Boolean
        if (known == active && view.isActivated == active) return
        view.setTag(R.id.rd_active_state, active)
        view.isActivated = active
        val icon = view as? ImageView ?: return
        if (icon.getTag(R.id.rd_active_tint) == null) icon.setTag(R.id.rd_active_tint, icon.imageTintList ?: NO_TINT)
        val original = (icon.getTag(R.id.rd_active_tint) as? ColorStateList)?.takeIf { it !== NO_TINT }
        val idle = original?.defaultColor ?: themeColor(icon.context, R.attr.player_icon_color, Color.WHITE)
        val on = activeColor(icon.context)
        (icon.getTag(R.id.rd_active_anim) as? ValueAnimator)?.cancel()
        val from = icon.imageTintList?.defaultColor ?: idle
        val to = if (active) on else idle
        if (known == null && !active) return
        val anim = ValueAnimator.ofArgb(from, to).apply {
            duration = if (view.isShown) DURATION else 0L
            addUpdateListener { icon.imageTintList = ColorStateList.valueOf(it.animatedValue as Int) }
            doOnEnd { if (icon.getTag(R.id.rd_active_state) == false) icon.imageTintList = original }
        }
        icon.setTag(R.id.rd_active_anim, anim)
        anim.start()
    }

    /** The glyph of an open / ON button: the accent, 30 % towards white (readable on the dark bar and square). */
    fun activeColor(context: Context): Int =
        ColorUtils.blendARGB(themeColor(context, androidx.appcompat.R.attr.colorPrimary, Color.WHITE), Color.WHITE, 0.3f)

    private fun themeColor(context: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        if (!context.theme.resolveAttribute(attr, tv, true)) return fallback
        return if (tv.resourceId != 0) try { context.getColor(tv.resourceId) } catch (e: Exception) { tv.data } else tv.data
    }

    private val NO_TINT = ColorStateList.valueOf(0)
}
