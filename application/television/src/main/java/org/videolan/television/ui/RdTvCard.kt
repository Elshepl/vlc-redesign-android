/*
 * VLC Redesign: a TV card with the design's focus, an accent frame around it.
 *
 * Leanback's card filters the focus out of its drawable states and the rows replace the focus listeners, so the
 * card colours its own inset background when it gets or loses the focus (or Leanback selects it).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.television.ui

import android.content.Context
import android.graphics.Rect
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import org.videolan.vlc.R

class RdTvCard(context: Context) : ImageCardView(context) {
    private val accent = ContextCompat.getColor(context, R.color.rd_accent_orange_main)
    private val plain = ContextCompat.getColor(context, R.color.tv_card_content_dark)

    init {
        (FRAME_DP * resources.displayMetrics.density).toInt().let { setPadding(it, it, it, it) }
        setBackgroundColor(plain)
        // the picture keeps the card's colour: only the frame around it takes the accent
        mainImageView?.setBackgroundColor(plain)
    }

    private fun update() = setBackgroundColor(if (hasFocus() || isSelected) accent else plain)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        update()
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        update()
    }

    private companion object {
        const val FRAME_DP = 3
    }
}
