/*
 * VLC Redesign, Live TV: watching, as a TV box (the desktop's iptv_addon.cpp "Watching"): the channel alone in
 * the queue, not kept in the history nor in the library; the channels up and down through the list it was
 * chosen in, the previous one, a number typed; a past programme from the channel's archive.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.Settings
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.media.MediaUtils

object TvPlayback {

    /** A programme of the archive playing: its channel, start, end and title. */
    data class Catchup(val key: String, val start: Long, val stop: Long, val title: String)

    /** The channel playing ("" none), and the programme of the archive when one plays. */
    val current = MutableStateFlow("")
    @Volatile var catchup: Catchup? = null
        private set
    /** Each switch (the banner shows at each one) */
    val switches = MutableStateFlow(0L)

    private var previousKey = ""
    @Volatile private var currentUrl = ""
    /** the addresses of the channels started (their options): a channel left is still one when its end is saved */
    private val started = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 300
    }
    /** the keys of the list the channel was chosen in (a group, the favourites, a search) */
    private var zapKeys: List<String> = emptyList()

    private fun optionsOf(url: String?): List<String>? {
        if (url == null) return null
        return synchronized(started) { started[url] ?: started[Uri.decode(url)] ?: started.entries.firstOrNull { Uri.decode(it.key) == Uri.decode(url) }?.value }
    }

    /** The address is a channel's (or a programme of its archive): kept in no history. */
    fun isTvUrl(url: String?) = optionsOf(url) != null

    /** The address playing is the channel's now (its keys work only then). */
    fun isCurrent(url: String?): Boolean {
        val u = currentUrl
        return current.value.isNotEmpty() && u.isNotEmpty() && url != null && (url == u || Uri.decode(url) == Uri.decode(u))
    }

    /** The options of the stream (user agent, referrer) for libvlc: PlaylistManager adds them to its media. */
    fun mediaOptions(url: String?): List<String> = optionsOf(url)?.let { it + ":no-sub-autodetect-file" } ?: emptyList()

    fun channel(key: String = current.value): TvChannel? = LiveTv.state.value.byKey[key]

    fun play(context: Context, key: String, list: List<String>? = null) {
        val c = channel(key)
        if (c == null) {
            LiveTv.message(context.getString(R.string.rd_tv_cannot_play))
            return
        }
        if (list != null) zapKeys = list
        if (key != current.value) previousKey = current.value
        catchup = null
        LiveTv.watched(key)
        start(context, c.channel.url, c.name, c.channel.options, !c.channel.radio)
        current.value = key
        switches.value = switches.value + 1
    }

    fun playCatchup(context: Context, key: String, start: Long, stop: Long, title: String) {
        val c = channel(key) ?: return
        val (url, error) = LiveTv.catchupUrl(c, start, stop)
        if (url.isEmpty()) {
            LiveTv.message(context.getString(when (error) {
                "too old" -> R.string.rd_tv_too_old
                "not yet" -> R.string.rd_tv_not_yet
                else -> R.string.rd_tv_no_archive
            }))
            return
        }
        if (key != current.value) previousKey = current.value
        start(context, url, if (title.isEmpty()) c.name else c.name + " – " + title, c.channel.options, !c.channel.radio)
        current.value = key
        catchup = Catchup(key, start, stop, title)
        switches.value = switches.value + 1
    }

    /** What is on, from its start (the archive). */
    fun playFromStart(context: Context) {
        val c = channel() ?: return
        val now = c.now(System.currentTimeMillis() / 1000)
        if (now == null) {
            LiveTv.message(context.getString(R.string.rd_tv_guide_unknown))
            return
        }
        playCatchup(context, c.key, now.start, now.stop, now.title)
    }

    fun backToLive(context: Context) = play(context, current.value)

    /** The channels up (+1) and down (-1) through the list the channel was chosen in, all of them when it is not in it. */
    fun zap(context: Context, step: Int) {
        val key = current.value
        val adult = Settings.getInstance(context).getBoolean(KEY_IPTV_ADULT, false)
        val all = LiveTv.state.value.channels
        var keys = zapKeys.filter { LiveTv.state.value.byKey.containsKey(it) }
        if (!keys.contains(key)) keys = all.filter { adult || !it.channel.adult }.map { it.key }
        if (keys.isEmpty()) return
        val i = keys.indexOf(key)
        val next = if (i < 0) 0 else (i + step + keys.size) % keys.size
        play(context, keys[next])
    }

    fun previousChannel(context: Context) {
        if (previousKey.isNotEmpty() && channel(previousKey) != null) play(context, previousKey)
    }

    fun playNumber(context: Context, number: Int): Boolean {
        val adult = Settings.getInstance(context).getBoolean(KEY_IPTV_ADULT, false)
        val c = LiveTv.state.value.channels.firstOrNull { it.number == number && (adult || !it.channel.adult) }
        if (c == null) {
            LiveTv.message(context.getString(R.string.rd_tv_no_channel, number.toString()))
            return false
        }
        play(context, c.key)
        return true
    }

    /** A film or an episode of an account: no channel, no keys of the TV. */
    fun playUrl(context: Context, url: String, title: String) {
        currentUrl = ""
        current.value = ""
        catchup = null
        open(context, url, title, true)
    }

    /** The playback left the channel (another media, stopped). */
    fun ended() {
        if (current.value.isEmpty()) return
        current.value = ""
        currentUrl = ""
        catchup = null
    }

    private fun start(context: Context, url: String, name: String, options: List<String>, video: Boolean) {
        currentUrl = url
        synchronized(started) { started[url] = options.toList() }
        open(context, url, name, video)
    }

    private fun open(context: Context, url: String, name: String, video: Boolean) {
        val mw = MLServiceLocator.getAbstractMediaWrapper(Uri.parse(url)).apply {
            title = name
            type = if (video) MediaWrapper.TYPE_VIDEO else MediaWrapper.TYPE_STREAM
            addFlags(MediaWrapper.MEDIA_NO_PARSE)
        }
        // the channel alone in the queue, as a TV switches channels
        val service = PlaybackService.instance
        if (service != null) service.load(listOf(mw), 0)
        else MediaUtils.openList(context, listOf(mw), 0)
    }
}
