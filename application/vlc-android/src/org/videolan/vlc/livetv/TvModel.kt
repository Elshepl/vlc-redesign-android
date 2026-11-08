/*
 * VLC Redesign, Live TV (IPTV): the data of the add-on, as the desktop's modules/misc/iptv (playlist.hpp,
 * guide.hpp, service.hpp). Pure data, read and written on the add-on's worker thread.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

/** How a channel's past programmes are reached (catch-up), from the playlist or guessed from its address. */
enum class CatchupMode {
    NONE,
    /** the address with ?utc=<start>&lutc=<now> added (catchup="default"/"append"/"shift") */
    APPEND,
    /** the address of catchup-source, its {placeholders} filled in */
    TEMPLATE,
    /** Flussonic: .../name.m3u8 becomes .../name-<start>-<duration>.m3u8 */
    FLUSSONIC,
    /** Xtream Codes: /timeshift/<user>/<pass>/<minutes>/<start>/<id>.ts */
    XTREAM,
}

/** A channel of a playlist (an M3U entry or an Xtream Codes live stream). */
class Channel {
    var name = ""
    var url = ""
    /** the category ("News", "Israel"...), empty when none */
    var group = ""
    /** the channel's id in the guide (XMLTV) */
    var tvgId = ""
    /** the name in the guide when the id is missing */
    var tvgName = ""
    var logo = ""
    /** the number the provider gives the channel (tvg-chno), 0 when none */
    var number = 0
    /** tvg-shift: the guide of the channel is this many seconds off */
    var guideShiftSecs = 0
    var catchup = CatchupMode.NONE
    var catchupDays = 0
    var catchupSource = ""
    /** Xtream Codes: the server's offset from UTC, for its timeshift addresses */
    var timeOffsetSecs = 0
    /** options VLC needs for this stream (#EXTVLCOPT, http-user-agent...) */
    val options = ArrayList<String>(0)
    var radio = false
    /** adult content (by its group or name), hidden unless allowed */
    var adult = false

    fun copy() = Channel().also {
        it.name = name; it.url = url; it.group = group; it.tvgId = tvgId; it.tvgName = tvgName; it.logo = logo
        it.number = number; it.guideShiftSecs = guideShiftSecs; it.catchup = catchup; it.catchupDays = catchupDays
        it.catchupSource = catchupSource; it.timeOffsetSecs = timeOffsetSecs; it.options.addAll(options)
        it.radio = radio; it.adult = adult
    }
}

class Playlist {
    val channels = ArrayList<Channel>()
    /** the guides the playlist names in its header (url-tvg, x-tvg-url) */
    val guideUrls = ArrayList<String>()
    /** why nothing was read, empty on success */
    var error = ""
}

/** A programme as read from the guide. */
class Programme {
    var start = 0L          // seconds since the epoch, UTC
    var stop = 0L
    var title = ""
    var subtitle = ""       // the episode title
    var description = ""
    var category = ""
    var episode = ""        // "S01E02"
    var language = ""       // of the title ("he", "en"), empty when not given
}

/**
 * A programme kept in memory: its description stays on disk (descOffset in the file of its guide), a guide of
 * 200 MB would not fit otherwise.
 */
class Entry(
    @JvmField val start: Long,
    @JvmField val stop: Long,
    @JvmField val title: String,
    @JvmField val subtitle: String,
    @JvmField val category: String,
    @JvmField val episode: String,
    @JvmField val descOffset: Long,
    @JvmField val descLength: Int,
)

/** The programmes of a channel, sorted by start. */
typealias EntryList = List<Entry>

/** The programme on at time t in a sorted list, -1 when none. */
fun EntryList.indexAt(t: Long): Int {
    var lo = 0
    var hi = size
    // the last one starting at or before t
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].start <= t) lo = mid + 1 else hi = mid
    }
    val i = lo - 1
    return if (i >= 0 && t < this[i].stop) i else -1
}

/** The first programme ending after t (what is on, or the next one), size when none. */
fun EntryList.firstEndingAfter(t: Long): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].stop <= t) lo = mid + 1 else hi = mid
    }
    return lo
}

/** The automatic guide of a source: the channels matched, the free guides added for them, when. */
class AutoGuide(val found: Int, val total: Int, val guides: List<String>, val at: Long)

/**
 * A source of channels: an M3U playlist (a link or a file), an Xtream Codes account, or an account of a TV server
 * or a paid guide (TvAccounts), with its guides.
 */
class SourceConfig {
    var id = ""
    var name = ""
    /** "m3u", "xtream" or "account" */
    var kind = "m3u"
    /** an account's type (TvAccounts.TYPES: jellyfin, emby, tvheadend...) */
    var accountType = ""
    /** an account's PIN, API key, access code, guide link and sign-in token (secrets), its user's id, the token's end */
    var pin = ""
    var key = ""
    var code = ""
    var guideUrl = ""
    var token = ""
    var userId = ""
    var tokenUntil = 0L
    /** the guides the user turned off */
    val guidesOff = ArrayList<String>()
    /** the automatic guide found for its channels (null: none), and whether the user took it away */
    var autoGuide: AutoGuide? = null
    var autoGuideOff = false
    /** the playlist (secret: often holds a token) */
    var url = ""
    /** the guides the user added */
    val epg = ArrayList<String>()
    var server = ""
    var username = ""
    /** (secret) */
    var password = ""
    /** added from the catalog */
    var catalogId = ""
    var enabled = true
    /** the guides the playlist names itself */
    var useHeaderGuides = true

    /** What its account type needs, from it. */
    fun account() = TvAccount(server, username, password, pin, key, code, url, guideUrl, token, userId, tokenUntil)
}

/** A guide of a source and what it gives: from its playlist, added by the user, found for its channels, its account's. */
data class GuideStatus(
    val url: String,
    val kind: String,
    val on: Boolean,
    val channels: Int,
    val programmes: Int,
    val error: String,
    val updated: Long,
)

/** What a source holds and what the add-on is doing with it, for the interface. */
data class SourceStatus(
    val id: String,
    val name: String,
    val kind: String,
    val enabled: Boolean,
    val catalogId: String,
    val channels: Int,
    val withGuide: Int,
    val catchupDays: Int,
    val busy: Boolean,
    val stage: String,
    val received: Long,
    val error: String,
    val updated: Long,
    val vod: Boolean,
    val series: Boolean,
    val epg: List<String>,
    val accountType: String = "",
    /** its account's sign-in refused (a search, an update): said on its row until it works */
    val refused: Boolean = false,
    /** the last Sign in / Test */
    val check: TvCheck? = null,
    val checking: Boolean = false,
    val autoFound: Int = 0,
    val autoTotal: Int = 0,
    val autoBusy: Boolean = false,
    /** looked for, none found */
    val autoNone: Boolean = false,
    val guides: List<GuideStatus> = emptyList(),
    /** an account's server address (no secret in it): a server found on the network and added isn't offered again */
    val address: String = "",
)

/** A channel of the add-on: its key, its source, its guide when one has it. */
class TvChannel(
    val key: String,
    val sourceId: String,
    val channel: Channel,
    val number: Int,
    val guide: EntryList?,
    val descPath: String,
) {
    val name get() = channel.name
    val logo get() = channel.logo
    val group get() = channel.group
    val catchupDays get() = if (channel.catchup != CatchupMode.NONE) channel.catchupDays else 0
    val hasGuide get() = !guide.isNullOrEmpty()

    /** What is on at t (seconds), null when the guide says nothing. */
    fun now(t: Long): Entry? = guide?.let { g -> g.indexAt(t - channel.guideShiftSecs).let { if (it >= 0) shifted(g[it]) else null } }

    /** What comes after what is on at t. */
    fun next(t: Long): Entry? = guide?.let { g ->
        val i = g.firstEndingAfter(t - channel.guideShiftSecs)
        val n = if (i < g.size && g[i].start <= t - channel.guideShiftSecs) i + 1 else i
        if (n < g.size) shifted(g[n]) else null
    }

    /** The programmes of the guide crossing [from, until), in the time of the clock. */
    fun between(from: Long, until: Long): List<Entry> {
        val g = guide ?: return emptyList()
        val shift = channel.guideShiftSecs
        val out = ArrayList<Entry>()
        var i = g.firstEndingAfter(from - shift)
        while (i < g.size && g[i].start < until - shift) {
            out.add(shifted(g[i]))
            ++i
        }
        return out
    }

    private fun shifted(e: Entry): Entry {
        val shift = channel.guideShiftSecs
        if (shift == 0) return e
        return Entry(e.start + shift, e.stop + shift, e.title, e.subtitle, e.category, e.episode, e.descOffset, e.descLength)
    }
}
