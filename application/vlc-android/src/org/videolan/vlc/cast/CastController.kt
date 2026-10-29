/*
 * VLC Redesign, casting: what the cast does (the desktop's RendererManager cast status, retry and "Play here").
 *
 * libvlc 3 says nothing of the device itself: the cast is *connecting* from the device chosen until the time
 * moves (the Chromecast output gives the device's time), *playing* then, and *failed* when the output fails
 * (the device does not answer, refuses the media, or stops by itself before the end). A failure while it was
 * connecting keeps the casting screen with Try again / Play here; the device lost while it played (it left the
 * network, the phone's network dropped) plays here again at once, where it was, with a message.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.cast

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.RendererItem
import org.videolan.resources.AppContextProvider
import org.videolan.tools.AppScope
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.RendererDelegate
import videolan.org.commontools.LiveEvent
import kotlin.math.abs

object CastController {
    private const val TAG = "VLC/Cast"
    /** no picture there after that long: it does not answer */
    private const val CONNECT_TIMEOUT = 30_000L
    /** the device left the discovery: lost if its time stays still that long */
    private const val LOST_CHECK = 8_000L
    /** an end that far before the end of a film: the device stopped by itself */
    private const val END_MARGIN = 15_000L

    /** a message to show (the device lost, back here) */
    val notice = LiveEvent<String>()

    private var item: RendererItem? = null
    private var timeAtStart = -1L
    private var lastTime = -1L
    private var lastTimeAt = 0L
    private var timeoutJob: Job? = null
    private var lostJob: Job? = null
    /** when the cast was left after its output broke: its last end / error events are not the media's */
    private var leftAt = 0L
    private fun justLeft() = SystemClock.elapsedRealtime() - leftAt < 3000L

    private val state get() = CastState.state.value

    /** The time of the media on the device, last seen */
    val time get() = lastTime

    /** PlaybackService.setRenderer: [new] chosen (null: back here) */
    fun rendererChanged(new: RendererItem?) {
        item = new
        lostJob?.cancel()
        timeoutJob?.cancel()
        if (new == null) {
            CastState.state.value = CastState.State()
            return
        }
        RendererDelegate.remember(new)
        // (where the media is now: back here from there if the device never plays)
        PlaybackService.instance?.let { if (it.hasMedia()) lastTime = it.getTime() }
        connecting(new)
    }

    private fun connecting(new: RendererItem) {
        timeAtStart = -1L
        CastState.state.value = CastState.State(CastState.Status.CONNECTING, new.displayName, new.canVideo())
        armTimeout()
    }

    /** still connecting after a while: it does not answer (the time a pause lasts does not count) */
    private fun armTimeout() {
        timeoutJob?.cancel()
        timeoutJob = AppScope.launch {
            delay(CONNECT_TIMEOUT)
            if (state.status != CastState.Status.CONNECTING) return@launch
            if (org.videolan.vlc.media.PlayerController.playbackState == android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED) armTimeout()
            else fail(CastState.Error.UNREACHABLE)
        }
    }

    private fun playing() {
        timeoutJob?.cancel()
        if (state.status != CastState.Status.PLAYING) CastState.state.value = state.copy(status = CastState.Status.PLAYING, error = null)
    }

    private fun fail(error: CastState.Error) {
        timeoutJob?.cancel()
        if (BuildConfig.DEBUG) Log.d(TAG, "failed: $error")
        CastState.state.value = state.copy(status = CastState.Status.FAILED, error = error)
        // the output stops: nothing plays on the device nor here until Try again / Play here
        PlaybackService.instance?.let { if (it.isPlaying) it.pause() }
    }

    /** The events of the media player while it casts */
    fun onEvent(service: PlaybackService, event: MediaPlayer.Event) {
        if (item == null) return
        when (event.type) {
            MediaPlayer.Event.TimeChanged -> {
                val t = event.timeChanged
                if (t != lastTime) {
                    lastTime = t
                    lastTimeAt = SystemClock.elapsedRealtime()
                }
                if (timeAtStart < 0) timeAtStart = t
                else if (state.status == CastState.Status.CONNECTING && abs(t - timeAtStart) >= 300) playing()
            }
            MediaPlayer.Event.Opening -> if (state.status == CastState.Status.PLAYING) item?.let { connecting(it) }
            // (EncounteredError: PlaylistManager asks errorIsCast, so that it neither skips nor says "invalid location")
        }
    }

    /**
     * The media ended while casting. True when it is a failure (the playlist does not go on):
     * the device never played it (it refused it), or it stopped well before the end (its own remote).
     */
    fun endIsFailure(service: PlaybackService): Boolean {
        if (item == null) return justLeft()
        if (state.status == CastState.Status.FAILED) return true
        if (state.status == CastState.Status.CONNECTING) {
            fail(CastState.Error.REFUSED)
            return true
        }
        val length = service.length
        if (length <= 0L || lastTime in 0 until length - END_MARGIN) {
            fail(CastState.Error.STOPPED)
            return true
        }
        return false
    }

    /** An error of the media while casting: the casting screen says it (no "invalid location", no next media) */
    fun errorIsCast(): Boolean {
        if (item == null) return justLeft()
        when (state.status) {
            // the connection broke while it played (the device went off, left the network): back here at once
            CastState.Status.PLAYING -> lost()
            CastState.Status.CONNECTING -> fail(CastState.Error.UNREACHABLE)
            else -> {}
        }
        return true
    }

    /** Try again: the same device, from where it was */
    fun retry() {
        val service = PlaybackService.instance ?: return
        val device = item ?: return
        connecting(device)
        service.restartCast(device, lastTime.coerceAtLeast(0L))
    }

    /** Play here (Stop casting): back on the phone, where it was */
    fun playHere() {
        PlaybackService.renderer.value = null
    }

    /** The device playing left the discovery: lost if its time stays still */
    fun onDeviceLost() {
        if (item == null) return
        lostJob?.cancel()
        val seen = lastTimeAt
        lostJob = AppScope.launch {
            delay(LOST_CHECK)
            val service = PlaybackService.instance ?: return@launch
            if (item != null && lastTimeAt == seen && (service.isPlaying || state.status != CastState.Status.PLAYING)) lost()
        }
    }

    /** The phone's network dropped: no device can be reached */
    fun onNetworkLost() {
        if (item != null) lost()
    }

    private fun lost() {
        if (item == null) return
        val name = state.name
        if (BuildConfig.DEBUG) Log.d(TAG, "lost $name")
        timeoutJob?.cancel()
        // (failed: back here, the media opens again where the device was)
        CastState.state.value = state.copy(status = CastState.Status.FAILED, error = CastState.Error.UNREACHABLE)
        leftAt = SystemClock.elapsedRealtime()
        playHere()
        // (after the player has turned back into the video: it is made again for it)
        AppScope.launch {
            delay(800L)
            notice.value = AppContextProvider.appContext.getString(R.string.rd_cast_no_answer, name)
        }
    }
}
