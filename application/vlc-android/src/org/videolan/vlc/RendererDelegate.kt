/*****************************************************************************
 * RendererDelegate.java
 *
 * Copyright © 2017 VLC authors and VideoLAN
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
package org.videolan.vlc

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.RendererDiscoverer
import org.videolan.libvlc.RendererItem
import org.videolan.resources.AppContextProvider
import org.videolan.resources.VLCInstance
import org.videolan.tools.AppScope
import org.videolan.tools.KEY_ENABLE_CASTING
import org.videolan.tools.NetworkMonitor
import org.videolan.tools.Settings
import org.videolan.tools.livedata.LiveDataset
import org.videolan.tools.putSingle
import org.videolan.tools.retry
import org.videolan.vlc.cast.CastController
import org.videolan.vlc.cast.FakeCastResponder
import org.videolan.vlc.cast.sameDevice

/**
 * VLC Redesign: the devices to cast to (the desktop's RendererManager).
 *
 * - It looks for them only while something that casts shows: the player, the cast list (see [observe]); a few
 *   seconds after the last one goes, the discoveries stop and are released.
 * - The same device found twice (two discoveries, two network interfaces, a name with and without its model) is
 *   listed once; it leaves the list only when no discovery sees it any more. The device cast to last comes first.
 * - The device playing that leaves the network goes to [CastController].
 */
object RendererDelegate : RendererDiscoverer.EventListener {

    private const val TAG = "VLC/RendererDelegate"
    private const val KEY_LAST_DEVICE = "rd_cast_last_device"
    /** the discoveries stay that long after the last screen that casts went (from the player to its list...) */
    private const val STOP_DELAY = 5000L
    /** "Looking for TVs…" that long, then "No device found…" */
    const val SEARCH_TIME = 12000L

    private val discoverers = ArrayList<RendererDiscoverer>()

    /** one row per device, the item shown for it (the one playing stays the same) */
    private class Entry(var shown: RendererItem) {
        val copies = mutableListOf(shown)
    }
    private val entries = ArrayList<Entry>()

    /** the devices, one per device (a new list at each change) */
    val renderers: LiveDataset<RendererItem> = LiveDataset()
    /** the discoveries run */
    val scanning = MutableLiveData(false)
    /** when they started (elapsed realtime) */
    var scanStartedAt = 0L
        private set

    @Volatile private var started = false
    private var holders = 0
    private var connected = false
    private var stopJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val context: Context get() = AppContextProvider.appContext

    init {
        NetworkMonitor.getInstance(AppContextProvider.appContext).connectionFlow.onEach {
            val was = connected
            connected = it.connected
            if (was && !connected) CastController.onNetworkLost()
            update()
        }.launchIn(AppScope)
    }

    /** Looks for devices while [owner] is started (shown) */
    fun observe(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = hold()
            override fun onStop(owner: LifecycleOwner) = unhold()
        })
    }

    fun hold() {
        holders++
        update()
    }

    fun unhold() {
        if (holders > 0) holders--
        update()
    }

    /** Casting switched on or off in the settings */
    fun settingsChanged() = update()

    fun castingEnabled() = Settings.getInstance(context).getBoolean(KEY_ENABLE_CASTING, true)

    private fun update() {
        val wanted = holders > 0 && connected && castingEnabled()
        if (wanted) {
            stopJob?.cancel()
            stopJob = null
            if (!started) AppScope.launch { start() }
        } else if (started && stopJob == null) {
            val wait = if (connected && castingEnabled()) STOP_DELAY else 0L
            stopJob = AppScope.launch {
                delay(wait)
                stopJob = null
                stop()
            }
        }
    }

    private suspend fun start() {
        if (started) return
        started = true
        val libVlc = withContext(Dispatchers.IO) { VLCInstance.getInstance(context) }
        if (!started) return
        acquireMulticast()
        scanStartedAt = SystemClock.elapsedRealtime()
        scanning.value = true
        if (BuildConfig.DEBUG) FakeCastResponder.start(context)
        for (discoverer in RendererDiscoverer.list(libVlc)) {
            if (!started) break
            val rd = RendererDiscoverer(libVlc, discoverer.name)
            discoverers.add(rd)
            rd.setEventListener(this@RendererDelegate)
            retry(5, 1000L) { started && !rd.isReleased && rd.start() }
        }
    }

    private fun stop() {
        if (!started) return
        started = false
        // (stop() releases the discovery and the items it found)
        for (discoverer in discoverers) if (!discoverer.isReleased) discoverer.stop()
        discoverers.clear()
        entries.clear()
        renderers.value = mutableListOf()
        scanning.value = false
        releaseMulticast()
        if (BuildConfig.DEBUG) FakeCastResponder.stop()
    }

    /** Wi-Fi drops multicast packets (mDNS answers) without a lock on many phones */
    private fun acquireMulticast() {
        if (multicastLock != null) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("VLC casting").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "no multicast lock: $e")
        }
    }

    private fun releaseMulticast() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    override fun onEvent(event: RendererDiscoverer.Event?) {
        val item = event?.item ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // (items are released with their event: the main thread keeps a reference first)
            item.retain()
            AppScope.launch {
                handle(event.type, item)
                item.release()
            }
        } else handle(event.type, item)
    }

    private fun handle(type: Int, item: RendererItem) {
        if (!started) return
        if (BuildConfig.DEBUG) Log.d(TAG, "${if (type == RendererDiscoverer.Event.ItemAdded) "added" else "removed"} ${item.displayName} (${item.type})")
        when (type) {
            RendererDiscoverer.Event.ItemAdded -> added(item)
            RendererDiscoverer.Event.ItemDeleted -> removed(item)
        }
    }

    private fun added(item: RendererItem) {
        val entry = entries.firstOrNull { sameDevice(it.shown, item) }
        if (entry == null) {
            // the device cast to last first: where it was the last time
            val last = Settings.getInstance(context).getString(KEY_LAST_DEVICE, "") ?: ""
            if (sameDevice(item.displayName, last)) entries.add(0, Entry(item)) else entries.add(Entry(item))
            publish()
            return
        }
        if (entry.copies.none { it == item }) entry.copies.add(item)
        // the same device found the other way: the longer name (with its model) shows; the one playing stays
        val shown = entry.shown.displayName
        if (!isPlayingOn(entry) && item.displayName.length > shown.length && item.displayName.startsWith(shown)) {
            entry.shown = item
            publish()
        }
    }

    private fun removed(item: RendererItem) {
        val entry = entries.firstOrNull { e -> e.copies.any { it == item } } ?: return
        entry.copies.removeAll { it == item }
        if (entry.copies.isNotEmpty()) {
            if (entry.shown == item) {
                entry.shown = entry.copies.first()
                publish()
            }
            return
        }
        entries.remove(entry)
        publish()
        // the device playing left the network
        if (isPlayingOn(entry)) CastController.onDeviceLost()
    }

    private fun isPlayingOn(entry: Entry) = PlaybackService.renderer.value?.let { r -> entry.copies.any { sameDevice(it, r) } || sameDevice(entry.shown, r) } == true

    private fun publish() {
        renderers.value = entries.map { it.shown }.toMutableList()
    }

    /** the device chosen: first in the list the next times */
    fun remember(item: RendererItem) {
        Settings.getInstance(context).putSingle(KEY_LAST_DEVICE, item.displayName)
    }
}
