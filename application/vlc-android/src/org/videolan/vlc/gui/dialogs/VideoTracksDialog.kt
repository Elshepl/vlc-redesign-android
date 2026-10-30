/*
 * ************************************************************************
 *  AddToGroupDialog.kt
 * *************************************************************************
 * Copyright © 2020 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
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
 *
 *
 */

package org.videolan.vlc.gui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.DependencyProvider
import org.videolan.tools.dp
import org.videolan.tools.putSingle
import org.videolan.tools.setGone
import org.videolan.tools.setVisible
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.VlcMigrationHelper
import org.videolan.vlc.databinding.PlayerOverlayTracksBinding
import org.videolan.vlc.getDisableTrack
import org.videolan.vlc.gui.dialogs.adapters.TrackAdapter
import org.videolan.vlc.gui.dialogs.adapters.VlcTrack
import org.videolan.vlc.gui.helpers.getBitmapFromDrawable
import org.videolan.vlc.util.SeriesPreset
import org.videolan.vlc.util.isTalkbackIsEnabled
import org.videolan.vlc.isVLC4
import com.google.android.material.tabs.TabLayout

class VideoTracksDialog : VLCBottomSheetDialogFragment() {
    override fun getDefaultState(): Int = STATE_EXPANDED

    override fun needToManageOrientation(): Boolean = true

    private lateinit var binding: PlayerOverlayTracksBinding

    override fun initialFocusedView(): View = binding.subtitleTracks.emptyView

    override fun allowRemote() = true

    lateinit var menuItemListener: (VideoTrackOption) -> Unit
    lateinit var trackSelectionListener: (String, TrackType) -> Unit

    private fun onServiceChanged(service: PlaybackService?) {
        service?.let { playbackService ->
            // VLC Redesign: each kind of track has its own tab, so a single video track still shows in its tab
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
            playbackService.spuTracks?.let { trackList ->
                // VLC Redesign, casting: the subtitles too (the Chromecast output burns them into the picture)
                val trackAdapter = TrackAdapter(generateTrackList(trackList), trackList.firstOrNull { it.getId() == playbackService.spuTrack }, getString(R.string.track_text))
                trackAdapter.setOnTrackSelectedListener { track ->
                    rdChanged = true
                    trackSelectionListener.invoke(track.getId(), TrackType.SPU)
                }
                binding.subtitleTracks.trackList.adapter = trackAdapter
                if (trackList.isEmpty()) binding.subtitleTracks.emptyView.setVisible()
                binding.subtitleTracks.trackTitle.contentDescription = getString(R.string.talkback_subtitle_tracks)
            }
            if (playbackService.spuTracks == null) binding.subtitleTracks.emptyView.setVisible()
            applyMediaKind(playbackService)
            setupSeries(playbackService)
        }
    }

    /**
     * Create a track list containing a fake "Disable track" track
     *
     * @param trackList the base track list
     * @return a complete track list
     */
    @Suppress("UNCHECKED_CAST")
    private fun generateTrackList(trackList: Array<out VlcTrack>) = if (isVLC4()) {
        val tempTracks = ArrayList<VlcTrack>()
        tempTracks.add(getDisableTrack(requireActivity()))
        trackList.forEach {
            tempTracks.add(it)
        }
        tempTracks.toList().toTypedArray()
    } else trackList as Array<VlcTrack>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = PlayerOverlayTracksBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.audioTracks.trackTitle.text = getString(R.string.audio)
        binding.videoTracks.trackTitle.text = getString(R.string.video)
        binding.subtitleTracks.trackTitle.text = getString(R.string.subtitles)

        binding.audioTracks.trackList.layoutManager = LinearLayoutManager(requireActivity())
        binding.videoTracks.trackList.layoutManager = LinearLayoutManager(requireActivity())
        binding.subtitleTracks.trackList.layoutManager = LinearLayoutManager(requireActivity())

        binding.videoTracks.trackMore.setGone()

        //prevent focus
        binding.tracksSeparator3.isEnabled = false
        binding.tracksSeparator2.isEnabled = false



        generateSeparator(binding.audioTracks.options)
        generateOptionItem(binding.audioTracks.options, getString(R.string.audio_delay), R.drawable.ic_delay, VideoTrackOption.AUDIO_DELAY)
        generateSeparator(binding.audioTracks.options, true)
        binding.audioTracks.options.setAnimationUpdateListener {
            binding.audioTracks.trackMore.rotation = if (binding.audioTracks.options.isCollapsed) 180F - (180F * it) else 180F * it
        }


        generateSeparator(binding.subtitleTracks.options)
        generateOptionItem(binding.subtitleTracks.options, getString(R.string.spu_delay), R.drawable.ic_delay, VideoTrackOption.SUB_DELAY)
        generateOptionItem(binding.subtitleTracks.options, getString(R.string.subtitle_select), R.drawable.ic_subtitles_file, VideoTrackOption.SUB_PICK)
        if (VlcMigrationHelper.isLolliPopOrLater) generateOptionItem(binding.subtitleTracks.options, getString(R.string.download_subtitles), R.drawable.ic_download_subtitles, VideoTrackOption.SUB_DOWNLOAD)
        generateSeparator(binding.subtitleTracks.options, true)
        binding.subtitleTracks.options.setAnimationUpdateListener {
            binding.subtitleTracks.trackMore.rotation = if (binding.subtitleTracks.options.isCollapsed) 180F - (180F * it) else 180F * it
        }

        binding.audioTracks.trackMore.setOnClickListener {
            binding.audioTracks.options.toggle()
            binding.subtitleTracks.options.collapse()
        }

        if (requireActivity().isTalkbackIsEnabled()) {
            binding.subtitleTracks.options.onReady {
                binding.subtitleTracks.trackMore.setGone()
                binding.subtitleTracks.options.lock()
            }
            binding.audioTracks.options.onReady {
                binding.audioTracks.trackMore.setGone()
                binding.audioTracks.options.lock()
            }
        }

        binding.subtitleTracks.trackMore.setOnClickListener {
            binding.subtitleTracks.options.toggle()
            binding.audioTracks.options.collapse()
        }
        setupTabs()
        setupTiming()
        super.onViewCreated(view, savedInstanceState)
        PlaybackService.serviceFlow.onEach { onServiceChanged(it) }.launchIn(lifecycleScope)
    }

    // VLC Redesign: the tabs of the desktop panel (Video, Audio, Subtitles), opening on Subtitles
    private var rdTabsKey: String? = null

    private fun setupTabs() {
        binding.rdTracksTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showSection(tab.tag as? Int ?: 2)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        configureTabs(hasVideo = true, subtitles = true)
    }

    private var rdStream = false

    // (the series preset under the audio tracks and under the subtitles: a local file only)
    private fun sections() = listOf(listOf<View>(binding.videoTracks.root),
            if (rdStream) listOf<View>(binding.audioTracks.root) else listOf<View>(binding.audioTracks.root, binding.rdSeries.root),
            if (rdStream) listOf<View>(binding.subtitleTracks.root) else listOf<View>(binding.subtitleTracks.root, binding.rdSubTiming.root, binding.rdSeries.root))

    private var rdSection = 2

    private fun showSection(index: Int) {
        rdSection = index
        val shown = sections().getOrElse(index) { emptyList() }
        (sections().flatten() + binding.rdSeries.root).distinct().forEach { it.visibility = if (it in shown) View.VISIBLE else View.GONE }
    }

    // The series preset: on, the choices are kept for the episodes of the series (and again as they change here)
    private var rdSeriesKey: String? = null
    private var rdChanged = false
    private var rdSeriesBound = false

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

    override fun onDestroyView() {
        // a choice changed here while the series keeps its choices: kept again
        val key = rdSeriesKey
        val service = rdService
        if (rdChanged && key != null && service != null && SeriesPreset.has(requireContext(), key)) SeriesPreset.save(requireContext(), service)
        super.onDestroyView()
    }

    /**
     * The owner's rule for every platform: subtitles are for films. Audio only: no Subtitles tab at all (and no
     * Video tab). A live/network stream: only its own subtitle tracks (on, off, language) when it carries any,
     * never a file, an online search or the timing.
     */
    private fun configureTabs(hasVideo: Boolean, subtitles: Boolean) {
        val key = "$hasVideo/$subtitles"
        if (key == rdTabsKey) return
        rdTabsKey = key
        val tabs = binding.rdTracksTabs
        val selected = tabs.getTabAt(tabs.selectedTabPosition)?.tag as? Int
        tabs.removeAllTabs()
        val ids = buildList { if (hasVideo) add(0); add(1); if (subtitles) add(2) }
        ids.forEach { id -> tabs.addTab(tabs.newTab().setText(listOf(R.string.video, R.string.audio, R.string.subtitles)[id]).setTag(id), false) }
        val target = selected?.takeIf { it in ids } ?: ids.last()
        tabs.getTabAt(ids.indexOf(target))?.select()
        showSection(target)
    }

    private fun applyMediaKind(service: PlaybackService) {
        val media = service.currentMediaWrapper
        val hasVideo = service.videoTracksCount > 0
        val stream = media != null && (media.type == MediaWrapper.TYPE_STREAM || media.uri?.scheme.let { it != null && it != "file" && it != "content" })
        val streamHasSubs = (service.spuTracks?.size ?: 0) > 0
        rdStream = stream
        configureTabs(hasVideo, hasVideo && (!stream || streamHasSubs))
        showSection(rdSection)
        if (stream) {
            binding.rdSubTiming.root.setGone()
            binding.subtitleTracks.trackMore.setGone()
            binding.subtitleTracks.options.setGone()
        }
    }

    private var rdService: PlaybackService? = null
    private var voiceMark = -1L
    private var textMark = -1L

    // Subtitle timing: the delay slider (ms, the player takes µs) and the voice/text marks
    private fun setupTiming() {
        val timing = binding.rdSubTiming
        timing.rdSubDelaySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                rdService?.setSpuDelay(value.toLong() * 1000L)
                rdChanged = true
            }
            showDelay(value.toLong())
        }
        timing.rdSubDelayReset.setOnClickListener {
            rdService?.setSpuDelay(0L)
            rdChanged = true
            voiceMark = -1L
            textMark = -1L
            updateTiming()
        }
        timing.rdSubMarkVoice.setOnClickListener {
            voiceMark = if (voiceMark >= 0) -1L else rdService?.getTime() ?: -1L
            applyMarks()
        }
        timing.rdSubMarkText.setOnClickListener {
            textMark = if (textMark >= 0) -1L else rdService?.getTime() ?: -1L
            applyMarks()
        }
        // the sync timeline takes the panel's place (the video stays visible above it)
        timing.rdSubSyncOpen.setOnClickListener {
            val fm = parentFragmentManager
            dismiss()
            if (fm.findFragmentByTag("subtitle_sync") == null) SubtitleSyncDialog().show(fm, "subtitle_sync")
        }
        setupSize()
    }

    /**
     * #10: the subtitle size, 25 to 500 % (SubtitleScale): the value follows the thumb, the playing subtitles take
     * the size as it is let go (VideoPlayerActivity.setSubtitleScale: libvlc 3 reads it when it starts, so the
     * video is opened again in place, as for the video effects); Reset: 100 %.
     */
    private fun setupSize() {
        val timing = binding.rdSubTiming
        val settings = org.videolan.tools.Settings.getInstance(requireContext())
        val current = org.videolan.tools.SubtitleScale.get(settings)
        timing.rdSubSizeSlider.value = (current / 5 * 5).toFloat().coerceIn(timing.rdSubSizeSlider.valueFrom, timing.rdSubSizeSlider.valueTo)
        showSize(current)
        timing.rdSubSizeSlider.addOnChangeListener { _, value, fromUser -> if (fromUser) showSize(value.toInt()) }
        timing.rdSubSizeSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) = applySize(slider.value.toInt())
        })
        // keys (D-pad, keyboard) move the slider without a touch
        timing.rdSubSizeSlider.setOnKeyListener { v, _, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP) v.post { applySize(timing.rdSubSizeSlider.value.toInt()) }
            false
        }
        timing.rdSubSizeReset.setOnClickListener {
            timing.rdSubSizeSlider.value = org.videolan.tools.SubtitleScale.DEFAULT.toFloat()
            showSize(org.videolan.tools.SubtitleScale.DEFAULT)
            applySize(org.videolan.tools.SubtitleScale.DEFAULT)
        }
    }

    private fun applySize(scale: Int) {
        rdChanged = true
        (activity as? org.videolan.vlc.gui.video.VideoPlayerActivity)?.setSubtitleScale(scale, osd = false)
            ?: org.videolan.tools.Settings.getInstance(requireContext()).putSingle(org.videolan.tools.KEY_SUBTITLES_TEXT_SCALE, org.videolan.tools.SubtitleScale.clamp(scale))
        showSize(scale)
    }

    private fun showSize(scale: Int) {
        binding.rdSubTiming.rdSubSizeValue.text = String.format(java.util.Locale.getDefault(), "⁦%d %%⁩", scale) // (isolated: "100 %" in a right to left language too)
    }

    /** Once both are marked: the text came (text - voice) late, so the subtitles move that much earlier. */
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

    private fun updateTiming() {
        if (!::binding.isInitialized) return
        val timing = binding.rdSubTiming
        val delay = ((rdService?.spuDelay ?: 0L) / 1000L).coerceIn(-10000L, 10000L) / 50L * 50L
        timing.rdSubDelaySlider.value = delay.toFloat()
        showDelay(delay)
        timing.rdSubMarkVoice.text = getString(if (voiceMark >= 0) R.string.rd_voice_marked else R.string.rd_mark_voice_heard)
        timing.rdSubMarkText.text = getString(if (textMark >= 0) R.string.rd_text_marked else R.string.rd_mark_text_seen)
        timing.rdSubMarkVoice.isChecked = voiceMark >= 0
        timing.rdSubMarkText.isChecked = textMark >= 0
    }

    private fun showDelay(ms: Long) {
        binding.rdSubTiming.rdSubDelayValue.text = String.format(java.util.Locale.US, "%+d ms", ms)
    }

    private fun generateSeparator(parent: ViewGroup, margin: Boolean = false) {
        val view = View(context)
        view.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.white_transparent_50))
        val lp = LinearLayout.LayoutParams(-1, 1.dp)

        lp.marginStart = if (margin) 56.dp else 16.dp
        lp.marginEnd = 16.dp
        lp.topMargin = 8.dp
        lp.bottomMargin = 8.dp
        view.layoutParams = lp
        parent.addView(view)
    }

    private fun generateOptionItem(parent: ViewGroup, title: String, @DrawableRes icon: Int, optionId: VideoTrackOption) {
        val view = layoutInflater.inflate(R.layout.player_overlay_track_option_item, null)
        view.findViewById<TextView>(R.id.option_title).text = title
        view.findViewById<ImageView>(R.id.option_icon).setImageBitmap(requireContext().getBitmapFromDrawable(icon))
        view.setOnClickListener {
            menuItemListener.invoke(optionId)
            dismiss()
        }
        parent.addView(view)
    }

    companion object : DependencyProvider<Any>() {

        const val TAG = "VLC/SavePlaylistDialog"
    }

    enum class TrackType {
        VIDEO, AUDIO, SPU
    }

    enum class VideoTrackOption {
        SUB_DELAY, SUB_PICK, SUB_DOWNLOAD, AUDIO_DELAY
    }
}

