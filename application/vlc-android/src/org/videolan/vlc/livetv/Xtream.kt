/*
 * VLC Redesign, Live TV: Xtream Codes, the account API most IPTV providers offer next to (or instead of) an M3U
 * link: player_api.php with the user name and the password, which lists the live channels, the films (VOD) and
 * the series by category. Pure functions: the addresses to ask, and the answers read. A port of the desktop's
 * modules/misc/iptv/xtream.cpp.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

object Xtream {

    class Account(val server: String, val username: String, val password: String) {
        val valid get() = server.isNotEmpty() && username.isNotEmpty()
    }

    class AccountInfo {
        var authenticated = false
        var status = ""
        var expires = 0L           // seconds since the epoch, 0: never
        var maxConnections = 0
        var activeConnections = 0
        var serverOffsetSecs = 0
        val outputFormats = ArrayList<String>()
        var error = ""
    }

    class Category(val id: String, val name: String)

    class VodItem {
        var id = ""          // stream_id of a film, series_id of a series
        var name = ""
        var poster = ""
        var category = ""
        var extension = ""
        var url = ""         // a film's address (empty for a series)
        var rating = 0.0
        var year = 0
        var added = 0L
        var adult = false
    }

    class Episode {
        var id = ""
        var title = ""
        var plot = ""
        var image = ""
        var url = ""
        var season = 0
        var number = 0
        var durationSecs = 0
    }

    class SeriesInfo {
        var plot = ""
        var cover = ""
        val episodes = ArrayList<Episode>()
    }

    // Xtream panels send numbers as numbers or as strings, and null for none
    private fun str(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is String -> v.trim()
        is Int, is Long -> v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Number -> v.toString()
        is Boolean -> if (v) "1" else "0"
        else -> ""
    }

    private fun num(v: Any?) = str(v).toDoubleOrNull()?.toLong() ?: 0L
    private fun real(v: Any?) = str(v).toDoubleOrNull() ?: 0.0

    private fun array(json: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val v = try { JSONTokener(json).nextValue() } catch (e: Exception) { return out }
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) v.optJSONObject(i)?.let { out.add(it) }
            // some panels answer an object keyed by index
            is JSONObject -> v.keys().forEach { k -> v.optJSONObject(k)?.let { out.add(it) } }
        }
        return out
    }

    private fun categoryName(categories: List<Category>, id: String) = categories.firstOrNull { it.id == id }?.name ?: ""

    private fun streamUrl(a: Account, kind: String, id: String, ext: String) =
        "${normalizeServer(a.server)}/$kind/${M3u.encode(a.username)}/${M3u.encode(a.password)}/$id.$ext"

    /** The server address, normalized: a scheme added, a trailing "/player_api.php", "/get.php..." or "/" removed. */
    fun normalizeServer(server: String): String {
        var s = server.trim()
        if (!s.contains("://")) s = "http://$s"
        val uri = Uri.parse(s)
        var path = uri.path ?: ""
        for (tail in arrayOf("/player_api.php", "/get.php", "/panel_api.php", "/xmltv.php"))
            if (path.endsWith(tail)) path = path.dropLast(tail.length)
        path = path.trimEnd('/')
        return "${uri.scheme}://${uri.encodedAuthority ?: ""}$path"
    }

    /** An Xtream account read from an M3U link of the provider (".../get.php?username=..&password=.."). */
    fun accountFromM3uUrl(address: String): Account? {
        val uri = Uri.parse(address)
        if (uri.path?.endsWith("/get.php") != true) return null
        val user = uri.getQueryParameter("username") ?: return null
        val pass = uri.getQueryParameter("password") ?: return null
        if (user.isEmpty() || pass.isEmpty()) return null
        return Account(normalizeServer(address), user, pass)
    }

    fun apiUrl(account: Account, action: String = "", params: List<Pair<String, String>> = emptyList()): String {
        val sb = StringBuilder(normalizeServer(account.server)).append("/player_api.php")
        sb.append("?username=").append(M3u.encode(account.username)).append("&password=").append(M3u.encode(account.password))
        if (action.isNotEmpty()) sb.append("&action=").append(action)
        for ((k, v) in params) sb.append('&').append(k).append('=').append(M3u.encode(v))
        return sb.toString()
    }

    /** The guide of the account (XMLTV). */
    fun guideUrl(account: Account) =
        "${normalizeServer(account.server)}/xmltv.php?username=${M3u.encode(account.username)}&password=${M3u.encode(account.password)}"

    fun parseAccountInfo(json: String): AccountInfo {
        val info = AccountInfo()
        val root = try { JSONObject(json) } catch (e: Exception) {
            info.error = "not an Xtream Codes server"
            return info
        }
        val user = root.optJSONObject("user_info") ?: JSONObject()
        val server = root.optJSONObject("server_info") ?: JSONObject()
        info.authenticated = num(user.opt("auth")) == 1L
        info.status = str(user.opt("status"))
        info.expires = num(user.opt("exp_date"))
        info.maxConnections = num(user.opt("max_connections")).toInt()
        info.activeConnections = num(user.opt("active_cons")).toInt()
        user.optJSONArray("allowed_output_formats")?.let { a -> for (i in 0 until a.length()) info.outputFormats.add(a.optString(i)) }
        // the server's clock: its local time next to the UTC timestamp
        val nowUtc = num(server.opt("timestamp_now"))
        val local = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(str(server.opt("time_now")))
        } catch (e: Exception) { null }
        if (nowUtc > 0 && local != null) {
            val diff = local.time / 1000 - nowUtc
            // to the quarter hour: the two clocks are read a moment apart
            info.serverOffsetSecs = ((diff / 900.0).roundToLong() * 900).toInt()
        }
        if (!info.authenticated) info.error = info.status.ifEmpty { "wrong user name or password" }
        return info
    }

    fun parseCategories(json: String) = array(json).mapNotNull { o ->
        val id = str(o.opt("category_id"))
        if (id.isEmpty()) null else Category(id, str(o.opt("category_name")))
    }

    /** The live channels, with the addresses to play them (HLS when the account allows it) and their catch-up. */
    fun parseLiveStreams(json: String, account: Account, categories: List<Category>, info: AccountInfo): List<Channel> {
        val ext = if (info.outputFormats.isEmpty() || info.outputFormats.contains("m3u8")) "m3u8" else "ts"
        val out = ArrayList<Channel>()
        for (o in array(json)) {
            val id = str(o.opt("stream_id"))
            if (id.isEmpty()) continue
            val c = Channel()
            c.name = str(o.opt("name"))
            c.logo = str(o.opt("stream_icon"))
            c.tvgId = str(o.opt("epg_channel_id"))
            c.group = categoryName(categories, str(o.opt("category_id")))
            c.url = streamUrl(account, "live", id, ext)
            c.number = num(o.opt("num")).toInt()
            c.radio = str(o.opt("stream_type")) == "radio_streams"
            if (num(o.opt("tv_archive")) == 1L) {
                c.catchup = CatchupMode.XTREAM
                c.catchupDays = maxOf(1, num(o.opt("tv_archive_duration")).toInt())
                c.timeOffsetSecs = info.serverOffsetSecs
            }
            c.adult = num(o.opt("is_adult")) == 1L || M3u.looksAdult(c.group, c.name)
            out.add(c)
        }
        return out
    }

    fun parseVodStreams(json: String, account: Account, categories: List<Category>) = array(json).mapNotNull { o ->
        val item = VodItem()
        item.id = str(o.opt("stream_id"))
        if (item.id.isEmpty()) return@mapNotNull null
        item.name = str(o.opt("name"))
        item.poster = str(o.opt("stream_icon"))
        item.category = categoryName(categories, str(o.opt("category_id")))
        item.extension = str(o.opt("container_extension")).ifEmpty { "mp4" }
        item.url = streamUrl(account, "movie", item.id, item.extension)
        item.rating = real(o.opt("rating"))
        item.added = num(o.opt("added"))
        item.year = num(o.opt("year")).toInt()
        item.adult = num(o.opt("is_adult")) == 1L || M3u.looksAdult(item.category, item.name)
        item
    }

    fun parseSeries(json: String, categories: List<Category>) = array(json).mapNotNull { o ->
        val item = VodItem()
        item.id = str(o.opt("series_id"))
        if (item.id.isEmpty()) return@mapNotNull null
        item.name = str(o.opt("name"))
        item.poster = str(o.opt("cover"))
        item.category = categoryName(categories, str(o.opt("category_id")))
        item.rating = real(o.opt("rating"))
        item.added = num(o.opt("last_modified"))
        item.year = str(o.opt("releaseDate")).take(4).toIntOrNull() ?: 0
        item.adult = M3u.looksAdult(item.category, item.name)
        item
    }

    fun parseSeriesInfo(json: String, account: Account): SeriesInfo {
        val out = SeriesInfo()
        val root = try { JSONObject(json) } catch (e: Exception) { return out }
        val info = root.optJSONObject("info") ?: JSONObject()
        out.plot = str(info.opt("plot"))
        out.cover = str(info.opt("cover"))
        fun addSeason(list: JSONArray) {
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val ei = o.optJSONObject("info") ?: JSONObject()
                val e = Episode()
                e.id = str(o.opt("id"))
                if (e.id.isEmpty()) continue
                e.title = str(o.opt("title"))
                e.season = num(o.opt("season")).toInt()
                e.number = num(o.opt("episode_num")).toInt()
                e.plot = str(ei.opt("plot"))
                e.image = str(ei.opt("movie_image"))
                e.durationSecs = num(ei.opt("duration_secs")).toInt()
                e.url = streamUrl(account, "series", e.id, str(o.opt("container_extension")).ifEmpty { "mp4" })
                out.episodes.add(e)
            }
        }
        when (val episodes = root.opt("episodes")) {
            is JSONObject -> episodes.keys().forEach { k -> episodes.optJSONArray(k)?.let { addSeason(it) } }
            is JSONArray -> for (i in 0 until episodes.length()) {
                val season = episodes.opt(i)
                if (season is JSONArray) addSeason(season) else if (season is JSONObject) addSeason(JSONArray().put(season))
            }
        }
        out.episodes.sortWith(compareBy({ it.season }, { it.number }))
        return out
    }
}
