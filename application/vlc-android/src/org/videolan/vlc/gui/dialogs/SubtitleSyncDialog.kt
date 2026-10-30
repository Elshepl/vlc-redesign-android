/*
 * VLC Redesign: the subtitle sync timeline (SubtitleSyncTimeline.qml of the desktop), opened from Subtitle
 * timing of the tracks panel.
 *
 * While the video plays: the sound of the 20 s around the playing time (Waveform.levels, read a minute at a time
 * on a worker thread, again when the playhead nears either end of what was read) and, under it, the lines of the
 * subtitle track shown (SubtitleCues: its file, or the track of the video when the platform can read it). Dragging
 * the lines, the arrow keys or the 50 ms buttons change the subtitle delay at once; Reset puts it back to 0.
 * A centred window on a tablet, a sheet on a phone (wide enough for the timeline in landscape too).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.dialogs

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.dp
import org.videolan.tools.setGone
import org.videolan.tools.setVisible
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.databinding.RdSubtitleSyncBinding
import org.videolan.vlc.gui.audio.Waveform
import org.videolan.vlc.gui.view.SubtitleSyncView
import org.videolan.vlc.repository.SlaveRepository
import org.videolan.vlc.util.SeriesPreset
import org.videolan.vlc.util.SubtitleCues
import java.io.File
import java.util.Locale

class SubtitleSyncDialog : VLCBottomSheetDialogFragment() {

    private lateinit var binding: RdSubtitleSyncBinding
    private var levelsJob: Job? = null
    private var levelsUri: Uri? = null
    private var levelsFrom = Long.MIN_VALUE / 2
    private var cuesKey: String? = null
    private var dragging = false

    override fun getDefaultState() = BottomSheetBehavior.STATE_EXPANDED
    override fun needToManageOrientation() = true
    override fun initialFocusedView(): View = binding.rdSyncView
    override fun allowRemote() = true

    // a timeline wants width: never the narrow side sheet; centred on a tablet as the other sheets
    override fun useSideSheet() = false

    override fun windowWidth(): Int {
        val metrics = resources.displayMetrics
        val landscape = metrics.widthPixels > metrics.heightPixels
        return if (landscape || resources.getBoolean(R.bool.is_tablet)) minOf(metrics.widthPixels - 32.dp, 640.dp) else super.windowWidth()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = RdSubtitleSyncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rdSyncEarlier.text = String.format(Locale.US, "−%d ms", SubtitleSyncView.STEP_MS)
        binding.rdSyncLater.text = String.format(Locale.US, "+%d ms", SubtitleSyncView.STEP_MS)
        binding.rdSyncEarlier.setOnClickListener { setDelay(binding.rdSyncView.delay - SubtitleSyncView.STEP_MS) }
        binding.rdSyncLater.setOnClickListener { setDelay(binding.rdSyncView.delay + SubtitleSyncView.STEP_MS) }
        binding.rdSyncReset.setOnClickListener { setDelay(0L) }
        binding.rdSyncView.onDelayChanged = { delay, done ->
            dragging = !done
            changed = true
            PlaybackService.instance?.setSpuDelay(delay * 1000L)
            showDelay(delay)
        }
        super.onViewCreated(view, savedInstanceState)
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            // the lines are dragged sideways: the sheet itself never follows the finger
            BottomSheetBehavior.from(it).isDraggable = false
        }
        lifecycleScope.launch {
            while (isActive) {
                update()
                delay(100L)
            }
        }
    }

    private var changed = false

    override fun onDestroyView() {
        // the series of the video keeps its choices: the new delay too
        val service = PlaybackService.instance
        val key = SeriesPreset.key(service?.currentMediaWrapper?.uri)
        if (changed && service != null && key != null && SeriesPreset.has(requireContext(), key)) SeriesPreset.save(requireContext(), service)
        super.onDestroyView()
    }

    private fun setDelay(ms: Long) {
        val delay = SubtitleSyncView.step(ms)
        changed = true
        PlaybackService.instance?.setSpuDelay(delay * 1000L)
        binding.rdSyncView.delay = delay
        showDelay(delay)
    }

    private fun showDelay(ms: Long) {
        binding.rdSyncValue.text = String.format(Locale.US, "⁦%+d ms⁩", ms) // (isolated: "+50 ms" in a right to left language too)
        binding.rdSyncReset.isEnabled = ms != 0L
    }

    /** Ten times a second: the playing time, the delay (changed elsewhere too), what to read */
    private fun update() {
        val service = PlaybackService.instance ?: return
        val view = binding.rdSyncView
        val time = service.getTime()
        view.time = time
        if (!dragging) {
            val delay = SubtitleSyncView.step(service.spuDelay / 1000L)
            if (delay != view.delay) view.delay = delay
            showDelay(delay)
        }
        val media = service.currentMediaWrapper ?: return
        val uri = media.uri ?: return
        loadCues(service, media, uri)
        // read ahead: again when the playhead nears either end of what was read
        if (uri != levelsUri || time - levelsFrom < 8_000L && levelsFrom > 0L || time - levelsFrom > LEVELS_SPAN - 15_000L) loadLevels(uri, time)
    }

    private fun loadLevels(uri: Uri, time: Long) {
        if (levelsJob?.isActive == true && uri == levelsUri) return
        levelsJob?.cancel()
        levelsUri = uri
        val ctx = requireContext().applicationContext
        val from = maxOf(0L, time - 12_000L)
        levelsFrom = from
        levelsJob = lifecycleScope.launch {
            val show = { levels: FloatArray? ->
                if (isAdded && levelsUri == uri) {
                    binding.rdSyncView.levelsStart = from
                    binding.rdSyncView.levelsBinMs = BIN_MS
                    binding.rdSyncView.levels = levels
                }
            }
            // (what is read so far shows at once: a slow phone takes seconds for the minute)
            val levels = withContext(Dispatchers.IO) {
                Waveform.levels(ctx, uri, from, from + LEVELS_SPAN, BIN_MS) { part -> binding.root.post { show(part) } }
            }
            if (!isAdded || levelsUri != uri) return@launch
            show(levels)
            noSound = levels == null
            showStatus()
        }
    }

    private var noSound = false
    private var cuesStatus = 0

    /** The lines of the subtitle track shown: read again when another track is chosen */
    private fun loadCues(service: PlaybackService, media: MediaWrapper, uri: Uri) {
        val tracks = service.spuTracks?.filter { it.getId() != "-1" } ?: emptyList()
        val selected = service.spuTrack
        val key = "$uri|$selected|${tracks.size}"
        if (key == cuesKey) return
        cuesKey = key
        val index = tracks.indexOfFirst { it.getId() == selected }
        if (index < 0) {
            binding.rdSyncView.cues = emptyList()
            cuesStatus = R.string.rd_sync_no_track
            showStatus()
            return
        }
        val name = tracks[index].getName()
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            val cues = withContext(Dispatchers.IO) {
                if (uri.scheme != "file") return@withContext emptyList()
                val video = File(uri.path ?: return@withContext emptyList())
                // the files the player opened beside the tracks of the video: those next to it, those added
                val added = SlaveRepository.getInstance(ctx).getSlaves(media.location)
                        .mapNotNull { Uri.parse(it.uri).path?.let { p -> File(p) } }
                val files = (SubtitleCues.siblings(video) + added).distinctBy { it.absolutePath }
                val embedded = (tracks.size - files.size).coerceAtLeast(0)
                if (BuildConfig.DEBUG) Log.d(TAG, "track $index/${tracks.size} \"$name\", files ${files.map { it.name }}")
                if (index < embedded) SubtitleCues.readEmbedded(ctx, uri, index)
                else {
                    // the file whose name the track's carries, else in the order the player added them
                    val file = files.firstOrNull { name.contains(it.name) || name.contains(it.nameWithoutExtension) }
                            ?: files.getOrNull(index - embedded) ?: files.firstOrNull()
                    file?.let { SubtitleCues.readFile(it) } ?: emptyList()
                }
            }
            if (!isAdded || cuesKey != key) return@launch
            binding.rdSyncView.cues = cues
            cuesStatus = if (cues.isEmpty()) R.string.rd_sync_no_cues else 0
            showStatus()
        }
    }

    private fun showStatus() {
        val text = listOfNotNull(cuesStatus.takeIf { it != 0 }?.let { getString(it) }, if (noSound) getString(R.string.rd_sync_no_sound) else null)
        binding.rdSyncStatus.text = text.joinToString("\n")
        if (text.isEmpty()) binding.rdSyncStatus.setGone() else binding.rdSyncStatus.setVisible()
    }

    companion object {
        const val TAG = "VLC/SubtitleSync"
        private const val LEVELS_SPAN = 60_000L
        private const val BIN_MS = 50
    }
}
