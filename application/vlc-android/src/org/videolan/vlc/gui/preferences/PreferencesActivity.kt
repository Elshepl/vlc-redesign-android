/*****************************************************************************
 * PreferencesActivity.java
 *
 * Copyright © 2011-2014 VLC authors and VideoLAN
 *
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
 */

package org.videolan.vlc.gui.preferences

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.resources.ACTIVITY_RESULT_PREFERENCES
import org.videolan.resources.util.parcelable
import org.videolan.tools.KEY_RESTRICT_SETTINGS
import org.videolan.tools.RESULT_RESTART
import org.videolan.tools.RESULT_RESTART_APP
import org.videolan.tools.RESULT_UPDATE_ARTISTS
import org.videolan.tools.Settings
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.gui.BaseActivity
import org.videolan.vlc.gui.PinCodeActivity
import org.videolan.vlc.gui.PinCodeReason
import org.videolan.vlc.gui.preferences.rd.RdPage
import org.videolan.vlc.gui.preferences.rd.RdSettingsPage
import org.videolan.vlc.gui.preferences.rd.RdSettingsSidebar
import org.videolan.vlc.gui.preferences.search.PreferenceItem
import org.videolan.vlc.gui.preferences.search.PreferenceParser
import org.videolan.vlc.gui.preferences.search.PreferenceSearchActivity
import org.videolan.vlc.widget.utils.refreshAllWidgets

const val EXTRA_PREF_END_POINT = "extra_pref_end_point"
open class PreferencesActivity : BaseActivity() {

    private val searchRequestCode = 167
    private var mAppBarLayout: AppBarLayout? = null
    override val displayTitle = true
    private var pinCodeResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            finish()
        }
    }
    override fun getSnackAnchorView(overAudioPlayer:Boolean): View? = findViewById(android.R.id.content)

    // VLC Redesign: the page shown (kept across a rotation, which may switch one pane <-> two panes)
    private var currentPage: RdPage? = null
    private var twoPane = false
    private val sidebar get() = supportFragmentManager.findFragmentById(R.id.rd_settings_sidebar) as? RdSettingsSidebar
            ?: supportFragmentManager.findFragmentById(R.id.fragment_placeholder) as? RdSettingsSidebar

    override fun onCreate(savedInstanceState: Bundle?) {
        // the panes are rebuilt from the page kept, not restored: a rotation can change their number
        super.onCreate(null)
        // VLC Redesign: the TV layout shows Settings as the design's dialog over the library (TvPreferencesActivity)
        if (this !is TvPreferencesActivity && org.videolan.vlc.gui.tablet.RdTvWindow.isOn(this)) {
            startActivity(Intent(intent).setClass(this, TvPreferencesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT))
            finish()
            overridePendingTransition(0, 0)
            return
        }
        if (Settings.getInstance(this).getBoolean(KEY_RESTRICT_SETTINGS, false) && savedInstanceState == null) {
            val intent = PinCodeActivity.getIntent(this, PinCodeReason.CHECK)
            pinCodeResult.launch(intent)
        }

        setContentView(R.layout.preferences_activity)
        setSupportActionBar(findViewById<View>(R.id.main_toolbar) as Toolbar)
        // (the window's metrics are Android 11; the configuration's window width before)
        val widthDp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            windowManager.currentWindowMetrics.bounds.width() / resources.displayMetrics.density
        else resources.configuration.screenWidthDp.toFloat()
        twoPane = widthDp >= 600
        findViewById<View>(R.id.rd_settings_sidebar).visibility = if (twoPane) View.VISIBLE else View.GONE
        val kept = RdPage.of(savedInstanceState?.getString(KEY_PAGE))
        val endPoint = if (savedInstanceState == null) intent.parcelable<PreferenceItem>(EXTRA_PREF_END_POINT) else null
        if (twoPane) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.rd_settings_sidebar, RdSettingsSidebar.newInstance(true))
                    .replace(R.id.fragment_placeholder, RdSettingsPage.newInstance(kept ?: RdPage.GENERAL))
                    .commitNow()
        } else {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_placeholder, RdSettingsSidebar.newInstance(false))
                    .commitNow()
            kept?.let { openPage(it) }
        }
        if (currentPage == null && !twoPane) supportActionBar?.title = getString(R.string.rd_settings)
        supportFragmentManager.addOnBackStackChangedListener {
            if (!twoPane && supportFragmentManager.backStackEntryCount == 0) {
                currentPage = null
                supportActionBar?.title = getString(R.string.rd_settings)
            }
        }
        endPoint?.let { openEndPoint(it) }
        if (savedInstanceState == null) RdPage.of(intent.getStringExtra("rd_debug_page"))?.let { openPage(it) }
        mAppBarLayout = findViewById(R.id.appbar)
        mAppBarLayout!!.post { ViewCompat.setElevation(mAppBarLayout!!, resources.getDimensionPixelSize(R.dimen.default_appbar_elevation).toFloat()) }
    }

    override fun onStop() {
        refreshAllWidgets()
        super.onStop()
    }

    internal fun expandBar() {
        mAppBarLayout!!.setExpanded(true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.activity_prefs, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                if (!supportFragmentManager.popBackStackImmediate())
                    finish()
                return true
            }
            R.id.menu_pref_search -> {
                startActivityForResult(Intent(this, PreferenceSearchActivity::class.java), searchRequestCode)
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == searchRequestCode && resultCode == RESULT_OK) {
            data?.extras?.parcelable<PreferenceItem>(EXTRA_PREF_END_POINT)?.let { openEndPoint(it) }
        }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPage?.let { outState.putString(KEY_PAGE, it.key) }
    }

    /** Opens a page of the settings: beside the sidebar (two panes) or over it */
    fun openPage(page: RdPage, scrollTo: String? = null) {
        val fm = supportFragmentManager
        if (twoPane) {
            fm.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            fm.beginTransaction().replace(R.id.fragment_placeholder, RdSettingsPage.newInstance(page, scrollTo)).commit()
        } else {
            fm.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            fm.beginTransaction().replace(R.id.fragment_placeholder, RdSettingsPage.newInstance(page, scrollTo))
                    .addToBackStack(PAGE_STACK).commit()
        }
        currentPage = page
    }

    /** A screen of its own (parental control, remote access, Android Auto, optional features) over the page */
    fun openSubScreen(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.fragment_placeholder, fragment).addToBackStack("sub").commit()
    }

    /**
     * The pages of the sidebar again (the Live TV add-on turned on or off: its page comes or goes), the page shown
     * left as it is, where it is scrolled. One pane: the list is drawn again when it comes back.
     */
    fun refreshSidebar() {
        if (!twoPane || isFinishing) return
        supportFragmentManager.beginTransaction().replace(R.id.rd_settings_sidebar, RdSettingsSidebar.newInstance(true)).commitNow()
        currentPage?.let { sidebar?.select(it.key) }
    }

    /** Called by a page when it shows: the sidebar marks it, the bar shows its title */
    fun onPageShown(page: RdPage) {
        currentPage = page
        sidebar?.select(page.key)
    }

    /** A setting found by the search or asked by another screen: its page, scrolled to it */
    private fun openEndPoint(item: PreferenceItem) {
        val page = PreferenceParser.rdPageOf(this, item.key)
        if (page != null) {
            openPage(page, item.key)
            return
        }
        val args = bundleOf(EXTRA_PREF_END_POINT to item)
        val sub = when (item.parentScreen) {
            R.xml.preferences_parental_control -> PreferencesParentalControl()
            R.xml.preferences_remote_access -> PreferencesRemoteAccess()
            R.xml.preferences_android_auto -> PreferencesAndroidAuto()
            else -> null
        } ?: return
        if (!twoPane) openPage(RdPage.GENERAL)
        openSubScreen(sub.apply { arguments = args })
    }

    fun exitAndRescan() {
        setRestart()
        val intent = intent
        finish()
        startActivity(intent)
    }

    fun setRestart() {
        setResult(RESULT_RESTART)
    }

    fun setRestartApp() {
        setResult(RESULT_RESTART_APP)
    }

    fun updateArtists() {
        setResult(RESULT_UPDATE_ARTISTS)
    }

    fun detectHeadset(detect: Boolean) {
        val le = PlaybackService.headSetDetection
        if (le.hasObservers()) le.value = detect
    }

    companion object {
        private const val KEY_PAGE = "rd_settings_page"
        private const val PAGE_STACK = "page"
        /**
         * Launch the preferences and redirect to a given preference
         * @param activity The calling activity
         * @param prefKey The preference key to redirect to
         * @throws NoSuchElementException if the key is not found
         */
        suspend fun launchWithPref(activity: FragmentActivity, prefKey:String) {
            val pref = withContext(Dispatchers.IO) {
                PreferenceParser.parsePreferences(activity, true)
            }.first { it.key == prefKey }
            val intent = Intent(activity, PreferencesActivity::class.java)
            intent.putExtra(EXTRA_PREF_END_POINT, pref)
            activity.startActivityForResult(intent, ACTIVITY_RESULT_PREFERENCES)
        }
    }
}
