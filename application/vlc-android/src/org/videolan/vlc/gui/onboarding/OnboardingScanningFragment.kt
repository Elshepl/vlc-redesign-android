package org.videolan.vlc.gui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import org.videolan.resources.KEY_ANIMATED
import org.videolan.tools.*
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.MediaParsingService
import org.videolan.vlc.R
import org.videolan.vlc.gui.SecondaryActivity

/**
 * VLC Redesign: the media folder of the desktop's welcome step. The field shows what the library will scan: the
 * folders chosen with Browse, or the whole device (VLC for Android's default); the cross removes the choice (no
 * scan). Debug builds never preselect the whole device: the library scope guard refuses a storage root, so they
 * start with no folder.
 */
class OnboardingScanningFragment : OnboardingFragment() {
    private val viewModel: OnboardingViewModel by activityViewModels()
    private lateinit var titleView: TextView
    private lateinit var folderValue: TextView
    private val preferences by lazy(LazyThreadSafetyMode.NONE) { Settings.getInstance(requireActivity()) }
    override fun getDefaultViewForTalkback() = titleView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.onboarding_scanning, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleView = view.findViewById(R.id.scanning_title)
        folderValue = view.findViewById(R.id.folder_value)
        if (BuildConfig.DEBUG && !viewModel.rerun && !viewModel.folderChosen && MediaParsingService.preselectedStorages.isEmpty()) setScan(false)
        view.findViewById<View>(R.id.folder_clear).setOnClickListener {
            MediaParsingService.preselectedStorages.clear()
            viewModel.folderChosen = true
            setScan(false)
        }
        view.findViewById<View>(R.id.customizeButton).setOnClickListener {
            val activity = requireActivity()
            viewModel.folderChosen = true
            setScan(true)
            val intent = Intent(activity.applicationContext, SecondaryActivity::class.java)
            intent.putExtra("fragment", SecondaryActivity.STORAGE_BROWSER_ONBOARDING)
            intent.putExtra(KEY_ANIMATED, true)
            activity.startActivity(intent)
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(R.anim.slide_in_bottom, R.anim.no_animation)
        }
    }

    override fun onResume() {
        super.onResume()
        // back from Browse: in a debug build nothing ticked there means no folder
        if (viewModel.folderChosen && MediaParsingService.preselectedStorages.isEmpty() && BuildConfig.DEBUG) setScan(false)
        showFolder()
    }

    private fun setScan(on: Boolean) {
        viewModel.scanStorages = on
        preferences.putSingle(KEY_MEDIALIBRARY_SCAN, if (on) ML_SCAN_ON else ML_SCAN_OFF)
        if (::folderValue.isInitialized) showFolder()
    }

    private fun showFolder() {
        // opened again from Settings: the library's own folders until the step changes them
        val folders = if (viewModel.rerun && !viewModel.folderChosen) viewModel.rerunFolders.ifEmpty {
            // the library may have started after the step was created
            (try { org.videolan.medialibrary.interfaces.Medialibrary.getInstance().foldersList?.toList() } catch (e: Exception) { null } ?: emptyList())
                .also { viewModel.rerunFolders = it }
        } else MediaParsingService.preselectedStorages
        val text = when {
            !viewModel.scanStorages -> getString(R.string.rd_ob_no_folder)
            folders.isEmpty() -> getString(R.string.rd_ob_whole_device)
            folders.size == 1 -> display(folders.first())
            else -> getString(R.string.rd_ob_folders_count, display(folders.first()), folders.size - 1)
        }
        folderValue.text = text
        folderValue.alpha = if (viewModel.scanStorages) 1f else 0.6f
        view?.findViewById<View>(R.id.folder_clear)?.visibility = if (viewModel.scanStorages) View.VISIBLE else View.INVISIBLE
    }

    private fun display(path: String) = path.removePrefix("file://").removePrefix("/storage/emulated/0").ifEmpty { "/" }

    companion object {
        fun newInstance() = OnboardingScanningFragment()
    }
}
