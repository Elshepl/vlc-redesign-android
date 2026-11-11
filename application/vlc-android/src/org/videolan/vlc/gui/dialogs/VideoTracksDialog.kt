/*
 * ************************************************************************
 *  VideoTracksDialog.kt
 * *************************************************************************
 * Copyright © 2020-2026 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
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
 * **************************************************************************
 */

package org.videolan.vlc.gui.dialogs

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.DependencyProvider
import org.videolan.tools.KEY_SUBTITLES_TEXT_SCALE
import org.videolan.tools.Settings
import org.videolan.tools.SubtitleScale
import org.videolan.tools.dp
import org.videolan.tools.putSingle
import org.videolan.tools.setGone
import org.videolan.tools.setVisible
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.databinding.ItemRdSubtitleLanguageBinding
import org.videolan.vlc.databinding.ItemRdSubtitleVariantBinding
import org.videolan.vlc.databinding.PlayerOverlayTracksBinding
import org.videolan.vlc.getDisableTrack
import org.videolan.vlc.gui.dialogs.adapters.TrackAdapter
import org.videolan.vlc.gui.dialogs.adapters.VlcTrack
import org.videolan.vlc.gui.helpers.getBitmapFromDrawable
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.isVLC4
import org.videolan.vlc.util.OnlineSubtitles
import org.videolan.vlc.util.RdToast
import org.videolan.vlc.util.SeriesPreset
import org.videolan.vlc.util.SubtitleCharset
import org.videolan.vlc.util.isTalkbackIsEnabled
import java.util.Locale

class VideoTracksDialog : VLCBottomSheetDialogFragment() {
    override fun getDefaultState(): Int = STATE_EXPANDED

    override fun needToManageOrientation(): Boolean = true

    private lateinit var binding: PlayerOverlayTracksBinding

    override fun initialFocusedView(): View = binding.rdTracksTabs

    override fun allowRemote() = true

    lateinit var menuItemListener: (VideoTrackOption) -> Unit
    lateinit var trackSelectionListener: (String, TrackType) -> Unit

    private var rdService: PlaybackService? = null
    private var rdTabsKey: String? = null
    private var rdStream = false
    private var rdSection = 2
    private var rdSeriesKey: String? = null
    private var rdChanged = false
    private var rdSeriesBound = false

    private var voiceMark = -1L
    private var textMark = -1L

    // Subtitle panel state
    private var selectedLangCode: String? = null
    private var onlineResults = listOf<OnlineSubtitles.Result>()
    private var onlineOutcome: OnlineSubtitles.Outcome? = null
    private var searchJob: Job? = null
    private var titleSearchJob: Job? = null
    private var downloadingId: String? = null
    private var currentMedia: OnlineSubtitles.Media? = null

    private var languageAdapter: LanguageAdapter? = null
    private var variantAdapter: VariantAdapter? = null

    data class SubtitleLang(
        val code: String,
        val name: String,
        val nativeName: String = "",
        val count: Int = 0,
        val isDefault: Boolean = false,
        val isOff: Boolean = false
    )

    data class SubtitleVariant(
        val id: String,
        val title: String,
        val detail: String,
        val langCode: String,
        val isLocalTrack: Boolean,
        val trackId: String = "",
        val checked: Boolean = false,
        val onlineResult: OnlineSubtitles.Result? = null,
        val exact: Boolean = false,
        val hearingImpaired: Boolean = false,
        val isBusy: Boolean = false
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = PlayerOverlayTracksBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.audioTracks.trackTitle.text = getString(R.string.audio)
        binding.videoTracks.trackTitle.text = getString(R.string.video)

        binding.audioTracks.trackList.layoutManager = LinearLayoutManager(requireActivity())
        binding.videoTracks.trackList.layoutManager = LinearLayoutManager(requireActivity())

        binding.videoTracks.trackMore.setGone()

        generateSeparator(binding.audioTracks.options)
        generateOptionItem(binding.audioTracks.options, getString(R.string.audio_delay), R.drawable.ic_delay, VideoTrackOption.AUDIO_DELAY)
        generateSeparator(binding.audioTracks.options, true)
        binding.audioTracks.options.setAnimationUpdateListener {
            binding.audioTracks.trackMore.rotation = if (binding.audioTracks.options.isCollapsed) 180F - (180F * it) else 180F * it
        }

        if (requireActivity().isTalkbackIsEnabled()) {
            binding.audioTracks.options.onReady {
                binding.audioTracks.trackMore.setGone()
                binding.audioTracks.options.lock()
            }
        }

        binding.audioTracks.trackMore.setOnClickListener {
            binding.audioTracks.options.toggle()
        }

        setupTabs()
        setupSubtitlesPanel()
        setupTiming()
        setupPositionSizeAndRtl()

        PlaybackService.serviceFlow.onEach { onServiceChanged(it) }.launchIn(lifecycleScope)
    }

    private fun onServiceChanged(service: PlaybackService?) {
        service?.let { playbackService ->
            rdService = playbackService
            updateTiming()

            playbackService.videoTracks?.let { trackList ->
                val trackAdapter = TrackAdapter(generateTrackList(trackList), trackList.firstOrNull { it.getId() == playbackService.videoTrack }, getString(R.string.track_video))
                trackAdapter.setOnTrackSelectedListener { track ->
                    trackSelectionListener.invoke(track.getId(), TrackType.VIDEO)
                }
                binding.videoTracks.trackList.adapter = trackAdapter
                binding.videoTracks.trackTitle.contentDescription = getString(R.string.talkback_video_tracks)
            }

            playbackService.audioTracks?.let { trackList ->
                val trackAdapter = TrackAdapter(generateTrackList(trackList), trackList.firstOrNull { it.getId() == playbackService.audioTrack }, getString(R.string.track_audio))
                trackAdapter.setOnTrackSelectedListener { track ->
                    rdChanged = true
                    trackSelectionListener.invoke(track.getId(), TrackType.AUDIO)
                }
                binding.audioTracks.trackList.adapter = trackAdapter
                binding.audioTracks.trackTitle.contentDescription = getString(R.string.talkback_audio_tracks)
            }

            applyMediaKind(playbackService)
            setupSeries(playbackService)
            setupSubtitlesSeries(playbackService)
            updateSubtitlePanel()

            if (!rdStream && onlineResults.isEmpty() && searchJob == null) {
                startOnlineSearch(identify = true)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun generateTrackList(trackList: Array<out VlcTrack>) = if (isVLC4()) {
        val tempTracks = ArrayList<VlcTrack>()
        tempTracks.add(getDisableTrack(requireActivity()))
        trackList.forEach { tempTracks.add(it) }
        tempTracks.toList().toTypedArray()
    } else trackList as Array<VlcTrack>

    private fun setupTabs() {
        binding.rdTracksTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showSection(tab.tag as? Int ?: 2)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        configureTabs(hasVideo = true, subtitles = true)
    }

    private fun configureTabs(hasVideo: Boolean, subtitles: Boolean) {
        val key = "$hasVideo/$subtitles"
        if (key == rdTabsKey) return
        rdTabsKey = key
        val tabs = binding.rdTracksTabs
        val selected = tabs.getTabAt(tabs.selectedTabPosition)?.tag as? Int
        tabs.removeAllTabs()
        val ids = buildList { if (hasVideo) add(0); add(1); if (subtitles) add(2) }
        ids.forEach { id ->
            val tab = tabs.newTab()
            tab.setText(listOf(R.string.video, R.string.audio, R.string.subtitles)[id])
            val iconRes = when (id) {
                0 -> R.drawable.rd_ic_track_video
                1 -> R.drawable.rd_ic_track_audio
                else -> R.drawable.rd_ic_track_subtitles
            }
            tab.setIcon(iconRes)
            tab.setTag(id)
            tabs.addTab(tab, false)
        }
        val target = selected?.takeIf { it in ids } ?: ids.last()
        tabs.getTabAt(ids.indexOf(target))?.select()
        showSection(target)
    }

    private fun sections() = listOf(
        listOf<View>(binding.videoTracks.root),
        if (rdStream) listOf<View>(binding.audioTracks.root) else listOf<View>(binding.audioTracks.root, binding.rdSeries.root),
        listOf<View>(binding.subtitlesPanel.root)
    )

    private fun showSection(index: Int) {
        rdSection = index
        val shown = sections().getOrElse(index) { emptyList() }
        (sections().flatten() + binding.rdSeries.root).distinct().forEach {
            it.visibility = if (it in shown) View.VISIBLE else View.GONE
        }
    }

    private fun applyMediaKind(service: PlaybackService) {
        val media = service.currentMediaWrapper
        val hasVideo = service.videoTracksCount > 0
        val stream = media != null && (media.type == MediaWrapper.TYPE_STREAM || media.uri?.scheme.let { it != null && it != "file" && it != "content" })
        val liveStream = stream && (media?.length ?: 0L) <= 0L
        val streamHasSubs = (service.spuTracks?.size ?: 0) > 0
        rdStream = liveStream
        configureTabs(hasVideo, hasVideo && (!liveStream || streamHasSubs))
        showSection(rdSection)

        val panel = binding.subtitlesPanel
        if (liveStream) {
            panel.rdSubTimingSection.setGone()
            panel.rdSubSeriesSection.setGone()
            panel.rdSubPositionSizeSection.setGone()
            panel.rdSubSearchTitleBtn.setGone()
            panel.rdSubRefreshBtn.setGone()
            panel.rdSubAddFileBtn.setGone()
        } else {
            panel.rdSubTimingSection.setVisible()
            panel.rdSubSeriesSection.setVisible()
            panel.rdSubPositionSizeSection.setVisible()
            panel.rdSubSearchTitleBtn.setVisible()
            panel.rdSubRefreshBtn.setVisible()
            panel.rdSubAddFileBtn.setVisible()
        }
    }

    // --- Subtitles Panel Setup ---

    private fun setupSubtitlesPanel() {
        val panel = binding.subtitlesPanel
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        panel.rdSubLanguagesRecycler.layoutManager = if (isLandscape) {
            LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        } else {
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        }

        panel.rdSubVariantsRecycler.layoutManager = LinearLayoutManager(requireContext())

        languageAdapter = LanguageAdapter { lang ->
            selectedLangCode = lang.code
            updateSubtitlePanel()
        }
        panel.rdSubLanguagesRecycler.adapter = languageAdapter

        variantAdapter = VariantAdapter { variant ->
            onVariantSelected(variant)
        }
        panel.rdSubVariantsRecycler.adapter = variantAdapter

        // Action Buttons
        panel.rdSubSearchTitleBtn.setOnClickListener {
            val visible = panel.rdSubTitleSearchBox.isVisible
            panel.rdSubTitleSearchBox.isVisible = !visible
            if (!visible) {
                panel.rdSubTitleInput.requestFocus()
            }
        }

        panel.rdSubRefreshBtn.setOnClickListener {
            startOnlineSearch(identify = true)
        }

        panel.rdSubAddFileBtn.setOnClickListener {
            menuItemListener.invoke(VideoTrackOption.SUB_PICK)
            dismiss()
        }

        panel.rdSubTitleGo.setOnClickListener {
            val text = panel.rdSubTitleInput.text.toString().trim()
            if (text.isNotEmpty()) findTitles(text)
        }

        panel.rdSubTitleInput.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val text = panel.rdSubTitleInput.text.toString().trim()
                if (text.isNotEmpty()) findTitles(text)
                true
            } else false
        }
    }

    private fun extractLanguage(trackName: String): String {
        val bracket = Regex("\\[(.*?)\\]").find(trackName)?.groupValues?.get(1)?.trim()
        if (!bracket.isNullOrEmpty()) {
            val code = OnlineSubtitles.normalize(bracket)
            if (code.isNotEmpty()) return code
            val fromEng = OnlineSubtitles.fromEnglishName(bracket)
            if (fromEng.isNotEmpty()) return fromEng
            return bracket.lowercase(Locale.ROOT)
        }
        return ""
    }

    private fun formatDownloads(count: Int): String {
        return if (count >= 1000) {
            String.format(Locale.US, "%.1fk downloads", count / 1000.0)
        } else {
            "$count downloads"
        }
    }

    private fun updateSubtitlePanel() {
        val service = rdService ?: return
        val localTracks = service.spuTracks ?: emptyArray()
        val currentTrackId = service.spuTrack ?: "-1"

        val allVariants = mutableListOf<SubtitleVariant>()

        // 1. Local Tracks
        for (t in localTracks) {
            val id = t.getId()
            if (id == "-1") continue
            val name = t.getName() ?: id
            val lang = extractLanguage(name)
            val isChecked = (id == currentTrackId)
            allVariants.add(
                SubtitleVariant(
                    id = "local_$id",
                    title = name,
                    detail = getString(R.string.rd_in_the_video),
                    langCode = lang,
                    isLocalTrack = true,
                    trackId = id,
                    checked = isChecked
                )
            )
        }

        // 2. Online Search Results
        for (res in onlineResults) {
            val detailParts = mutableListOf<String>()
            if (res.provider.isNotEmpty()) detailParts.add(res.provider)
            if (res.downloads > 0) detailParts.add(formatDownloads(res.downloads))
            if (res.foreignOnly) detailParts.add(getString(R.string.rd_os_foreign))

            allVariants.add(
                SubtitleVariant(
                    id = res.id,
                    title = res.release,
                    detail = detailParts.joinToString(" · "),
                    langCode = res.language,
                    isLocalTrack = false,
                    onlineResult = res,
                    exact = res.exact,
                    hearingImpaired = res.hearingImpaired,
                    isBusy = (downloadingId == res.id)
                )
            )
        }

        // 3. Languages Grouping
        val langCounts = mutableMapOf<String, Int>()
        for (item in allVariants) {
            val code = item.langCode
            if (code.isNotEmpty()) {
                langCounts[code] = (langCounts[code] ?: 0) + 1
            }
        }

        val preferredLang = OnlineSubtitles.preferredLanguage(requireContext())
        val languages = mutableListOf<SubtitleLang>()

        // "Off" option at top
        val anyChecked = allVariants.any { it.isLocalTrack && it.checked }
        languages.add(
            SubtitleLang(
                code = "off",
                name = getString(R.string.rd_off),
                count = 0,
                isOff = true
            )
        )

        for ((code, count) in langCounts) {
            val displayName = if (code.length <= 3) Locale(code).getDisplayLanguage(Locale.getDefault()) else code.replaceFirstChar { it.uppercase() }
            val nativeName = OnlineSubtitles.nativeName(code)
            val isDef = (code == preferredLang)
            languages.add(
                SubtitleLang(
                    code = code,
                    name = displayName.ifEmpty { code },
                    nativeName = if (nativeName.isNotEmpty() && !nativeName.equals(displayName, true)) nativeName else if (isDef) getString(R.string.rd_default) else "",
                    count = count,
                    isDefault = isDef
                )
            )
        }

        languages.sortWith { a, b ->
            when {
                a.isOff -> -1
                b.isOff -> 1
                a.isDefault -> -1
                b.isDefault -> 1
                else -> b.count.compareTo(a.count)
            }
        }

        if (selectedLangCode == null) {
            val checkedVariant = allVariants.firstOrNull { it.checked }
            selectedLangCode = when {
                !anyChecked -> "off"
                checkedVariant != null && checkedVariant.langCode.isNotEmpty() -> checkedVariant.langCode
                languages.any { it.isDefault } -> preferredLang
                languages.size > 1 -> languages[1].code
                else -> "off"
            }
        }

        val filteredVariants = if (selectedLangCode == "off") {
            emptyList()
        } else {
            allVariants.filter { it.langCode == selectedLangCode }
        }

        languageAdapter?.submit(languages, selectedLangCode)
        variantAdapter?.submit(filteredVariants)

        binding.subtitlesPanel.rdSubEmptyView.isVisible = filteredVariants.isEmpty() && selectedLangCode != "off"
    }

    private fun onVariantSelected(variant: SubtitleVariant) {
        rdChanged = true
        if (variant.isLocalTrack) {
            rdService?.setSpuTrack(variant.trackId)
            trackSelectionListener.invoke(variant.trackId, TrackType.SPU)
            updateSubtitlePanel()
        } else {
            loadOnlineSubtitle(variant)
        }
    }

    private fun loadOnlineSubtitle(variant: SubtitleVariant) {
        val res = variant.onlineResult ?: return
        val m = currentMedia ?: return
        val ctx = requireContext().applicationContext
        val videoActivity = activity as? VideoPlayerActivity

        downloadingId = res.id
        updateSubtitlePanel()

        lifecycleScope.launch {
            val (file, problem) = OnlineSubtitles.download(ctx, res, m)
            downloadingId = null
            if (file == null) {
                updateSubtitlePanel()
                RdToast.makeText(ctx, getString(R.string.rd_os_err_download, res.provider), RdToast.LENGTH_SHORT).show()
                return@launch
            }
            if (videoActivity != null) {
                videoActivity.rdAddOnlineSubtitle(file, res, select = true)
            } else {
                rdService?.addUtf8SubtitleTrack(file.absolutePath, select = true)
            }
            RdToast.makeText(ctx, "${res.release} loaded", RdToast.LENGTH_SHORT).show()
            updateSubtitlePanel()
        }
    }

    private fun startOnlineSearch(match: OnlineSubtitles.Match? = null, identify: Boolean = true) {
        val s = rdService ?: return
        val mw = s.currentMediaWrapper ?: return
        val uri = mw.uri ?: return
        val title = mw.title ?: uri.lastPathSegment ?: ""
        val ctx = requireContext().applicationContext

        searchJob?.cancel()
        val panel = binding.subtitlesPanel
        panel.rdSubBusy.setVisible()
        panel.rdSubRefreshBtn.isEnabled = false
        panel.rdSubStatus.text = getString(R.string.rd_os_looking)

        searchJob = lifecycleScope.launch {
            val m = currentMedia ?: withContext(Dispatchers.IO) { OnlineSubtitles.mediaOf(uri, title) }.also { currentMedia = it }
            val found = if (identify) withContext(Dispatchers.IO) { OnlineSubtitles.identify(ctx, m) } else match
            val outcome = OnlineSubtitles.search(ctx, m, found)
            onlineOutcome = outcome
            onlineResults = outcome.results.map { it.result }

            panel.rdSubBusy.setGone()
            panel.rdSubRefreshBtn.isEnabled = true

            val matchTitle = outcome.match?.title ?: m.title
            panel.rdSubStatus.text = if (outcome.results.isNotEmpty()) {
                getString(R.string.rd_title_and_count, matchTitle, outcome.results.size)
            } else {
                getString(R.string.rd_no_subtitles_found)
            }

            val servicesText = outcome.asked.joinToString("  ·  ") { if (it.failed) "${it.name} –" else "${it.name} ${it.count}" }
            panel.rdSubServices.text = servicesText
            panel.rdSubServices.isVisible = servicesText.isNotEmpty()

            updateSubtitlePanel()
        }
    }

    private fun findTitles(text: String) {
        if (text.isBlank()) return
        titleSearchJob?.cancel()
        val panel = binding.subtitlesPanel
        panel.rdSubBusy.setVisible()
        panel.rdSubStatus.text = getString(R.string.rd_os_searching)

        titleSearchJob = lifecycleScope.launch {
            val titles = withContext(Dispatchers.IO) { OnlineSubtitles.findTitles(text) }
            panel.rdSubBusy.setGone()
            val ctx = requireContext()
            if (titles.isEmpty()) {
                panel.rdSubStatus.text = getString(R.string.rd_os_err_not_found_title, text.trim())
                return@launch
            }
            panel.rdSubTitleResults.isVisible = true
            panel.rdSubTitleResults.layoutManager = LinearLayoutManager(ctx)
            panel.rdSubTitleResults.adapter = TitleResultAdapter(titles) { match ->
                currentMedia?.let { OnlineSubtitles.rememberMatch(ctx, it, match) }
                panel.rdSubTitleSearchBox.isVisible = false
                panel.rdSubTitleResults.isVisible = false
                startOnlineSearch(match, identify = false)
            }
        }
    }

    // --- Subtitle Timing ---

    private fun setupTiming() {
        val panel = binding.subtitlesPanel

        panel.rdSubDelaySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                rdService?.setSpuDelay(value.toLong() * 1000L)
                rdChanged = true
            }
            showDelay(value.toLong())
        }

        panel.rdSubDelayReset.setOnClickListener {
            rdService?.setSpuDelay(0L)
            rdChanged = true
            voiceMark = -1L
            textMark = -1L
            updateTiming()
        }

        panel.rdSubMarkVoice.setOnClickListener {
            voiceMark = if (voiceMark >= 0) -1L else rdService?.getTime() ?: -1L
            applyMarks()
        }

        panel.rdSubMarkText.setOnClickListener {
            textMark = if (textMark >= 0) -1L else rdService?.getTime() ?: -1L
            applyMarks()
        }

        panel.rdSubSyncOpen.setOnClickListener {
            val fm = parentFragmentManager
            dismiss()
            if (fm.findFragmentByTag("subtitle_sync") == null) SubtitleSyncDialog().show(fm, "subtitle_sync")
        }
    }

    private fun updateTiming() {
        if (!::binding.isInitialized) return
        val panel = binding.subtitlesPanel
        val delay = ((rdService?.spuDelay ?: 0L) / 1000L).coerceIn(-10000L, 10000L) / 50L * 50L
        panel.rdSubDelaySlider.value = delay.toFloat()
        showDelay(delay)
        panel.rdSubMarkVoice.text = getString(if (voiceMark >= 0) R.string.rd_voice_marked else R.string.rd_mark_voice_heard)
        panel.rdSubMarkText.text = getString(if (textMark >= 0) R.string.rd_text_marked else R.string.rd_mark_text_seen)
        panel.rdSubMarkVoice.isChecked = voiceMark >= 0
        panel.rdSubMarkText.isChecked = textMark >= 0
    }

    private fun showDelay(ms: Long) {
        binding.subtitlesPanel.rdSubDelayValue.text = String.format(Locale.US, "%+d ms", ms)
    }

    private fun applyMarks() {
        val service = rdService ?: return
        if (voiceMark >= 0 && textMark >= 0) {
            val delay = (service.spuDelay / 1000L + (voiceMark - textMark)).coerceIn(-10000L, 10000L)
            service.setSpuDelay(delay / 50L * 50L * 1000L)
            rdChanged = true
            voiceMark = -1L
            textMark = -1L
        }
        updateTiming()
    }

    // --- Series Preset ---

    private fun setupSeries(service: PlaybackService) {
        val series = binding.rdSeries
        val key = SeriesPreset.key(service.currentMediaWrapper?.uri)
        if (rdSeriesBound && key == rdSeriesKey) return
        rdSeriesBound = true
        rdSeriesKey = key
        series.rdSeriesSwitch.setOnCheckedChangeListener(null)
        series.rdSeriesSwitch.isEnabled = key != null
        series.rdSeriesSwitch.isChecked = key != null && SeriesPreset.has(requireContext(), key)
        series.rdSeriesDetail.text = if (key == null) getString(R.string.rd_series_play_local) else getString(R.string.rd_series_applies, SeriesPreset.label(key))
        series.rdSeriesSwitch.setOnCheckedChangeListener { _, on ->
            val k = rdSeriesKey ?: return@setOnCheckedChangeListener
            if (on) {
                if (SeriesPreset.save(requireContext(), service)) series.rdSeriesDetail.text = getString(R.string.rd_series_saved, SeriesPreset.label(k))
            } else {
                SeriesPreset.remove(requireContext(), k)
                series.rdSeriesDetail.text = getString(R.string.rd_series_removed)
            }
        }
    }

    private fun setupSubtitlesSeries(service: PlaybackService) {
        val panel = binding.subtitlesPanel
        val key = SeriesPreset.key(service.currentMediaWrapper?.uri)
        val label = if (key != null) SeriesPreset.label(key) else ""
        val hasPreset = key != null && SeriesPreset.has(requireContext(), key)

        panel.rdSeriesDetail.text = if (key == null) {
            getString(R.string.rd_series_play_local)
        } else {
            getString(R.string.rd_series_applies, label)
        }

        panel.rdSeriesSave.isEnabled = (key != null)
        panel.rdSeriesRemove.isEnabled = hasPreset

        panel.rdSeriesSave.setOnClickListener {
            val k = key ?: return@setOnClickListener
            if (SeriesPreset.save(requireContext(), service)) {
                panel.rdSeriesDetail.text = getString(R.string.rd_series_saved, label)
                panel.rdSeriesRemove.isEnabled = true
            }
        }

        panel.rdSeriesRemove.setOnClickListener {
            val k = key ?: return@setOnClickListener
            SeriesPreset.remove(requireContext(), k)
            panel.rdSeriesDetail.text = getString(R.string.rd_series_removed)
            panel.rdSeriesRemove.isEnabled = false
        }
    }

    // --- Position, Size, and RTL ---

    private fun setupPositionSizeAndRtl() {
        val panel = binding.subtitlesPanel
        val settings = Settings.getInstance(requireContext())

        // 1. Position
        var pos = settings.getInt("rd_sub_position", 0)
        fun updatePosUI() {
            panel.rdSubPosValue.text = when {
                pos == 0 -> getString(R.string.rd_position_usual)
                pos > 0 -> "+$pos%"
                else -> "$pos%"
            }
            panel.rdSubPosMinus.isEnabled = pos > -20
            panel.rdSubPosPlus.isEnabled = pos < 60
            panel.rdSubPosReset.isEnabled = pos != 0
        }
        updatePosUI()

        panel.rdSubPosMinus.setOnClickListener {
            pos = (pos - 1).coerceIn(-20, 60)
            settings.putSingle("rd_sub_position", pos)
            settings.putSingle("rd_sub_margin", (pos * 5).coerceIn(0, 400))
            rdChanged = true
            updatePosUI()
        }

        panel.rdSubPosPlus.setOnClickListener {
            pos = (pos + 1).coerceIn(-20, 60)
            settings.putSingle("rd_sub_position", pos)
            settings.putSingle("rd_sub_margin", (pos * 5).coerceIn(0, 400))
            rdChanged = true
            updatePosUI()
        }

        panel.rdSubPosReset.setOnClickListener {
            pos = 0
            settings.putSingle("rd_sub_position", 0)
            settings.putSingle("rd_sub_margin", 0)
            rdChanged = true
            updatePosUI()
        }

        // 2. Size
        var currentScale = SubtitleScale.get(settings)
        fun updateSizeUI() {
            panel.rdSubSizeValue.text = "$currentScale%"
            panel.rdSubSizeMinus.isEnabled = currentScale > SubtitleScale.MIN
            panel.rdSubSizePlus.isEnabled = currentScale < SubtitleScale.MAX
            panel.rdSubSizeReset.isEnabled = currentScale != SubtitleScale.DEFAULT
        }
        updateSizeUI()

        panel.rdSubSizeMinus.setOnClickListener {
            currentScale = SubtitleScale.step(currentScale, false)
            applySize(currentScale)
            updateSizeUI()
        }

        panel.rdSubSizePlus.setOnClickListener {
            currentScale = SubtitleScale.step(currentScale, true)
            applySize(currentScale)
            updateSizeUI()
        }

        panel.rdSubSizeReset.setOnClickListener {
            currentScale = SubtitleScale.DEFAULT
            applySize(currentScale)
            updateSizeUI()
        }

        // 3. RTL
        var rtlEnabled = settings.getBoolean("rd_subtitle_rtl_fix", true)
        fun updateRtlUI() {
            panel.rdSubRtlToggle.text = if (rtlEnabled) getString(R.string.rd_on) else getString(R.string.rd_off)
        }
        updateRtlUI()

        panel.rdSubRtlToggle.setOnClickListener {
            rtlEnabled = !rtlEnabled
            settings.putSingle("rd_subtitle_rtl_fix", rtlEnabled)
            updateRtlUI()

            // Force reload current subtitle track so the change takes effect immediately on screen
            val s = rdService
            val track = s?.spuTrack
            if (track != null && track != "-1") {
                s.setSpuTrack("-1")
                view?.postDelayed({ s.setSpuTrack(track) }, 150L)
            }
        }
    }

    private fun applySize(scale: Int) {
        rdChanged = true
        (activity as? VideoPlayerActivity)?.setSubtitleScale(scale, osd = false)
            ?: Settings.getInstance(requireContext()).putSingle(KEY_SUBTITLES_TEXT_SCALE, SubtitleScale.clamp(scale))
    }

    private fun generateSeparator(parent: ViewGroup, margin: Boolean = false) {
        val sep = View(context)
        sep.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.white_transparent_50))
        val lp = LinearLayout.LayoutParams(-1, 1.dp)
        lp.marginStart = if (margin) 56.dp else 16.dp
        lp.marginEnd = 16.dp
        lp.topMargin = 8.dp
        lp.bottomMargin = 8.dp
        sep.layoutParams = lp
        parent.addView(sep)
    }

    private fun generateOptionItem(parent: ViewGroup, title: String, @DrawableRes icon: Int, optionId: VideoTrackOption) {
        val optionView = layoutInflater.inflate(R.layout.player_overlay_track_option_item, null)
        optionView.findViewById<TextView>(R.id.option_title).text = title
        optionView.findViewById<ImageView>(R.id.option_icon).setImageBitmap(requireContext().getBitmapFromDrawable(icon))
        optionView.setOnClickListener {
            menuItemListener.invoke(optionId)
            dismiss()
        }
        parent.addView(optionView)
    }

    override fun onDestroyView() {
        val key = rdSeriesKey
        val service = rdService
        if (rdChanged && key != null && service != null && SeriesPreset.has(requireContext(), key)) {
            SeriesPreset.save(requireContext(), service)
        }
        searchJob?.cancel()
        titleSearchJob?.cancel()
        super.onDestroyView()
    }

    // --- Adapters ---

    class LanguageAdapter(private val onSelect: (SubtitleLang) -> Unit) : RecyclerView.Adapter<LanguageAdapter.Holder>() {
        private var list = listOf<SubtitleLang>()
        private var activeCode: String? = null

        fun submit(newList: List<SubtitleLang>, active: String?) {
            list = newList
            activeCode = active
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemRdSubtitleLanguageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun getItemCount() = list.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = list[position]
            val isActive = (item.code == activeCode)
            holder.bind(item, isActive)
            holder.itemView.setOnClickListener { onSelect(item) }
        }

        class Holder(private val b: ItemRdSubtitleLanguageBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: SubtitleLang, isActive: Boolean) {
                b.rdLangName.text = item.name
                b.rdLangCount.text = if (item.count > 0) item.count.toString() else ""
                b.rdLangCount.isVisible = item.count > 0

                if (b.rdLangDetail != null) {
                    b.rdLangDetail.text = item.nativeName
                    b.rdLangDetail.isVisible = item.nativeName.isNotEmpty()
                }

                if (b.rdLangIndicator != null) {
                    b.rdLangIndicator.isVisible = isActive
                }

                val isLandscape = itemView.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                if (!isLandscape) {
                    b.rdLangRoot.setBackgroundResource(
                        if (isActive) R.drawable.rd_chip_accent_bg else R.drawable.rd_chip_normal_bg
                    )
                }
            }
        }
    }

    class VariantAdapter(private val onSelect: (SubtitleVariant) -> Unit) : RecyclerView.Adapter<VariantAdapter.Holder>() {
        private var list = listOf<SubtitleVariant>()

        fun submit(newList: List<SubtitleVariant>) {
            list = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemRdSubtitleVariantBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun getItemCount() = list.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = list[position]
            holder.bind(item)
            holder.itemView.setOnClickListener { onSelect(item) }
        }

        class Holder(private val b: ItemRdSubtitleVariantBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: SubtitleVariant) {
                b.rdVariantTitle.text = item.title
                b.rdVariantDetail.text = item.detail
                b.rdVariantDetail.isVisible = item.detail.isNotEmpty()

                b.rdVariantCheck.isVisible = item.checked && !item.isBusy
                b.rdVariantProgress.isVisible = item.isBusy

                b.rdVariantBadgeExact.isVisible = item.exact
                b.rdVariantBadgeHi.isVisible = item.hearingImpaired
            }
        }
    }

    class TitleResultAdapter(
        private val list: List<Pair<OnlineSubtitles.Match, String>>,
        private val onSelect: (OnlineSubtitles.Match) -> Unit
    ) : RecyclerView.Adapter<TitleResultAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rd_online_subtitle, parent, false)
            return Holder(view)
        }

        override fun getItemCount() = list.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (match, detail) = list[position]
            holder.bind(match, detail)
            holder.itemView.setOnClickListener { onSelect(match) }
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val release: TextView = itemView.findViewById(R.id.rd_os_row_release)
            private val detail: TextView = itemView.findViewById(R.id.rd_os_row_detail)
            private val check: View? = itemView.findViewById(R.id.rd_os_row_check)

            fun bind(match: OnlineSubtitles.Match, detailText: String) {
                release.text = if (match.year > 0) "${match.title} (${match.year})" else match.title
                detail.text = detailText
                check?.isVisible = false
            }
        }
    }

    companion object : DependencyProvider<Any>() {
        const val TAG = "VLC/VideoTracksDialog"
    }

    enum class TrackType {
        VIDEO, AUDIO, SPU
    }

    enum class VideoTrackOption {
        SUB_DELAY, SUB_PICK, SUB_DOWNLOAD, AUDIO_DELAY
    }
}
