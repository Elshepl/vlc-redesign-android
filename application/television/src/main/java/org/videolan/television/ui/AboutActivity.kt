package org.videolan.television.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import org.videolan.resources.util.applyOverscanMargin
import org.videolan.television.R
import org.videolan.vlc.gui.helpers.UiTools

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // VLC Redesign: the TV layout's About in the design's dark palette, whatever the phone's theme
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.onCreate(savedInstanceState)
        setContentView(R.layout.about)
        UiTools.fillAboutView(this, window.decorView.rootView)
        // the texts of links walk their links with Left and Right; Up and Down leave them for the rows around
        // (a link text keeps the D-pad otherwise, and the rows under the design's links could not be reached)
        for (id in intArrayOf(R.id.rd_design_links, R.id.rd_first_implementation, R.id.rd_built_on))
            window.decorView.findViewById<View>(id)?.setOnKeyListener(::leaveLinks)
        applyOverscanMargin(this)
    }

    override fun onResume() {
        super.onResume()
        rdTvFullScreen()
    }

    private fun leaveLinks(v: View, keyCode: Int, event: KeyEvent): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) v.focusSearch(direction)?.requestFocus()
        return true
    }
}
