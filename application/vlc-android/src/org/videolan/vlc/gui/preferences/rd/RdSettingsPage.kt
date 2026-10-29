/*
 * VLC Redesign: one page of the settings, organised as the desktop's (General, Interface, Privacy, Controls,
 * Playback, Video, Audio, Subtitles, Library, Network, Performance, Extensions). The rows keep the keys of VLC
 * for Android; their behaviour, which lived in one fragment per old screen (PreferencesFragment, PreferencesUi,
 * PreferencesVideo, PreferencesAudio, PreferencesSubtitles, PreferencesAdvanced, PreferencesVideoControls,
 * PreferencesAudioControls, PreferencesCasting), is gathered here by key, so that a row works on any page.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.preferences.rd

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference
import com.jaredrummler.android.colorpicker.ColorPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.medialibrary.Tools
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.AndroidDevices
import org.videolan.resources.AppContextProvider
import org.videolan.resources.EXPORT_SETTINGS_FILE
import org.videolan.resources.ROOM_DATABASE
import org.videolan.resources.VLCInstance
import org.videolan.tools.*
import org.videolan.tools.LocaleUtils.getLocales
import org.videolan.tools.Settings.isPinCodeSet
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.gui.DebugLogActivity
import org.videolan.vlc.gui.EqualizerSettingsActivity
import org.videolan.vlc.gui.PinCodeActivity
import org.videolan.vlc.gui.PinCodeReason
import org.videolan.vlc.gui.SecondaryActivity
import org.videolan.vlc.gui.browser.EXTRA_MRL
import org.videolan.vlc.gui.browser.FilePickerActivity
import org.videolan.vlc.gui.browser.KEY_PICKER_TYPE
import org.videolan.vlc.gui.dialogs.CONFIRM_DELETE_DIALOG_RESULT
import org.videolan.vlc.gui.dialogs.CONFIRM_DELETE_DIALOG_RESULT_TYPE
import org.videolan.vlc.gui.dialogs.CONFIRM_PREFERENCE_CHANGE_DIALOG_RESULT
import org.videolan.vlc.gui.dialogs.ConfirmDeleteDialog
import org.videolan.vlc.gui.dialogs.ConfirmPreferenceChangeDialog
import org.videolan.vlc.gui.dialogs.NEW_INSTALL
import org.videolan.vlc.gui.dialogs.PREFERENCE_KEY
import org.videolan.vlc.gui.dialogs.PermissionListDialog
import org.videolan.vlc.gui.dialogs.RenameDialog
import org.videolan.vlc.gui.dialogs.SleepTimerDialog
import org.videolan.vlc.gui.dialogs.UPDATE_DATE
import org.videolan.vlc.gui.dialogs.UPDATE_URL
import org.videolan.vlc.gui.dialogs.UpdateDialog
import org.videolan.vlc.gui.helpers.MedialibraryUtils
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.hf.StoragePermissionsDelegate.Companion.getWritePermission
import org.videolan.vlc.gui.helpers.restartMediaPlayer
import org.videolan.vlc.gui.onboarding.startOnboarding
import org.videolan.vlc.gui.preferences.BasePreferenceFragment
import org.videolan.vlc.gui.preferences.PreferencesActivity
import org.videolan.vlc.gui.preferences.PreferencesAndroidAuto
import org.videolan.vlc.gui.preferences.PreferencesOptional
import org.videolan.vlc.gui.preferences.PreferencesParentalControl
import org.videolan.vlc.gui.preferences.PreferencesRemoteAccess
import org.videolan.vlc.gui.preferences.search.PreferenceParser
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.providers.PickerType
import org.videolan.vlc.util.AutoUpdate
import org.videolan.vlc.util.FileUtils
import org.videolan.vlc.util.LocaleUtil
import org.videolan.vlc.util.LoudnessEffects
import org.videolan.vlc.util.Permissions
import org.videolan.vlc.util.share
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import org.videolan.vlc.util.RdToast as Toast

/** The pages of the settings, in the desktop sidebar's order and groups. */
enum class RdPage(val key: String, val label: Int, val title: Int, val xml: Int, val icon: Int, val iconActive: Int, val group: Int) {
    GENERAL("general", R.string.rd_set_general, R.string.rd_title_general, R.xml.rd_settings_general, R.drawable.rd_ic_set_general, R.drawable.rd_ic_set_general_active, R.string.rd_set_app),
    INTERFACE("interface", R.string.rd_set_interface, R.string.rd_title_interface, R.xml.rd_settings_interface, R.drawable.rd_ic_set_interface, R.drawable.rd_ic_set_interface_active, R.string.rd_set_app),
    PRIVACY("privacy", R.string.rd_set_privacy, R.string.rd_title_privacy, R.xml.rd_settings_privacy, R.drawable.rd_ic_set_privacy, R.drawable.rd_ic_set_privacy_active, R.string.rd_set_app),
    CONTROLS("controls", R.string.rd_set_controls, R.string.rd_set_controls, R.xml.rd_settings_controls, R.drawable.rd_ic_set_controls, R.drawable.rd_ic_set_controls_active, R.string.rd_set_app),
    PLAYBACK("playback", R.string.rd_set_playback, R.string.rd_title_playback, R.xml.rd_settings_playback, R.drawable.rd_ic_set_playback, R.drawable.rd_ic_set_playback_active, R.string.rd_set_media),
    VIDEO("video", R.string.rd_set_video, R.string.rd_title_video, R.xml.rd_settings_video, R.drawable.rd_ic_set_video, R.drawable.rd_ic_set_video_active, R.string.rd_set_media),
    AUDIO("audio", R.string.rd_set_audio, R.string.rd_title_audio, R.xml.rd_settings_audio, R.drawable.rd_ic_set_audio, R.drawable.rd_ic_set_audio_active, R.string.rd_set_media),
    SUBTITLES("subtitles", R.string.rd_set_subtitles, R.string.rd_title_subtitles, R.xml.rd_settings_subtitles, R.drawable.rd_ic_set_subtitles, R.drawable.rd_ic_set_subtitles_active, R.string.rd_set_media),
    LIBRARY("library", R.string.rd_set_library, R.string.rd_title_library, R.xml.rd_settings_library, R.drawable.rd_ic_set_library, R.drawable.rd_ic_set_library_active, R.string.rd_set_media),
    NETWORK("network", R.string.rd_set_network, R.string.rd_title_network, R.xml.rd_settings_network, R.drawable.rd_ic_set_network, R.drawable.rd_ic_set_network_active, R.string.rd_set_system),
    PERFORMANCE("performance", R.string.rd_set_performance, R.string.rd_title_performance, R.xml.rd_settings_performance, R.drawable.rd_ic_set_performance, R.drawable.rd_ic_set_performance_active, R.string.rd_set_system),
    EXTENSIONS("extensions", R.string.rd_set_extensions, R.string.rd_set_extensions, R.xml.rd_settings_extensions, R.drawable.rd_ic_set_extensions, R.drawable.rd_ic_set_extensions_active, R.string.rd_set_system);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

private const val SOUNDFONT_PICKER = 10001
private const val SETTINGS_PICKER = 10002
private const val RESULT_VALUE_CLEAR_HISTORY = 1
private const val RESULT_VALUE_CLEAR_MEDIA_DATABASE = 2
private const val RESULT_VALUE_CLEAR_APP_DATA = 3
const val RD_PAGE = "rd_page"
const val RD_SCROLL_TO = "rd_scroll_to"

class RdSettingsPage : BasePreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val page get() = RdPage.of(arguments?.getString(RD_PAGE)) ?: RdPage.GENERAL
    private lateinit var settings: SharedPreferences
    private var subtitlesChanged = false
    private var needToRestartOnResume = false

    private val pinCodeResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) openSub(PreferencesParentalControl())
    }

    override fun getXml() = page.xml
    override fun getTitleId() = page.title

    private fun <T : Preference> pref(key: String): T? = findPreference(key)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.getInstance(requireActivity())
        // Interface / General
        pref<ListPreference>(KEY_SET_LOCALE)?.let { lp ->
            val localePair = LocaleUtils.getLocalesUsedInProject(BuildConfig.TRANSLATION_ARRAY, getString(R.string.device_default))
            lp.entries = localePair.localeEntries
            lp.entryValues = localePair.localeEntryValues
        }
        if (pref<Preference>(KEY_APP_THEME) != null) setupTheme()
        pref<Preference>("default_sleep_timer")?.let { manageSleepTimerSummary() }
        // Video
        if (page == RdPage.VIDEO && settings.getBoolean(PREF_SHOW_VIDEO_SETTINGS_DISCLAIMER, false)) {
            UiTools.snackerConfirm(requireActivity(), requireActivity().getString(R.string.video_settings_disclaimer), indefinite = true) {
                Settings.getInstance(AppContextProvider.appContext).putSingle(PREF_SHOW_VIDEO_SETTINGS_DISCLAIMER, false)
            }
        }
        // Audio
        updatePassThroughSummary()
        for (key in arrayOf(KEY_AUDIO_REPLAY_GAIN_DEFAULT, KEY_AUDIO_REPLAY_GAIN_PREAMP)) {
            pref<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                it.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
                it.setSelection(it.editableText.length)
            }
        }
        for (key in arrayOf("network_caching", "rd_file_caching", "rd_live_caching", "rd_avcodec_threads", "rd_audio_desync")) {
            pref<EditTextPreference>(key)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or (if (key == "rd_audio_desync") InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
                it.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(5))
                it.setSelection(it.editableText.length)
            }
        }
        pref<ListPreference>(KEY_AUDIO_PREFERRED_LANGUAGE)?.let {
            val localePair = LocaleUtils.getLocalesUsedInProject(BuildConfig.TRANSLATION_ARRAY, getString(R.string.no_track_preference), requireActivity().getLocales())
            it.entries = localePair.localeEntries
            it.entryValues = localePair.localeEntryValues
        }
        // Subtitles
        pref<ListPreference>(KEY_SUBTITLE_PREFERRED_LANGUAGE)?.let {
            val localePair = LocaleUtils.getLocalesUsedInProject(BuildConfig.TRANSLATION_ARRAY, getString(R.string.no_track_preference), requireActivity().getLocales())
            it.entries = localePair.localeEntries
            it.entryValues = localePair.localeEntryValues
        }
        pref<ListPreference>("subtitles_presets")?.let { preset ->
            preset.value = "-1"
            preset.setOnPreferenceChangeListener { _, newValue -> applySubtitlePreset(newValue as String); false }
        }
        manageSubtitleVisibilities()
        // Controls
        setupControls()
        // Loudness: Android's audio effects
        if (!LoudnessEffects.isSupported) {
            for (key in arrayOf("rd_normalize", "rd_norm_level", "rd_night_mode")) pref<Preference>(key)?.isEnabled = false
        }
        // Advanced Options start closed on every page, as on the desktop
        pref<Preference>(page.key + "_advanced")?.isVisible = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView?.clipToPadding = false
        arguments?.getString(RD_SCROLL_TO)?.let { key ->
            // a row of the Advanced Options: open them first
            val bar = pref<RdAdvancedPreference>(page.key + "_advanced_bar")
            val inAdvanced = pref<androidx.preference.PreferenceGroup>(page.key + "_advanced")?.findPreference<Preference>(key) != null
            if (inAdvanced) bar?.expand()
            scrollToPreference(key)
            // the page's late rows (the accent swatches, the language list) change the list after the first
            // layout and left it at the top on a phone: scroll once more when they are in, and mark the row
            view.postDelayed({
                if (!isAdded) return@postDelayed
                scrollToPreference(key)
                val position = (listView?.adapter as? androidx.preference.PreferenceGroup.PreferencePositionCallback)?.getPreferenceAdapterPosition(key) ?: -1
                if (position >= 0) listView?.postDelayed({ listView?.findViewHolderForAdapterPosition(position)?.itemView?.isPressed = true }, 300)
            }, 250)
            arguments?.remove(RD_SCROLL_TO)
        }
        requireActivity().supportFragmentManager.setFragmentResultListener(CONFIRM_PREFERENCE_CHANGE_DIALOG_RESULT, viewLifecycleOwner) { _, bundle ->
            when (bundle.getString(PREFERENCE_KEY, "")) {
                AUDIO_RESUME_PLAYBACK -> {
                    settings.edit()
                        .remove(KEY_AUDIO_LAST_PLAYLIST)
                        .remove(KEY_MEDIA_LAST_PLAYLIST_RESUME)
                        .remove(KEY_CURRENT_AUDIO_RESUME_TITLE)
                        .remove(KEY_CURRENT_AUDIO_RESUME_ARTIST)
                        .remove(KEY_CURRENT_AUDIO_RESUME_THUMB)
                        .remove(KEY_CURRENT_AUDIO)
                        .remove(KEY_CURRENT_MEDIA)
                        .remove(KEY_CURRENT_MEDIA_RESUME)
                        .apply()
                    activity?.setResult(RESULT_RESTART)
                    pref<CheckBoxPreference>(AUDIO_RESUME_PLAYBACK)?.isChecked = false
                }
                PLAYBACK_HISTORY -> pref<CheckBoxPreference>(PLAYBACK_HISTORY)?.isChecked = false
            }
        }
        requireActivity().supportFragmentManager.setFragmentResultListener(CONFIRM_DELETE_DIALOG_RESULT, viewLifecycleOwner) { _, bundle ->
            when (bundle.getInt(CONFIRM_DELETE_DIALOG_RESULT_TYPE)) {
                RESULT_VALUE_CLEAR_HISTORY -> {
                    Medialibrary.getInstance().clearHistory(Medialibrary.HISTORY_TYPE_GLOBAL)
                    settings.edit()
                        .remove(KEY_AUDIO_LAST_PLAYLIST)
                        .remove(KEY_MEDIA_LAST_PLAYLIST)
                        .remove(KEY_MEDIA_LAST_PLAYLIST_RESUME)
                        .remove(KEY_CURRENT_AUDIO)
                        .remove(KEY_CURRENT_MEDIA)
                        .remove(KEY_CURRENT_MEDIA_RESUME)
                        .remove(KEY_CURRENT_AUDIO_RESUME_TITLE)
                        .remove(KEY_CURRENT_AUDIO_RESUME_ARTIST)
                        .remove(KEY_CURRENT_AUDIO_RESUME_THUMB)
                        .apply()
                }
                RESULT_VALUE_CLEAR_MEDIA_DATABASE -> clearMediaDatabase()
                RESULT_VALUE_CLEAR_APP_DATA -> (requireActivity().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        (activity as? PreferencesActivity)?.onPageShown(page)
    }

    override fun onStop() {
        super.onStop()
        if (subtitlesChanged) {
            subtitlesChanged = false
            lifecycleScope.launch { restartLibVLC() }
        }
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        if (needToRestartOnResume) {
            lifecycleScope.launch {
                VLCInstance.restart()
                UiTools.restartDialog(requireActivity())
            }
            needToRestartOnResume = false
        }
        super.onResume()
    }

    private fun openSub(fragment: androidx.fragment.app.Fragment) {
        (activity as? PreferencesActivity)?.openSubScreen(fragment)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val key = preference.key ?: return false
        val activity = requireActivity()
        when (key) {
            // Library
            "directories" -> {
                if (Medialibrary.getInstance().isWorking) {
                    UiTools.snacker(activity, getString(R.string.settings_ml_block_scan))
                } else {
                    val intent = Intent(activity.applicationContext, SecondaryActivity::class.java)
                    intent.putExtra("fragment", SecondaryActivity.STORAGE_BROWSER)
                    startActivity(intent)
                    activity.setResult(RESULT_RESTART)
                }
            }
            "equalizer" -> startActivity(Intent(activity.applicationContext, EqualizerSettingsActivity::class.java))
            "parental_control" -> {
                if (activity.isPinCodeSet()) openSub(PreferencesParentalControl())
                else pinCodeResult.launch(PinCodeActivity.getIntent(activity, PinCodeReason.FIRST_CREATION))
            }
            "permissions" -> PermissionListDialog.newInstance().show(activity.supportFragmentManager, "PermissionListDialog")
            "remote_access_category" -> openSub(PreferencesRemoteAccess())
            "android_auto_category" -> openSub(PreferencesAndroidAuto())
            "optional_features" -> openSub(PreferencesOptional())
            "rd_welcome_setup" -> activity.startOnboarding()
            // Privacy
            PLAYBACK_HISTORY -> {
                activity.setResult(RESULT_RESTART)
                if (!(preference as TwoStatePreference).isChecked) {
                    ConfirmPreferenceChangeDialog.newInstance(PLAYBACK_HISTORY, getString(R.string.playback_history_title), getString(R.string.playback_history_warning))
                            .show(activity.supportFragmentManager, ConfirmPreferenceChangeDialog::class.simpleName)
                    preference.isChecked = true
                }
            }
            AUDIO_RESUME_PLAYBACK -> {
                if (!(preference as TwoStatePreference).isChecked) {
                    ConfirmPreferenceChangeDialog.newInstance(AUDIO_RESUME_PLAYBACK, getString(R.string.audio_resume_playback_title), getString(R.string.audio_resume_playback_warning))
                            .show(activity.supportFragmentManager, ConfirmPreferenceChangeDialog::class.simpleName)
                    preference.isChecked = true
                }
            }
            VIDEO_RESUME_PLAYBACK -> {
                settings.edit()
                        .remove(KEY_MEDIA_LAST_PLAYLIST)
                        .remove(KEY_MEDIA_LAST_PLAYLIST_RESUME)
                        .remove(KEY_CURRENT_MEDIA_RESUME)
                        .remove(KEY_CURRENT_MEDIA)
                        .apply()
                activity.setResult(RESULT_RESTART)
            }
            "clear_history" -> ConfirmDeleteDialog.newInstance(title = getString(R.string.clear_playback_history), description = getString(R.string.clear_history_message),
                    buttonText = getString(R.string.clear_history), resultType = RESULT_VALUE_CLEAR_HISTORY)
                    .show(activity.supportFragmentManager, RenameDialog::class.simpleName)
            // Interface
            PREF_TV_UI -> {
                Settings.tvUI = (preference as TwoStatePreference).isChecked
                (activity as PreferencesActivity).setRestartApp()
            }
            SHOW_VIDEO_THUMBNAILS -> {
                Settings.showVideoThumbs = (preference as TwoStatePreference).isChecked
                (activity as PreferencesActivity).setRestart()
            }
            KEY_SHOW_HEADERS -> {
                Settings.showHeaders = (preference as TwoStatePreference).isChecked
                (activity as PreferencesActivity).setRestart()
            }
            "default_sleep_timer" -> {
                val newFragment = SleepTimerDialog.newInstance(true)
                newFragment.onDismissListener = DialogInterface.OnDismissListener { manageSleepTimerSummary() }
                newFragment.show(activity.supportFragmentManager, "time")
            }
            KEY_MEDIA_SEEN -> activity.setResult(RESULT_UPDATE_SEEN_MEDIA)
            KEY_ARTISTS_SHOW_ALL -> (activity as PreferencesActivity).updateArtists()
            // Audio
            KEY_ENABLE_HEADSET_DETECTION -> (activity as PreferencesActivity).detectHeadset((preference as TwoStatePreference).isChecked)
            "soundfont" -> {
                val filePickerIntent = Intent(requireContext(), FilePickerActivity::class.java)
                filePickerIntent.putExtra(KEY_PICKER_TYPE, PickerType.SOUNDFONT.ordinal)
                @Suppress("DEPRECATION")
                startActivityForResult(filePickerIntent, SOUNDFONT_PICKER)
            }
            // Casting
            KEY_ENABLE_CASTING -> (activity as PreferencesActivity).setRestartApp()
            // Advanced
            "debug_logs" -> startActivity(Intent(requireContext(), DebugLogActivity::class.java))
            "nightly_install" -> android.app.AlertDialog.Builder(activity)
                    .setTitle(resources.getString(R.string.install_nightly))
                    .setMessage(resources.getString(R.string.install_nightly_alert))
                    .setPositiveButton(R.string.ok) { _, _ ->
                        activity.lifecycleScope.launch {
                            AutoUpdate.checkUpdate(activity.application, true) { url, date ->
                                UpdateDialog().apply {
                                    arguments = bundleOf(UPDATE_URL to url, UPDATE_DATE to date.time, NEW_INSTALL to true)
                                }.show(activity.supportFragmentManager, "fragment_update")
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            "clear_media_db" -> {
                if (Medialibrary.getInstance().isWorking) Toast.makeText(activity, R.string.settings_ml_block_scan, Toast.LENGTH_LONG).show()
                else ConfirmDeleteDialog.newInstance(title = getString(R.string.clear_media_db), description = getString(R.string.clear_media_db_message),
                        buttonText = getString(R.string.clear), resultType = RESULT_VALUE_CLEAR_MEDIA_DATABASE)
                        .show(activity.supportFragmentManager, RenameDialog::class.simpleName)
            }
            "clear_app_data" -> ConfirmDeleteDialog.newInstance(title = getString(R.string.clear_app_data), description = getString(R.string.clear_app_data_message),
                    buttonText = getString(R.string.clear), resultType = RESULT_VALUE_CLEAR_APP_DATA)
                    .show(activity.supportFragmentManager, RenameDialog::class.simpleName)
            "quit_app" -> android.os.Process.killProcess(android.os.Process.myPid())
            "dump_media_db" -> {
                if (Medialibrary.getInstance().isWorking) UiTools.snacker(activity, getString(R.string.settings_ml_block_scan))
                else {
                    val dst = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + Medialibrary.VLC_MEDIA_DB_NAME)
                    lifecycleScope.launch {
                        if (activity.getWritePermission(Uri.fromFile(dst))) {
                            val copied = withContext(Dispatchers.IO) {
                                val db = File(requireContext().getDir("db", Context.MODE_PRIVATE).toString() + Medialibrary.VLC_MEDIA_DB_NAME)
                                FileUtils.copyFile(db, dst)
                            }
                            dumped(copied, dst)
                        }
                    }
                }
            }
            "dump_app_db" -> {
                val dst = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + ROOM_DATABASE)
                lifecycleScope.launch {
                    if (activity.getWritePermission(Uri.fromFile(dst))) {
                        val copied = withContext(Dispatchers.IO) {
                            val db = File(requireContext().getDir("db", Context.MODE_PRIVATE).parent!! + "/databases")
                            val files = db.listFiles()?.map { it.path }?.toTypedArray()
                            if (files == null) false else FileUtils.zip(files, dst.path)
                        }
                        dumped(copied, dst)
                    }
                }
            }
            "export_settings" -> {
                val dst = File(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + EXPORT_SETTINGS_FILE)
                lifecycleScope.launch(Dispatchers.IO) {
                    if (activity.getWritePermission(Uri.fromFile(dst))) PreferenceParser.exportPreferences(activity, dst)
                }
            }
            "restore_settings" -> {
                val filePickerIntent = Intent(requireContext(), FilePickerActivity::class.java)
                filePickerIntent.putExtra(KEY_PICKER_TYPE, PickerType.SETTINGS.ordinal)
                @Suppress("DEPRECATION")
                startActivityForResult(filePickerIntent, SETTINGS_PICKER)
            }
            KEY_QUICK_PLAY -> {
                activity.setResult(RESULT_RESTART)
                if (!(preference as TwoStatePreference).isChecked) pref<CheckBoxPreference>(KEY_QUICK_PLAY_DEFAULT)?.isChecked = false
            }
            else -> return super.onPreferenceTreeClick(preference)
        }
        return true
    }

    private fun dumped(copied: Boolean, dst: File) {
        val activity = activity ?: return
        if (copied)
            UiTools.snackerConfirm(activity, getString(R.string.dump_db_succes), confirmMessage = R.string.share, overAudioPlayer = false) { activity.share(dst) }
        else Toast.makeText(activity, getString(R.string.dump_db_failure), Toast.LENGTH_LONG).show()
    }

    private fun clearMediaDatabase() {
        val medialibrary = Medialibrary.getInstance()
        if (medialibrary.isWorking) {
            activity?.let { Toast.makeText(it, R.string.settings_ml_block_scan, Toast.LENGTH_LONG).show() }
            return
        }
        lifecycleScope.launch {
            val roots = medialibrary.foldersList
            withContext(Dispatchers.IO) {
                medialibrary.clearDatabase(false)
                try {
                    requireActivity().getExternalFilesDir(null)?.let {
                        File(it.absolutePath + Medialibrary.MEDIALIB_FOLDER_NAME).listFiles()?.forEach { file ->
                            if (file.isFile) FileUtils.deleteFile(file)
                        }
                    }
                    BitmapCache.clear()
                } catch (e: IOException) {
                    Log.e(this::class.java.simpleName, e.message, e)
                }
            }
            for (root in roots) MedialibraryUtils.addDir(root.removePrefix("file://"), requireContext())
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (data == null || !data.hasExtra(EXTRA_MRL)) return
        when (requestCode) {
            SOUNDFONT_PICKER -> {
                lifecycleScope.launch {
                    MediaUtils.useAsSoundFont(requireActivity(), data.getStringExtra(EXTRA_MRL)!!.toUri())
                    VLCInstance.restart()
                }
                UiTools.restartDialog(requireActivity())
            }
            SETTINGS_PICKER -> lifecycleScope.launch {
                try {
                    PreferenceParser.restoreSettings(requireActivity(), data.getStringExtra(EXTRA_MRL)!!.toUri())
                    var continueRestart = true
                    if (settings.getBoolean(POPUP_FORCE_LEGACY, false) && !Permissions.canDrawOverlays(requireActivity()))
                        continueRestart = !Permissions.checkDrawOverlaysPermission(requireActivity())
                    if (continueRestart) {
                        VLCInstance.restart()
                        UiTools.restartDialog(requireActivity())
                    } else needToRestartOnResume = true
                } catch (e: Exception) {
                    Log.e("RdSettingsPage", "restore: ${e.message}", e)
                    UiTools.snacker(requireActivity(), getString(R.string.invalid_settings_file))
                }
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val activity = activity as? PreferencesActivity ?: return
        if (sharedPreferences == null || key == null) return
        when (key) {
            // General / Interface
            // the desktop switches its language at once: this screen now, the others as they come back
            KEY_SET_LOCALE -> {
                activity.setRestart()
                AppContextProvider.setLocale(sharedPreferences.getString(KEY_SET_LOCALE, ""))
                activity.recreate()
            }
            // the desktop changes its theme at once: every open screen is drawn again in it
            KEY_APP_THEME -> {
                activity.setRestart()
                sharedPreferences.getString(KEY_APP_THEME, "-1")?.toIntOrNull()?.let { AppCompatDelegate.setDefaultNightMode(it) }
                // drawn again from its theme, with the accent overlay (an in-place day/night update drops it)
                activity.recreate()
            }
            "rd_accent" -> {
                // the accent of the desktop changes at once: this screen now, the others when they come back
                activity.setRestart()
                activity.recreate()
            }
            LIST_TITLE_ELLIPSIZE -> {
                Settings.listTitleEllipsize = sharedPreferences.getString(LIST_TITLE_ELLIPSIZE, "0")?.toInt() ?: 0
                activity.setRestart()
            }
            KEY_INCLUDE_MISSING -> {
                Settings.includeMissing = sharedPreferences.getBoolean(key, true)
                activity.setRestart()
            }
            "video_action_switch" -> if (!AndroidUtil.isOOrLater && pref<ListPreference>(key)?.value == "2" && !Permissions.canDrawOverlays(activity))
                Permissions.checkDrawOverlaysPermission(activity)
            PLAYBACK_HISTORY -> if (sharedPreferences.getBoolean(key, true)) {
                pref<CheckBoxPreference>(AUDIO_RESUME_PLAYBACK)?.isChecked = true
                pref<CheckBoxPreference>(VIDEO_RESUME_PLAYBACK)?.isChecked = true
            }
            // Video
            KEY_PREFERRED_RESOLUTION -> lifecycleScope.launch { restartLibVLC() }
            POPUP_FORCE_LEGACY -> {
                if (sharedPreferences.getBoolean(key, false) && !Permissions.canDrawOverlays(activity)) Permissions.checkDrawOverlaysPermission(activity)
                if (!sharedPreferences.getBoolean(key, false) && !Permissions.isPiPAllowed(activity)) Permissions.checkPiPPermission(activity)
            }
            // Audio
            KEY_AUDIO_DIGITAL_OUTPUT -> {
                updatePassThroughSummary()
                LoudnessEffects.apply(activity)
            }
            KEY_AUDIO_REPLAY_GAIN_ENABLE, KEY_AUDIO_REPLAY_GAIN_MODE, KEY_AUDIO_REPLAY_GAIN_PEAK_PROTECTION, "rd_stereo_mode", "rd_audio_desync",
            "rd_deinterlace", "rd_deinterlace_mode", "rd_avi_index", "rd_sub_fuzzy", "rd_file_caching", "rd_live_caching", "rd_http_proxy",
            "rd_postproc", "rd_avcodec_threads", KEY_OPENGL, KEY_DEBLOCKING, KEY_ENABLE_FRAME_SKIP, KEY_ENABLE_TIME_STRETCHING_AUDIO,
            KEY_ENABLE_VERBOSE_MODE, KEY_CASTING_PASSTHROUGH, KEY_CASTING_QUALITY, KEY_CASTING_AUDIO_ONLY -> lifecycleScope.launch { restartLibVLC() }
            "rd_normalize", "rd_norm_level", "rd_night_mode" -> LoudnessEffects.apply(activity)
            KEY_AUDIO_REPLAY_GAIN_DEFAULT, KEY_AUDIO_REPLAY_GAIN_PREAMP -> {
                val defValue = if (key == KEY_AUDIO_REPLAY_GAIN_DEFAULT) "-7.0" else "0.0"
                val newValue = sharedPreferences.getString(key, defValue)
                var fmtValue = defValue
                try {
                    fmtValue = DecimalFormat("###0.0###", DecimalFormatSymbols(Locale.ENGLISH)).format(newValue?.toDouble())
                } catch (e: IllegalArgumentException) {
                    Log.w("RdSettingsPage", "Could not parse value: $newValue. Setting $key to $fmtValue", e)
                } finally {
                    if (fmtValue != newValue) {
                        sharedPreferences.edit {
                            putString(key, fmtValue)
                            pref<EditTextPreference>(key)?.let { it.text = fmtValue }
                        }
                    } else lifecycleScope.launch { restartLibVLC() }
                }
            }
            KEY_AOUT -> {
                lifecycleScope.launch { restartLibVLC() }
                if (settings.getString(KEY_AOUT, "0") == "2") settings.putSingle(KEY_AUDIO_DIGITAL_OUTPUT, false)
            }
            // Subtitles
            KEY_SUBTITLES_SIZE, KEY_SUBTITLES_BOLD, KEY_SUBTITLE_TEXT_ENCODING,
            KEY_SUBTITLES_COLOR, KEY_SUBTITLES_COLOR_OPACITY,
            KEY_SUBTITLES_BACKGROUND_COLOR, KEY_SUBTITLES_BACKGROUND_COLOR_OPACITY, KEY_SUBTITLES_BACKGROUND,
            KEY_SUBTITLES_OUTLINE, KEY_SUBTITLES_OUTLINE_SIZE, KEY_SUBTITLES_OUTLINE_COLOR, KEY_SUBTITLES_OUTLINE_COLOR_OPACITY,
            KEY_SUBTITLES_SHADOW, KEY_SUBTITLES_SHADOW_COLOR, KEY_SUBTITLES_SHADOW_COLOR_OPACITY, "rd_shadow_distance", "rd_sub_margin" -> {
                subtitlesChanged = true
                manageSubtitleVisibilities()
            }
            // Network / Performance
            "network_caching" -> {
                sharedPreferences.edit {
                    try {
                        val origValue = Integer.parseInt(sharedPreferences.getString(key, "0") ?: "0")
                        val newValue = origValue.coerceIn(0, 60000)
                        putInt(KEY_NETWORK_CACHING_VALUE, newValue)
                        pref<EditTextPreference>(key)?.let { it.text = newValue.toString() }
                        if (origValue != newValue) UiTools.snacker(activity, R.string.network_caching_popup)
                    } catch (e: NumberFormatException) {
                        putInt(KEY_NETWORK_CACHING_VALUE, 0)
                        pref<EditTextPreference>(key)?.let { it.text = "0" }
                        UiTools.snacker(activity, R.string.network_caching_popup)
                    }
                }
                lifecycleScope.launch { restartLibVLC() }
            }
            KEY_CUSTOM_LIBVLC_OPTIONS -> lifecycleScope.launch {
                try {
                    VLCInstance.restart()
                } catch (e: IllegalStateException) {
                    UiTools.snacker(activity, R.string.custom_libvlc_options_invalid)
                    sharedPreferences.putSingle(KEY_CUSTOM_LIBVLC_OPTIONS, "")
                } finally {
                    restartMediaPlayer()
                }
                restartLibVLC()
            }
            DAV1D_THREAD_NUMBER -> {
                val threadNumber = sharedPreferences.getString(key, "") ?: ""
                if (threadNumber != "") {
                    if ((threadNumber.isDigitsOnly() && threadNumber.toInt() < 1) || !threadNumber.isDigitsOnly()) {
                        UiTools.snacker(activity, R.string.dav1d_thread_number_invalid)
                        sharedPreferences.putSingle(DAV1D_THREAD_NUMBER, "")
                    }
                } else {
                    val p = pref<EditTextPreference>(key)
                    if (p?.callChangeListener("") == true) p.text = ""
                }
            }
            KEY_PREFER_SMBV1 -> {
                lifecycleScope.launch { VLCInstance.restart() }
                UiTools.restartDialog(activity)
            }
            // Controls (video)
            VIDEO_HUD_TIMEOUT -> {
                Settings.videoHudDelay = sharedPreferences.getInt(VIDEO_HUD_TIMEOUT, 4).coerceInOrDefault(1, 15, -1)
                updateHudTimeoutSummary()
            }
            KEY_VIDEO_JUMP_DELAY -> Settings.videoJumpDelay = sharedPreferences.getInt(KEY_VIDEO_JUMP_DELAY, 10)
            KEY_VIDEO_LONG_JUMP_DELAY -> Settings.videoLongJumpDelay = sharedPreferences.getInt(KEY_VIDEO_LONG_JUMP_DELAY, 20)
            KEY_VIDEO_DOUBLE_TAP_JUMP_DELAY -> Settings.videoDoubleTapJumpDelay = sharedPreferences.getInt(KEY_VIDEO_DOUBLE_TAP_JUMP_DELAY, 20)
            FASTPLAY_SPEED -> {
                Settings.fastplaySpeed = sharedPreferences.getInt(FASTPLAY_SPEED, 20) / 10f
                updateFastplaySpeedSummary()
            }
            // Controls (audio)
            KEY_AUDIO_JUMP_DELAY -> Settings.audioJumpDelay = sharedPreferences.getInt(KEY_AUDIO_JUMP_DELAY, 10)
            KEY_AUDIO_LONG_JUMP_DELAY -> Settings.audioLongJumpDelay = sharedPreferences.getInt(KEY_AUDIO_LONG_JUMP_DELAY, 20)
            KEY_AUDIO_SHOW_TRACK_NUMBERS -> Settings.audioShowTrackNumbers.postValue(sharedPreferences.getBoolean(KEY_AUDIO_SHOW_TRACK_NUMBERS, false))
            KEY_AUDIO_SHOW_BOOkMARK_BUTTONS -> if (!sharedPreferences.getBoolean(KEY_AUDIO_SHOW_BOOkMARK_BUTTONS, true))
                pref<CheckBoxPreference>("audio_show_bookmark_markers")?.isChecked = false
        }
        if (key in AUDIO_CONTROLS) Settings.onAudioControlsChanged()
    }

    private suspend fun restartLibVLC() {
        VLCInstance.restart()
        restartMediaPlayer()
    }

    // General

    private fun setupTheme() {
        val prefs = preferenceScreen.sharedPreferences!!
        if (!prefs.contains(KEY_APP_THEME)) {
            var theme = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            if (prefs.getBoolean(KEY_DAYNIGHT, false) && !AndroidDevices.canUseSystemNightMode()) {
                theme = AppCompatDelegate.MODE_NIGHT_AUTO
            } else if (prefs.contains(KEY_BLACK_THEME)) {
                theme = if (prefs.getBoolean(KEY_BLACK_THEME, false)) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            }
            prefs.putSingle(KEY_APP_THEME, theme.toString())
        }
    }

    private fun manageSleepTimerSummary() {
        val p = pref<Preference>("default_sleep_timer") ?: return
        val interval = settings.getLong(SLEEP_TIMER_DEFAULT_INTERVAL, -1L)
        if (interval == -1L) {
            p.summary = getString(R.string.disabled)
            return
        }
        val wait = settings.getBoolean(SLEEP_TIMER_DEFAULT_WAIT, false)
        val reset = settings.getBoolean(SLEEP_TIMER_DEFAULT_RESET_INTERACTION, false)
        p.summary = getString(R.string.default_sleep_timer_summary, Tools.millisToString(interval), if (wait) "true" else "false", if (reset) "true" else "false")
    }

    // Audio

    private fun updatePassThroughSummary() {
        val p = pref<Preference>(KEY_AUDIO_DIGITAL_OUTPUT) ?: return
        val pt = preferenceManager.sharedPreferences!!.getBoolean(KEY_AUDIO_DIGITAL_OUTPUT, false)
        p.setSummary(if (pt) R.string.audio_digital_output_enabled else R.string.audio_digital_output_disabled)
    }

    // Subtitles

    private fun applySubtitlePreset(newValue: String) {
        val size = pref<ListPreference>(KEY_SUBTITLES_SIZE) ?: return
        val color = pref<ColorPreferenceCompat>(KEY_SUBTITLES_COLOR)
        val background = pref<CheckBoxPreference>(KEY_SUBTITLES_BACKGROUND)
        val backgroundOpacity = pref<SeekBarPreference>(KEY_SUBTITLES_BACKGROUND_COLOR_OPACITY)
        val shadow = pref<CheckBoxPreference>(KEY_SUBTITLES_SHADOW)
        val outline = pref<CheckBoxPreference>(KEY_SUBTITLES_OUTLINE)
        // reset all
        size.value = "16"
        pref<CheckBoxPreference>(KEY_SUBTITLES_BOLD)?.isChecked = false
        color?.saveValue(ContextCompat.getColor(requireActivity(), R.color.white))
        pref<SeekBarPreference>(KEY_SUBTITLES_COLOR_OPACITY)?.value = 255
        background?.isChecked = false
        pref<ColorPreferenceCompat>(KEY_SUBTITLES_BACKGROUND_COLOR)?.saveValue(ContextCompat.getColor(requireActivity(), R.color.black))
        backgroundOpacity?.value = 255
        shadow?.isChecked = true
        pref<ColorPreferenceCompat>(KEY_SUBTITLES_SHADOW_COLOR)?.saveValue(ContextCompat.getColor(requireActivity(), R.color.black))
        pref<SeekBarPreference>(KEY_SUBTITLES_SHADOW_COLOR_OPACITY)?.value = 128
        outline?.isChecked = true
        pref<ColorPreferenceCompat>(KEY_SUBTITLES_OUTLINE_COLOR)?.saveValue(ContextCompat.getColor(requireActivity(), R.color.black))
        pref<SeekBarPreference>(KEY_SUBTITLES_OUTLINE_COLOR_OPACITY)?.value = 255
        when (newValue) {
            "1" -> size.value = "13"
            "2" -> {
                size.value = "10"
                background?.isChecked = true
                backgroundOpacity?.value = 255
                shadow?.isChecked = false
                outline?.isChecked = false
            }
            "3" -> {
                background?.isChecked = true
                backgroundOpacity?.value = 128
                shadow?.isChecked = false
            }
            "4" -> color?.saveValue(Color.YELLOW)
            "5" -> {
                color?.saveValue(Color.YELLOW)
                background?.isChecked = true
                backgroundOpacity?.value = 128
                shadow?.isChecked = false
            }
        }
    }

    private fun manageSubtitleVisibilities() {
        if (pref<Preference>(KEY_SUBTITLES_BACKGROUND) == null) return
        val background = settings.getBoolean(KEY_SUBTITLES_BACKGROUND, false)
        pref<Preference>(KEY_SUBTITLES_BACKGROUND_COLOR)?.isVisible = background
        pref<Preference>(KEY_SUBTITLES_BACKGROUND_COLOR_OPACITY)?.isVisible = background
        val shadow = settings.getBoolean(KEY_SUBTITLES_SHADOW, true)
        pref<Preference>(KEY_SUBTITLES_SHADOW_COLOR)?.isVisible = shadow
        pref<Preference>(KEY_SUBTITLES_SHADOW_COLOR_OPACITY)?.isVisible = shadow
        pref<Preference>("rd_shadow_distance")?.isVisible = shadow
        val outline = settings.getBoolean(KEY_SUBTITLES_OUTLINE, true)
        // the outline size stays hidden, as upstream (it draws badly)
        pref<Preference>(KEY_SUBTITLES_OUTLINE_SIZE)?.isVisible = false
        pref<Preference>(KEY_SUBTITLES_OUTLINE_COLOR)?.isVisible = outline
        pref<Preference>(KEY_SUBTITLES_OUTLINE_COLOR_OPACITY)?.isVisible = outline
    }

    // Controls

    private fun setupControls() {
        val tv = AndroidDevices.isAndroidTv
        pref<Preference>(POPUP_KEEPSCREEN)?.isVisible = !tv && !AndroidUtil.isOOrLater
        pref<Preference>(ENABLE_DOUBLE_TAP_SEEK)?.isVisible = !tv
        pref<Preference>(ENABLE_DOUBLE_TAP_PLAY)?.isVisible = !tv
        pref<Preference>(ENABLE_SCALE_GESTURE)?.isVisible = !tv
        pref<Preference>(ENABLE_SWIPE_SEEK)?.isVisible = !tv
        pref<Preference>(ENABLE_FASTPLAY)?.isVisible = !tv
        pref<Preference>(FASTPLAY_SPEED)?.isVisible = !tv
        pref<Preference>(SCREENSHOT_MODE)?.isVisible = !tv
        pref<Preference>(ENABLE_VOLUME_GESTURE)?.isVisible = AndroidDevices.hasTsp
        pref<Preference>(ENABLE_BRIGHTNESS_GESTURE)?.isVisible = AndroidDevices.hasTsp
        pref<Preference>(KEY_VIDEO_DOUBLE_TAP_JUMP_DELAY)?.title = getString(if (tv) R.string.video_key_jump_delay else R.string.video_double_tap_jump_delay)
        pref<Preference>(LOCK_USE_SENSOR)?.isVisible = !tv
        pref<Preference>(KEY_AUDIO_BOOST)?.isVisible = !tv
        updateHudTimeoutSummary()
        updateFastplaySpeedSummary()
        val audioManager = requireActivity().getSystemService<AudioManager>()
        if (audioManager?.isVolumeFixed == true) {
            pref<CheckBoxPreference>(KEY_AUDIO_BOOST)?.let {
                it.isChecked = false
                it.isEnabled = false
                it.summary = getString(R.string.system_volume_disabled, it.summary)
            }
            pref<CheckBoxPreference>(ENABLE_VOLUME_GESTURE)?.let {
                it.isChecked = false
                it.isEnabled = false
                it.summary = getString(R.string.system_volume_disabled, it.summary)
            }
        }
    }

    private fun updateHudTimeoutSummary() {
        val p = pref<Preference>(VIDEO_HUD_TIMEOUT) ?: return
        p.summary = if (Settings.videoHudDelay == -1) getString(R.string.timeout_infinite)
        else getString(R.string.video_hud_timeout_summary, Settings.videoHudDelay.toString())
    }

    private fun updateFastplaySpeedSummary() {
        pref<Preference>(FASTPLAY_SPEED)?.summary = String.format("%sx", Settings.fastplaySpeed.readableString())
    }

    companion object {
        private val AUDIO_CONTROLS = setOf(KEY_AUDIO_JUMP_DELAY, KEY_AUDIO_LONG_JUMP_DELAY, KEY_AUDIO_SHOW_TRACK_NUMBERS,
                KEY_AUDIO_SHOW_BOOkMARK_BUTTONS, "audio_show_bookmark_markers", "audio_show_chapter_buttons",
                "blurred_cover_background", "audio_force_shuffle")

        fun newInstance(page: RdPage, scrollTo: String? = null) = RdSettingsPage().apply {
            arguments = bundleOf(RD_PAGE to page.key, RD_SCROLL_TO to scrollTo)
        }
    }
}
