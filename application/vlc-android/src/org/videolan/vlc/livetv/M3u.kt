/*
 * VLC Redesign, Live TV: the channels of an IPTV provider read from its M3U playlist (#EXTINF lines with tvg-*
 * attributes), the addresses of the channels' archives (catch-up) and the marks of adult content. A port of the
 * desktop's modules/misc/iptv/playlist.cpp.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import android.net.Uri
import java.io.BufferedReader
import java.io.Reader
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object M3u {

    private class ExtInf {
        val attrs = HashMap<String, String>()
        var title = ""
    }

    private fun parseExtInf(line: String): ExtInf {
        val out = ExtInf()
        // skip "#EXTINF:" and the duration
        var i = line.indexOf(':')
        if (i < 0) return out
        ++i
        val n = line.length
        while (i < n && (line[i].isDigit() || line[i] == '-' || line[i] == '.')) ++i
        while (i < n) {
            while (i < n && line[i].isWhitespace()) ++i
            if (i >= n) break
            if (line[i] == ',') {
                out.title = line.substring(i + 1).trim()
                break
            }
            // a key: up to '=' (or a bare word)
            val keyStart = i
            while (i < n && line[i] != '=' && line[i] != ',' && !line[i].isWhitespace()) ++i
            val key = line.substring(keyStart, i).lowercase(Locale.ROOT)
            if (i < n && line[i] == '=') {
                ++i
                val value: String
                if (i < n && (line[i] == '"' || line[i] == '\'')) {
                    val quote = line[i++]
                    val valueStart = i
                    while (i < n && line[i] != quote) ++i
                    value = line.substring(valueStart, i)
                    if (i < n) ++i
                } else {
                    val valueStart = i
                    while (i < n && line[i] != ',' && !line[i].isWhitespace()) ++i
                    value = line.substring(valueStart, i)
                }
                if (key.isNotEmpty()) out.attrs[key] = value.trim()
            }
        }
        return out
    }

    private fun resolve(address: String, base: String): String {
        if (base.isEmpty() || address.isEmpty()) return address
        return try {
            val uri = URI(address.replace(" ", "%20"))
            if (uri.isAbsolute) address else URI(base).resolve(uri).toString()
        } catch (e: Exception) {
            address
        }
    }

    private fun modeOf(type: String) = when (type.lowercase(Locale.ROOT)) {
        "default", "append", "shift" -> CatchupMode.APPEND
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> CatchupMode.FLUSSONIC
        "xc" -> CatchupMode.XTREAM
        else -> CatchupMode.NONE
    }

    private fun pathOf(url: String) = try { URI(url.replace(" ", "%20")).rawPath ?: "" } catch (e: Exception) { "" }

    /** A Flussonic stream: its last path element is "<name>.m3u8" (mono, index, video, tracks-v1a1...) or "mpegts". */
    private fun looksFlussonic(url: String): Boolean {
        val last = pathOf(url).substringAfterLast('/')
        if (last == "mpegts") return true
        if (!last.endsWith(".m3u8")) return false
        val name = last.dropLast(5)
        return name == "mono" || name == "index" || name == "video" || (name.startsWith("tracks-") && name.length > 7)
    }

    private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun fillTemplate(template: String, start: Long, duration: Int, now: Long): String {
        var tpl = template
        val end = start + duration
        // {duration:60} -> minutes, {offset:1} -> seconds ago
        for (name in arrayOf("{duration:", "{offset:")) {
            while (true) {
                val at = tpl.indexOf(name)
                if (at < 0) break
                val close = tpl.indexOf('}', at)
                if (close < 0) break
                val divisor = tpl.substring(at + name.length, close).toLongOrNull() ?: break
                val value = if (name == "{duration:") duration.toLong() else now - start
                tpl = tpl.replaceRange(at, close + 1, (value / maxOf(1L, divisor)).toString())
            }
        }
        val date = Date(start * 1000)
        val keys = listOf(
            "{utc}" to start, "\${start}" to start, "{start}" to start, "{timestamp}" to start,
            "{utcend}" to end, "\${end}" to end, "{end}" to end,
            "{lutc}" to now, "{now}" to now, "\${timestamp}" to now,
            "{duration}" to duration.toLong(), "\${duration}" to duration.toLong(),
            "{offset}" to now - start, "\${offset}" to now - start,
        )
        for ((k, v) in keys) tpl = tpl.replace(k, v.toString())
        for ((k, p) in listOf("{Y}" to "yyyy", "{m}" to "MM", "{d}" to "dd", "{H}" to "HH", "{M}" to "mm", "{S}" to "ss"))
            if (tpl.contains(k)) tpl = tpl.replace(k, utcFormat(p).format(date))
        return tpl
    }

    /** Whether a playlist looks like a channel list, not an HLS media playlist (#EXT-X- segments). */
    fun isChannelList(head: String): Boolean {
        if (head.contains("#EXT-X-TARGETDURATION") || head.contains("#EXT-X-STREAM-INF") || head.contains("#EXT-X-MEDIA-SEQUENCE"))
            return false
        return head.contains("#EXTINF")
    }

    /** Reads an M3U / M3U8 playlist of channels, a line at a time. base resolves relative addresses. */
    fun parse(input: Reader, base: String = ""): Playlist {
        val out = Playlist()
        val reader = input as? BufferedReader ?: BufferedReader(input, 64 * 1024)
        // the attributes of the header that stand for every channel
        val defaults = HashMap<String, String>()
        var pending = Channel()
        var havePending = false
        var pendingHasTitle = false
        var pendingGroup = ""
        var first = true
        var sawHeader = false
        while (true) {
            var line = reader.readLine() ?: break
            if (first) {
                if (line.startsWith("﻿")) line = line.substring(1)
                first = false
            }
            line = line.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTM3U", true)) {
                sawHeader = true
                val header = parseExtInf("#EXTM3U:0 " + line.substring(7))
                for (key in arrayOf("url-tvg", "x-tvg-url", "tvg-url"))
                    header.attrs[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach {
                        if (!out.guideUrls.contains(it)) out.guideUrls.add(it)
                    }
                for (key in arrayOf("catchup", "catchup-type", "catchup-source", "catchup-days", "tvg-shift"))
                    header.attrs[key]?.let { defaults[key] = it }
                continue
            }
            if (line.startsWith("#EXTINF", true)) {
                sawHeader = true
                val inf = parseExtInf(line)
                for ((k, v) in defaults) if (!inf.attrs.containsKey(k)) inf.attrs[k] = v
                val a = inf.attrs
                pending = Channel()
                havePending = true
                pending.name = inf.title.ifEmpty { a["tvg-name"] ?: "" }
                pending.tvgId = a["tvg-id"] ?: ""
                pending.tvgName = a["tvg-name"] ?: ""
                pending.logo = resolve(a["tvg-logo"] ?: a["logo"] ?: "", base)
                pending.group = a["group-title"] ?: ""
                pending.number = (a["tvg-chno"] ?: a["channel-number"])?.trim()?.toIntOrNull() ?: 0
                pending.guideShiftSecs = ((a["tvg-shift"]?.toDoubleOrNull() ?: 0.0) * 3600).toInt()
                pendingHasTitle = pending.group.isNotEmpty()
                if (!pendingHasTitle) pending.group = pendingGroup
                pending.radio = a["radio"].equals("true", true)
                pending.catchup = modeOf(a["catchup"] ?: a["catchup-type"] ?: "")
                pending.catchupSource = a["catchup-source"] ?: ""
                if (pending.catchupSource.isNotEmpty() && pending.catchup != CatchupMode.XTREAM)
                    pending.catchup = if ((a["catchup"] ?: "").equals("append", true)) CatchupMode.APPEND else CatchupMode.TEMPLATE
                pending.catchupDays = (a["catchup-days"] ?: a["tvg-rec"] ?: a["timeshift"])?.trim()?.toIntOrNull() ?: 0
                // checked against the address below
                if (pending.catchupDays > 0 && pending.catchup == CatchupMode.NONE) pending.catchup = CatchupMode.FLUSSONIC
                a["user-agent"]?.takeIf { it.isNotEmpty() }?.let { pending.options.add(":http-user-agent=$it") }
                a["http-referrer"]?.takeIf { it.isNotEmpty() }?.let { pending.options.add(":http-referrer=$it") }
                continue
            }
            if (line.startsWith("#EXTGRP:", true)) {
                pendingGroup = line.substring(8).trim()
                // after its #EXTINF it names the entry's group (before it, the group of the entries that follow)
                if (havePending && !pendingHasTitle) pending.group = pendingGroup
                continue
            }
            if (line.startsWith("#EXTVLCOPT:", true)) {
                if (havePending) pending.options.add(":" + line.substring(11).trim())
                continue
            }
            if (line.startsWith("#")) continue
            // the address of the pending entry (or a bare address)
            val channel = if (havePending) pending else Channel()
            channel.url = resolve(line, base)
            if (channel.name.isEmpty()) channel.name = Uri.parse(channel.url).lastPathSegment ?: channel.url
            if (channel.catchup == CatchupMode.FLUSSONIC && !looksFlussonic(channel.url))
                channel.catchup = if (channel.catchupDays > 0) CatchupMode.APPEND else CatchupMode.NONE
            if (channel.catchup != CatchupMode.NONE && channel.catchupDays <= 0) channel.catchupDays = 7
            channel.adult = looksAdult(channel.group, channel.name)
            out.channels.add(channel)
            havePending = false
        }
        if (!sawHeader && out.channels.isEmpty()) out.error = "not an M3U playlist"
        else if (out.channels.isEmpty()) out.error = "no channels"
        return out
    }

    fun parse(text: String, base: String = "") = parse(text.reader(), base)

    /**
     * The address that plays the channel from start for durationSecs, or "" when the channel has no catch-up
     * that far back (times in seconds since the epoch).
     */
    fun catchupUrl(channel: Channel, start: Long, durationSecs: Int, now: Long): String {
        if (channel.catchup == CatchupMode.NONE || durationSecs <= 0) return ""
        if (now - start > channel.catchupDays.toLong() * 86400 || start >= now) return ""
        return when (channel.catchup) {
            CatchupMode.TEMPLATE -> fillTemplate(channel.catchupSource, start, durationSecs, now)
            CatchupMode.APPEND -> {
                if (channel.catchupSource.isNotEmpty()) channel.url + fillTemplate(channel.catchupSource, start, durationSecs, now)
                else {
                    val sep = if (channel.url.contains('?')) "&" else "?"
                    channel.url + sep + "utc=" + start + "&lutc=" + now
                }
            }
            CatchupMode.FLUSSONIC -> {
                val uri = try { URI(channel.url.replace(" ", "%20")) } catch (e: Exception) { return "" }
                val path = uri.rawPath ?: return ""
                val slash = path.lastIndexOf('/')
                val last = path.substring(slash + 1)
                val newPath = when {
                    last == "mpegts" -> path.substring(0, slash + 1) + "timeshift_abs-$start.ts"
                    last.endsWith(".m3u8") -> {
                        var name = last.dropLast(5)
                        // index.m3u8 of a multi-bitrate stream: its archive is "archive"
                        if (name == "index") name = "archive"
                        path.substring(0, slash + 1) + "$name-$start-$durationSecs.m3u8"
                    }
                    else -> return ""
                }
                rebuild(uri, newPath, uri.rawQuery)
            }
            CatchupMode.XTREAM -> {
                // .../live/<user>/<pass>/<id>.<ext> -> .../timeshift/<user>/<pass>/<minutes>/<Y-m-d:H-M>/<id>.ts
                val uri = try { URI(channel.url.replace(" ", "%20")) } catch (e: Exception) { return "" }
                val parts = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
                if (parts.size < 3) return ""
                val id = parts.last().substringBefore('.')
                val user = parts[parts.size - 3]
                val pass = parts[parts.size - 2]
                // in the provider's local time: Xtream panels take the server's zone
                val local = utcFormat("yyyy-MM-dd:HH-mm").format(Date((start + channel.timeOffsetSecs) * 1000))
                rebuild(uri, "/timeshift/$user/$pass/${(durationSecs + 59) / 60}/$local/$id.ts", null)
            }
            CatchupMode.NONE -> ""
        }
    }

    private fun rebuild(uri: URI, rawPath: String, rawQuery: String?): String {
        val sb = StringBuilder()
        sb.append(uri.scheme).append("://").append(uri.rawAuthority ?: "")
        sb.append(rawPath)
        if (!rawQuery.isNullOrEmpty()) sb.append('?').append(rawQuery)
        return sb.toString()
    }

    /** "1. Israel" -> "Israel": the numbers some providers put first so their groups sort in their order. */
    fun displayGroup(group: String): String {
        val g = group.trim()
        var i = 0
        while (i < g.length && i < 4 && g[i].isDigit()) ++i
        if (i == 0 || i > 3) return g
        var j = i
        while (j < g.length && g[j].isWhitespace()) ++j
        if (j < g.length && (g[j] == '.' || g[j] == ')' || g[j] == '-' || g[j] == ':')) return g.substring(j + 1).trim()
        return g
    }

    private fun adultText(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        val words = ArrayList<String>()
        val word = StringBuilder()
        for (c in t) {
            if (c.isLetterOrDigit()) word.append(c)
            else if (word.isNotEmpty()) { words.add(word.toString()); word.setLength(0) }
        }
        if (word.isNotEmpty()) words.add(word.toString())
        for ((i, w) in words.withIndex()) {
            if (w == "xxx" || w == "adult" || w == "adults" || w.startsWith("porn") || w.startsWith("erotic")
                || w == "eroluxe" || w.startsWith("erox") || w == "hustler" || w == "playboy" || w == "brazzers"
                || w == "privatetv" || w == "redlight" || w == "dorcel" || w == "vivid"
                || (w == "private" && i + 1 < words.size && words[i + 1] == "tv"))
                return true
        }
        // "18+" or "+18" alone (not "118+", not "18+1")
        for (mark in arrayOf("18+", "+18")) {
            var at = 0
            while (true) {
                at = t.indexOf(mark, at)
                if (at < 0) break
                val before = at == 0 || !(t[at - 1].isLetterOrDigit() || t[at - 1] == '+')
                val after = at + mark.length
                val afterOk = after >= t.length || !(t[after].isLetterOrDigit() || t[after] == '+')
                if (before && afterOk) return true
                at = after
            }
        }
        return false
    }

    /** Adult content by its group or name ("XXX", "18+", "Adult"...). */
    fun looksAdult(group: String, name: String) = adultText(group) || adultText(name)

    internal fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
