/*
 * *************************************************************************
 *  Navigator.kt
 * **************************************************************************
 *  Copyright © 2018-2019 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.helpers

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.EXTRA_FOR_ESPRESSO
import org.videolan.resources.EXTRA_TARGET
import org.videolan.resources.ID_AUDIO
import org.videolan.resources.ID_DIRECTORIES
import org.videolan.resources.ID_VIDEO
import org.videolan.resources.util.parcelableList
import org.videolan.tools.KEY_FRAGMENT_ID
import org.videolan.tools.isStarted
import org.videolan.tools.setGone
import org.videolan.tools.setVisible
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.gui.BaseFragment
import org.videolan.vlc.gui.MainActivity
import org.videolan.vlc.gui.HomeFragment
import org.videolan.vlc.gui.MoreFragment
import org.videolan.vlc.gui.PlaylistFragment
import org.videolan.vlc.gui.audio.AudioBrowserFragment
import org.videolan.vlc.gui.browser.BaseBrowserFragment
import org.videolan.vlc.gui.browser.MainBrowserFragment
import org.videolan.vlc.gui.helpers.UiTools.isTablet
import org.videolan.vlc.gui.helpers.UiTools.useNavigationRail
import org.videolan.vlc.gui.tablet.TabletSidebar
import org.videolan.vlc.gui.video.VideoBrowserFragment
import org.videolan.vlc.util.getScreenWidth

private const val TAG = "Navigator"

class Navigator : NavigationBarView.OnItemSelectedListener, DefaultLifecycleObserver, INavigator {

    private val defaultFragmentId = R.id.nav_home
    override var currentFragmentId: Int = 0
    private var currentFragment: Fragment? = null
    private lateinit var activity: MainActivity
    private lateinit var settings: SharedPreferences
    override lateinit var navigationView: List<NavigationBarView>
    override lateinit var appbarLayout: AppBarLayout
    private var forExpresso: ArrayList<MediaLibraryItem>? = null


    override fun MainActivity.setupNavigation(state: Bundle?) {
        activity = this
        this@Navigator.settings = settings
        forExpresso = intent.parcelableList(EXTRA_FOR_ESPRESSO)
        currentFragmentId = intent.getIntExtra(EXTRA_TARGET, 0)
        if (state !== null) {
            currentFragment = supportFragmentManager.getFragment(state, "current_fragment")
        }
        lifecycle.addObserver(this@Navigator)
        navigationView = listOf(findViewById(R.id.navigation), findViewById(R.id.navigation_rail))
        // VLC Redesign: the icons carry their own colours (the active one is filled in the accent with a cut-out)
        navigationView.forEach { it.itemIconTintList = null }
        appbarLayout = findViewById(R.id.appbar)
        // VLC Redesign: the pages in the user's order, TV while the Live TV add-on is on (gui/livetv)
        org.videolan.vlc.gui.livetv.TvNavigation.install(this)
        org.videolan.vlc.gui.livetv.TvNavigation.apply(this, navigationView, currentFragmentId)
    }

    override fun onStart(owner: LifecycleOwner) {
        refreshPages()
        if (currentFragmentId == R.id.nav_tv && !org.videolan.vlc.livetv.LiveTv.enabled) currentFragmentId = defaultFragmentId
        if (currentFragment === null && !currentIdIsExtension()) showFragment(if (currentFragmentId != 0) currentFragmentId else settings.getInt(KEY_FRAGMENT_ID, defaultFragmentId).let { if (it == R.id.nav_tv && !org.videolan.vlc.livetv.LiveTv.enabled) defaultFragmentId else it })
        navigationView.forEach { it.setOnItemSelectedListener(this) }
    }

    override fun onStop(owner: LifecycleOwner) {
        navigationView.forEach { it.setOnItemSelectedListener(null) }
    }

    private fun getNewFragment(id: Int): Fragment {
        return when (id) {
            R.id.nav_audio -> AudioBrowserFragment()
            R.id.nav_directories -> MainBrowserFragment().apply {
                arguments = bundleOf(EXTRA_FOR_ESPRESSO to forExpresso)
            }
            R.id.nav_playlists -> PlaylistFragment()
            R.id.nav_more -> MoreFragment()
            R.id.nav_home -> HomeFragment()
            R.id.nav_tv -> org.videolan.vlc.gui.livetv.TvFragment()
            else -> VideoBrowserFragment()
        }
    }

    private fun showFragment(id: Int) {
        val tag = getTag(id)
        val fragment = getNewFragment(id)
        showFragment(fragment, id, tag)
    }

    private fun showFragment(fragment: Fragment, id: Int, tag: String = getTag(id)) {
        val fm = activity.supportFragmentManager
        if (currentFragment is BaseBrowserFragment) fm.popBackStackImmediate("root", FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val ft = fm.beginTransaction()
        ft.replace(R.id.fragment_placeholder, fragment, tag)
        if (BuildConfig.DEBUG) ft.commit()
        else ft.commitAllowingStateLoss()
        updateCheckedItem(id)
        currentFragment = fragment
        currentFragmentId = id
    }

    override fun currentIdIsExtension() = idIsExtension(currentFragmentId)

    private fun idIsExtension(id: Int) = id in 1..100

    override fun reloadPreferences() {
        currentFragmentId = settings.getInt(KEY_FRAGMENT_ID, defaultFragmentId)
    }

    override fun configurationChanged(size: Int) {
        navigationView.forEach {
            when (it) {
                is BottomNavigationView -> if (activity.useNavigationRail()) it.setGone() else it.setVisible()
                else -> if (!activity.useNavigationRail()) it.setGone() else it.setVisible()
            }
        }
        // VLC Redesign: a phone in landscape has no room for the large play button above the five entries:
        // the rail keeps only the entries and the play button floats at the bottom end of the content
        if (activity.useNavigationRail() && !activity.isTablet()) (activity.findViewById<View>(R.id.navigation_rail) as? NavigationRailView)?.removeHeaderView()
        // VLC Redesign, tablets: the desktop's expanded sidebar on a wide tablet window (gui/tablet)
        with(TabletSidebar) { activity.applyTabletSidebar() }
    }

    override fun getFragmentWidth(activity: Activity): Int {
        val screenWidth = activity.getScreenWidth()
        return screenWidth - activity.resources.getDimension(R.dimen.navigation_margin).toInt()
    }

    private fun getTag(id: Int) = when (id) {
        R.id.nav_audio -> ID_AUDIO
        R.id.nav_directories -> ID_DIRECTORIES
        R.id.nav_tv -> "tv"
        else -> ID_VIDEO
    }

    /** VLC Redesign: the bars in the user's order again (Edit Sidebar, the Live TV add-on switched). */
    override fun refreshPages() {
        if (!::navigationView.isInitialized) return
        org.videolan.vlc.gui.livetv.TvNavigation.apply(activity, navigationView, currentFragmentId)
        if (currentFragmentId == R.id.nav_tv && !org.videolan.vlc.livetv.LiveTv.enabled && currentFragment != null) showFragment(defaultFragmentId)
    }

    /** VLC Redesign: a page that is not in the bar (listed in More) opened in its place. */
    override fun openPage(id: Int) {
        if (currentFragmentId == id || !::activity.isInitialized) return
        activity.slideDownAudioPlayer()
        showFragment(id)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        val current = currentFragment

        appbarLayout.setExpanded(true, true)

        if (current == null) {
            return false
        }
        if (current is BaseFragment && current.actionMode != null) current.stopActionMode()

        if (currentFragmentId == id) { /* Already selected */
            // Go back at root level of current mProvider
            if ((current as? BaseBrowserFragment)?.isStarted() == false) {
                activity.supportFragmentManager.popBackStackImmediate("root", FragmentManager.POP_BACK_STACK_INCLUSIVE)
            } else {
                return false
            }
        } else {
            activity.slideDownAudioPlayer()
            showFragment(id)
        }
        return true
    }


    private fun updateCheckedItem(id: Int) {
        val currentId = currentFragmentId
        navigationView.forEach {
            val target = it.menu.findItem(id)
            if (id != it.selectedItemId && target != null) {
                val current = it.menu.findItem(currentId)
                if (current != null) current.isChecked = false
                target.isChecked = true
                /* Save the tab status in pref */
                settings.edit { putInt(KEY_FRAGMENT_ID, id) }
            }
        }
    }

}

interface INavigator {
    var navigationView: List<NavigationBarView>
    var appbarLayout: AppBarLayout
    var currentFragmentId: Int

    fun MainActivity.setupNavigation(state: Bundle?)
    fun currentIdIsExtension(): Boolean
    fun reloadPreferences()
    fun configurationChanged(size: Int)
    fun getFragmentWidth(activity: Activity): Int
    fun refreshPages()
    fun openPage(id: Int)
}