/*
 * VLC Redesign, Live TV accounts: the user's own TV servers and paid services, each declaring what the user gives it
 * (the same model as the subtitle services: fields, Sign in / Test, Sign out), read into the channels and guides of
 * LiveTv. The same types, fields and requests as the desktop's and the Samsung TV app's (app/js/tvaccounts.js):
 * Jellyfin (a password or Quick Connect), Emby (a password or an API key), Tvheadend (its M3U and XMLTV, with the
 * account: Basic or Digest, or its access code), NextPVR (its PIN), Channels DVR, HDHomeRun (its lineup and its free
 * guide), Schedules Direct (a paid guide for the sources' channels) and an M3U with a sign-in. Plex is left out: its
 * live TV is a session a channel, not a list to play.
 *
 * Plain Kotlin without Android's classes; the requests go through [http] (the unit tests answer them from fixtures).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What the user gives an account (the fields its type declares; the secrets kept encrypted by LiveTv). */
class TvAccount(
    var address: String = "",
    var username: String = "",
    var password: String = "",
    var pin: String = "",
    var key: String = "",
    var code: String = "",
    var url: String = "",
    var guide: String = "",
    var token: String = "",
    var userId: String = "",
    var tokenUntil: Long = 0,
)

enum class TvCheckState { SIGNED_IN, WORKS, WRONG, REFUSED, NO_ANSWER, BUSY, CODE }

/** What Sign in / Test found: its state, the user (or the server's name), a token to keep. */
class TvCheck(
    val state: TvCheckState,
    val user: String = "",
    val token: String = "",
    val userId: String = "",
    val tokenUntil: Long = 0,
    val code: String = "",
) {
    val works get() = state == TvCheckState.SIGNED_IN || state == TvCheckState.WORKS
}

/** The channels of an account, the guides of its own (XMLTV links), whether its guide is read by [TvAccounts.guide]. */
class TvLoaded(
    val channels: List<Channel>,
    val guides: List<String>,
    val jsonGuide: Boolean,
    val token: String = "",
    val userId: String = "",
    val tokenUntil: Long = 0,
    /** an M3U as the server gave it, kept as the source's playlist (read again offline) */
    val playlist: String = "",
)

/** An account that could not be read: refused (its sign-in), or no answer. */
class TvAccountException(message: String, val refused: Boolean = false, val busy: Boolean = false) : Exception(message)

/** A programme of a guide given as JSON, written as XMLTV for the guides' index. */
class TvProg(val channel: String, val start: Long, val stop: Long, val title: String, val subtitle: String = "",
             val desc: String = "", val episode: String = "")

object TvAccounts {

    /** A field: address, username, password, pin, key, code, url, guide. */
    class Field(val name: String, val optional: Boolean = false) {
        val secret get() = name == "password" || name == "pin" || name == "key" || name == "code"
    }

    class Type(val id: String, val name: String, val ports: List<Int>, val fields: List<Field>, val guideOnly: Boolean = false) {
        /** it signs in (a user name, a PIN or a code), rather than only being tested */
        val signsIn get() = fields.any { it.name == "username" || it.name == "pin" }
    }

    val TYPES = listOf(
        Type("jellyfin", "Jellyfin", listOf(8096, 8920), listOf(Field("address"), Field("username", true), Field("password", true))),
        Type("emby", "Emby", listOf(8096, 8920), listOf(Field("address"), Field("username", true), Field("password", true), Field("key", true))),
        Type("tvheadend", "Tvheadend", listOf(9981), listOf(Field("address"), Field("code", true), Field("username", true), Field("password", true))),
        Type("nextpvr", "NextPVR", listOf(8866), listOf(Field("address"), Field("pin", true))),
        Type("channelsdvr", "Channels DVR", listOf(8089), listOf(Field("address"))),
        Type("hdhomerun", "HDHomeRun", listOf(80), listOf(Field("address"))),
        Type("schedulesdirect", "Schedules Direct", emptyList(), listOf(Field("username"), Field("password")), guideOnly = true),
        Type("m3ulogin", "M3U", emptyList(), listOf(Field("url"), Field("guide", true), Field("username"), Field("password"))),
    )

    fun type(id: String) = TYPES.firstOrNull { it.id == id }

    /** The requests (replaced by the unit tests). */
    @Volatile var http: (TvRequest) -> TvAnswer = { TvHttp.request(it) }
    /** this device for the servers that ask (Jellyfin, Emby, NextPVR): set by LiveTv, kept */
    @Volatile var deviceId = "juxto-android"
    @Volatile var version = "0.5.8"
    @Volatile var deviceName = "Android"
    /** how often a Quick Connect code is asked about, and how many times */
    @Volatile var pollMs = 3000L
    var pollTries = 100

    private const val CLIENT = "Juxto Media Player"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun json(a: TvAnswer): JSONObject? = try { JSONObject(a.body) } catch (e: Exception) { null }
    private fun jsonArray(a: TvAnswer): JSONArray? = try { JSONArray(a.body) } catch (e: Exception) { null }

    /** a failed answer's state: no answer, refused (401, 403), busy (429) */
    fun stateOf(a: TvAnswer): TvCheckState = when {
        a.status == 0 -> TvCheckState.NO_ANSWER
        a.status == 401 || a.status == 403 -> TvCheckState.REFUSED
        a.status == 429 -> TvCheckState.BUSY
        else -> TvCheckState.NO_ANSWER
    }

    private fun fail(a: TvAnswer): Nothing {
        val s = stateOf(a)
        throw TvAccountException(if (a.status > 0) "HTTP ${a.status}" else "no answer", s == TvCheckState.REFUSED, s == TvCheckState.BUSY)
    }

    private fun failOf(c: TvCheck): Nothing = throw TvAccountException(c.state.name.lowercase(Locale.ROOT),
        c.state == TvCheckState.WRONG || c.state == TvCheckState.REFUSED, c.state == TvCheckState.BUSY)

    /* ---- the address of a server: a scheme and the type's port when none was typed ---- */

    private val addressRe = Regex("""^([a-z]+://)(\[[^\]]+\]|[^/:?#]+)(:\d+)?(.*)$""", RegexOption.IGNORE_CASE)

    fun address(text: String, typeId: String): String {
        var t = text.trim().trimEnd('/')
        if (t.isEmpty()) return ""
        val ports = type(typeId)?.ports ?: emptyList()
        if (!Regex("""^[a-z][a-z0-9+.\-]*://""", RegexOption.IGNORE_CASE).containsMatchIn(t))
            t = (if (ports.firstOrNull() == 443 || ports.firstOrNull() == 8920) "https://" else "http://") + t
        val m = addressRe.find(t) ?: return t
        var port = m.groupValues[3]
        val first = ports.firstOrNull()
        if (port.isEmpty() && first != null && first != 80 && first != 443) port = ":$first"
        return m.groupValues[1] + m.groupValues[2] + port + m.groupValues[4]
    }

    /* ---- Channels DVR and Tvheadend: their M3U and XMLTV ---- */

    class Links(val playlist: String, val guide: String, val usesAccount: Boolean)

    fun channelsDvr(a: TvAccount) = Links(a.address + "/devices/ANY/channels.m3u", a.address + "/devices/ANY/guide/xmltv", false)

    /** with the user's persistent access code (Users > Passwords) the addresses carry it; else the account's
     *  sign-in (Basic or Digest) for the lists, and in the channels' addresses for the player; raw streams */
    fun tvheadend(a: TvAccount): Links {
        if (a.code.isNotEmpty()) {
            val auth = "auth=" + enc(a.code)
            return Links(a.address + "/playlist/auth/channels?" + auth + "&profile=pass", a.address + "/xmltv/channels?" + auth, false)
        }
        return Links(a.address + "/playlist/channels?profile=pass", a.address + "/xmltv/channels", a.username.isNotEmpty())
    }

    /** The account's user name and password in an address (for the player, which answers Basic and Digest). */
    fun withCredentials(url: String, user: String, password: String): String {
        if (user.isEmpty()) return url
        val m = Regex("""^([a-z]+://)([^/@]*@)?(.*)$""", RegexOption.IGNORE_CASE).find(url) ?: return url
        return m.groupValues[1] + enc(user) + ":" + enc(password) + "@" + m.groupValues[3]
    }

    /* ---- HDHomeRun: its lineup, its free guide by the device's DeviceAuth ---- */

    fun hdhrChannels(body: String): List<Channel> {
        val a = try { JSONArray(body) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Channel>()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            val url = c.optString("URL")
            if (url.isEmpty() || c.optInt("DRM") == 1 || c.optBoolean("DRM")) continue
            val number = c.optString("GuideNumber").trim()
            out.add(Channel().also {
                it.name = c.optString("GuideName").trim().ifEmpty { number }
                it.url = url
                it.tvgId = "hdhr:$number"
                it.group = if (c.optInt("HD") == 1 || c.optBoolean("HD")) "HD" else ""
                it.number = number.substringBefore('.').toIntOrNull() ?: 0
            })
        }
        return out
    }

    /** [{GuideNumber, Guide: [{StartTime, EndTime (seconds), Title, EpisodeTitle, Synopsis, EpisodeNumber}]}] */
    fun hdhrGuide(body: String): List<TvProg> {
        val a = try { JSONArray(body) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<TvProg>()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            val progs = c.optJSONArray("Guide") ?: continue
            val number = c.optString("GuideNumber").trim()
            for (j in 0 until progs.length()) {
                val p = progs.optJSONObject(j) ?: continue
                out.add(TvProg("hdhr:$number", p.optLong("StartTime"), p.optLong("EndTime"), p.optString("Title"), p.optString("EpisodeTitle"),
                    p.optString("Synopsis"), p.optString("EpisodeNumber")))
            }
        }
        return out
    }

    /* ---- Jellyfin: "Authorization: MediaBrowser ..." (the only form 12.x takes; 10.8 too), its Live TV ---- */

    fun jfHeaders(token: String = ""): Map<String, String> {
        val v = "MediaBrowser Client=\"" + enc(CLIENT) + "\", Device=\"" + enc(deviceName) + "\", DeviceId=\"" + enc(deviceId) +
            "\", Version=\"" + enc(version) + "\"" + (if (token.isNotEmpty()) ", Token=\"" + enc(token) + "\"" else "")
        return mapOf("Authorization" to v, "Accept" to "application/json", "Content-Type" to "application/json")
    }

    /** an AuthenticateByName (or Quick Connect) answer: signed in, its token and user */
    fun signedIn(body: String, fallbackUser: String): TvCheck? {
        val o = try { JSONObject(body) } catch (e: Exception) { return null }
        val token = o.optString("AccessToken").ifEmpty { return null }
        val user = o.optJSONObject("User")
        return TvCheck(TvCheckState.SIGNED_IN, user?.optString("Name")?.trim()?.ifEmpty { null } ?: fallbackUser, token, user?.optString("Id") ?: "")
    }

    private fun jfCheck(a: TvAccount, progress: ((TvCheck) -> Unit)?): TvCheck {
        if (a.username.isNotEmpty()) {
            val r = http(TvRequest(a.address + "/Users/AuthenticateByName", "POST", jfHeaders(),
                JSONObject().put("Username", a.username).put("Pw", a.password).toString()))
            return (if (r.ok) signedIn(r.body, a.username) else null) ?: TvCheck(if (r.status == 401) TvCheckState.WRONG else stateOf(r))
        }
        // Quick Connect: a code to type in a Jellyfin app signed in to the same server
        val r = http(TvRequest(a.address + "/QuickConnect/Initiate", "POST", jfHeaders(), ""))
        val o = if (r.ok) json(r) else null
        val secret = o?.optString("Secret") ?: ""
        if (secret.isEmpty()) return TvCheck(if (stateOf(r) == TvCheckState.REFUSED) TvCheckState.REFUSED else TvCheckState.NO_ANSWER)
        progress?.invoke(TvCheck(TvCheckState.CODE, code = o?.optString("Code") ?: ""))
        repeat(pollTries) {
            val s = http(TvRequest(a.address + "/QuickConnect/Connect?secret=" + enc(secret), headers = jfHeaders()))
            if (s.status == 404) return TvCheck(TvCheckState.NO_ANSWER)
            if (s.ok && json(s)?.optBoolean("Authenticated") == true) {
                val t = http(TvRequest(a.address + "/Users/AuthenticateWithQuickConnect", "POST", jfHeaders(), JSONObject().put("Secret", secret).toString()))
                return (if (t.ok) signedIn(t.body, "") else null) ?: TvCheck(stateOf(t))
            }
            Thread.sleep(pollMs)
        }
        return TvCheck(TvCheckState.NO_ANSWER)
    }

    /** the token kept, else a new sign-in with the account */
    private fun withToken(a: TvAccount, check: (TvAccount) -> TvCheck): Pair<TvAccount, Boolean> {
        if (a.token.isNotEmpty()) return a to true
        if (a.username.isEmpty()) throw TvAccountException("sign in first", refused = true)
        val c = check(a)
        if (c.state != TvCheckState.SIGNED_IN) failOf(c)
        a.token = c.token
        a.userId = c.userId
        return a to false
    }

    /** the channels of /LiveTv/Channels (Jellyfin, Emby): their id prefixed as the guide knows them */
    fun mbChannels(body: String, prefix: String, streamOf: (String) -> String, logoOf: (String, String) -> String): List<Channel> {
        val items = try { JSONObject(body).optJSONArray("Items") } catch (e: Exception) { null } ?: return emptyList()
        val out = ArrayList<Channel>()
        for (i in 0 until items.length()) {
            val c = items.optJSONObject(i) ?: continue
            val id = c.optString("Id").ifEmpty { continue }
            val tag = c.optJSONObject("ImageTags")?.optString("Primary") ?: ""
            out.add(Channel().also {
                it.name = c.optString("Name").trim().ifEmpty { id }
                it.url = streamOf(id)
                it.tvgId = prefix + id
                it.logo = if (tag.isNotEmpty()) logoOf(id, tag) else ""
                it.number = (c.optString("ChannelNumber").ifEmpty { c.optString("Number") }).substringBefore('.').toIntOrNull() ?: 0
            })
        }
        return out
    }

    private val isoFormat = ThreadLocal<SimpleDateFormat>()
    private fun isoParser(): SimpleDateFormat = isoFormat.get() ?: SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC"); isoFormat.set(this)
    }

    /** "2026-11-08T20:00:00.0000000Z" -> seconds (0 when it isn't one) */
    fun isoSeconds(text: String): Long = try {
        val t = text.trim()
        val base = isoParser().parse(t.take(19))?.time ?: 0L
        // an offset after the time ("+02:00"); Z or none: UTC
        val m = Regex("""([+\-])(\d\d):?(\d\d)$""").find(t.drop(19))
        val shift = if (m != null) (m.groupValues[2].toInt() * 3600 + m.groupValues[3].toInt() * 60) * (if (m.groupValues[1] == "+") 1 else -1) else 0
        base / 1000 - shift
    } catch (e: Exception) { 0L }

    fun iso(seconds: Long): String = isoParser().format(Date(seconds * 1000)) + "Z"

    /** the programmes of /LiveTv/Programs */
    fun mbPrograms(body: String, prefix: String): List<TvProg> {
        val items = try { JSONObject(body).optJSONArray("Items") } catch (e: Exception) { null } ?: return emptyList()
        val out = ArrayList<TvProg>()
        for (i in 0 until items.length()) {
            val p = items.optJSONObject(i) ?: continue
            val idx = p.optInt("IndexNumber")
            out.add(TvProg(prefix + p.optString("ChannelId"), isoSeconds(p.optString("StartDate")), isoSeconds(p.optString("EndDate")),
                p.optString("Name"), p.optString("EpisodeTitle"), p.optString("Overview"), if (idx > 0) "E$idx" else ""))
        }
        return out
    }

    private fun jfLoad(a: TvAccount, again: Boolean = false): TvLoaded {
        val (acc, kept) = withToken(a) { jfCheck(it, null) }
        val r = http(TvRequest(acc.address + "/LiveTv/Channels?" + (if (acc.userId.isNotEmpty()) "userId=" + enc(acc.userId) + "&" else "") +
            "enableImages=true&limit=5000", headers = jfHeaders(acc.token)))
        // (a kept token that ended: signed in again, once)
        if (stateOf(r) == TvCheckState.REFUSED && r.status > 0 && kept && acc.username.isNotEmpty() && !again) { acc.token = ""; return jfLoad(acc, true) }
        if (!r.ok) fail(r)
        val token = acc.token
        val channels = mbChannels(r.body, "jf:", { acc.address + "/Videos/" + it + "/stream?static=true&ApiKey=" + enc(token) },
            { id, tag -> acc.address + "/Items/" + id + "/Images/Primary?tag=" + enc(tag) + "&maxHeight=120" })
        if (channels.isEmpty()) throw TvAccountException("no channels")
        return TvLoaded(channels, emptyList(), true, token, acc.userId)
    }

    private fun jfGuide(a: TvAccount, channels: List<Channel>, from: Long, until: Long): List<TvProg> {
        val (acc, _) = withToken(a) { jfCheck(it, null) }
        val ids = channels.mapNotNull { c -> c.tvgId.removePrefix("jf:").takeIf { it.isNotEmpty() && c.tvgId.startsWith("jf:") } }
        val body = JSONObject().put("ChannelIds", JSONArray(ids)).put("MinEndDate", iso(from)).put("MaxStartDate", iso(until))
            .put("Fields", JSONArray(listOf("Overview"))).put("SortBy", JSONArray(listOf("StartDate"))).put("EnableTotalRecordCount", false)
        if (acc.userId.isNotEmpty()) body.put("UserId", acc.userId)
        val r = http(TvRequest(acc.address + "/LiveTv/Programs", "POST", jfHeaders(acc.token), body.toString(), timeoutMs = 120_000))
        if (!r.ok) fail(r)
        return mbPrograms(r.body, "jf:")
    }

    /* ---- Emby: /emby/..., "X-Emby-Authorization: Emby ...", its token (or an API key) ---- */

    fun embyHeaders(token: String = ""): Map<String, String> {
        val h = linkedMapOf("X-Emby-Authorization" to "Emby UserId=\"\", Client=\"$CLIENT\", Device=\"$deviceName\", DeviceId=\"$deviceId\", Version=\"$version\"",
            "Accept" to "application/json", "Content-Type" to "application/json")
        if (token.isNotEmpty()) h["X-Emby-Token"] = token
        return h
    }

    private fun embyCheck(a: TvAccount): TvCheck {
        if (a.key.isNotEmpty() && a.username.isEmpty()) {
            val r = http(TvRequest(a.address + "/emby/System/Info?api_key=" + enc(a.key), headers = embyHeaders(a.key)))
            val o = if (r.ok) json(r) else null
            return if (o != null) TvCheck(TvCheckState.WORKS, o.optString("ServerName").trim(), a.key) else TvCheck(stateOf(r))
        }
        val r = http(TvRequest(a.address + "/emby/Users/AuthenticateByName", "POST", embyHeaders(),
            JSONObject().put("Username", a.username).put("Pw", a.password).toString()))
        return (if (r.ok) signedIn(r.body, a.username) else null) ?: TvCheck(if (r.status == 401) TvCheckState.WRONG else stateOf(r))
    }

    private fun embyLoad(a: TvAccount, again: Boolean = false): TvLoaded {
        if (a.key.isNotEmpty() && a.username.isEmpty()) a.token = a.key
        val (acc, kept) = withToken(a, ::embyCheck)
        val r = http(TvRequest(acc.address + "/emby/LiveTv/Channels?" + (if (acc.userId.isNotEmpty()) "UserId=" + enc(acc.userId) + "&" else "") +
            "EnableImages=true&Limit=5000", headers = embyHeaders(acc.token)))
        if (stateOf(r) == TvCheckState.REFUSED && r.status > 0 && kept && acc.username.isNotEmpty() && !again) { acc.token = ""; return embyLoad(acc, true) }
        if (!r.ok) fail(r)
        val token = acc.token
        val channels = mbChannels(r.body, "emby:", { acc.address + "/emby/videos/" + it + "/stream?static=true&api_key=" + enc(token) },
            { id, tag -> acc.address + "/emby/Items/" + id + "/Images/Primary?tag=" + enc(tag) + "&maxHeight=120" })
        if (channels.isEmpty()) throw TvAccountException("no channels")
        return TvLoaded(channels, emptyList(), true, token, acc.userId)
    }

    private fun embyGuide(a: TvAccount, channels: List<Channel>, from: Long, until: Long): List<TvProg> {
        if (a.key.isNotEmpty() && a.username.isEmpty()) a.token = a.key
        val (acc, _) = withToken(a, ::embyCheck)
        val ids = channels.mapNotNull { c -> c.tvgId.removePrefix("emby:").takeIf { it.isNotEmpty() && c.tvgId.startsWith("emby:") } }
        val out = ArrayList<TvProg>()
        // (the channels fifty at a time: an address of every id would be too long)
        for (part in ids.chunked(50)) {
            val r = http(TvRequest(acc.address + "/emby/LiveTv/Programs?ChannelIds=" + part.joinToString(",") + "&MinEndDate=" + enc(iso(from)) +
                "&MaxStartDate=" + enc(iso(until)) + "&Fields=Overview" + (if (acc.userId.isNotEmpty()) "&UserId=" + enc(acc.userId) else ""),
                headers = embyHeaders(acc.token), timeoutMs = 60_000))
            if (!r.ok) { if (out.isNotEmpty()) return out; fail(r) }
            out.addAll(mbPrograms(r.body, "emby:"))
        }
        return out
    }

    /* ---- NextPVR: a session (md5(":" + md5(PIN) + ":" + salt)), its channels and listings as JSON ---- */

    /** a value of its answers, JSON or XML ("sid", "salt") */
    fun npValue(body: String, name: String): String {
        try { JSONObject(body).let { if (it.has(name)) return it.optString(name) } } catch (e: Exception) { }
        return Regex("<$name>([^<]*)</$name>").find(body)?.groupValues?.get(1) ?: ""
    }

    fun npFailed(a: TvAnswer) = !a.ok || a.body.contains("stat=\"fail\"") || (json(a)?.optString("stat") == "fail")

    fun npLoginHash(pin: String, salt: String) = TvHttp.md5(":" + TvHttp.md5(pin.ifEmpty { "0000" }) + ":" + salt)

    private fun npSession(a: TvAccount): TvCheck {
        val headers = mapOf("Accept" to "application/json")
        val r = http(TvRequest(a.address + "/service?method=session.initiate&ver=1.0&device=" + enc("juxto-$deviceId"), headers = headers))
        val sid = if (r.ok) npValue(r.body, "sid") else ""
        val salt = if (r.ok) npValue(r.body, "salt") else ""
        if (sid.isEmpty()) return TvCheck(stateOf(r))
        val r2 = http(TvRequest(a.address + "/service?method=session.login&sid=" + enc(sid) + "&md5=" + npLoginHash(a.pin, salt), headers = headers))
        if (npFailed(r2)) return TvCheck(if (r2.ok || r2.status == 401) TvCheckState.WRONG else stateOf(r2))
        return TvCheck(TvCheckState.SIGNED_IN, "", sid)
    }

    fun npChannels(body: String, address: String, sid: String): List<Channel> {
        val list = try { JSONObject(body).optJSONArray("channels") } catch (e: Exception) { null } ?: return emptyList()
        val out = ArrayList<Channel>()
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            if (!c.has("channelId")) continue
            val id = c.optString("channelId")
            out.add(Channel().also {
                it.name = c.optString("channelName").trim().ifEmpty { id }
                it.url = address + "/live?channeloid=" + enc(id) + "&client=" + enc("juxto-$deviceId") + "&sid=" + enc(sid)
                it.tvgId = "npvr:$id"
                it.logo = if (c.optBoolean("channelIcon")) address + "/service?method=channel.icon&channel_id=" + enc(id) + "&sid=" + enc(sid) else ""
                it.number = (c.optString("channelNumberFormated").ifEmpty { c.optString("channelNumber") }).substringBefore('.').toIntOrNull() ?: 0
            })
        }
        return out
    }

    fun npListings(body: String, channelId: String): List<TvProg> {
        val list = try { JSONObject(body).optJSONArray("listings") } catch (e: Exception) { null } ?: return emptyList()
        val out = ArrayList<TvProg>()
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val season = p.optInt("season")
            val episode = p.optInt("episode")
            out.add(TvProg("npvr:$channelId", p.optLong("start"), p.optLong("end"), p.optString("name"), p.optString("subtitle"),
                p.optString("description"), if (season > 0 && episode > 0) "S${season}E$episode" else ""))
        }
        return out
    }

    private fun npLoad(a: TvAccount): TvLoaded {
        val s = npSession(a)
        if (s.state != TvCheckState.SIGNED_IN) failOf(s)
        val r = http(TvRequest(a.address + "/service?method=channel.list&sid=" + enc(s.token), headers = mapOf("Accept" to "application/json"), timeoutMs = 60_000))
        if (!r.ok) fail(r)
        val out = npChannels(r.body, a.address, s.token)
        if (out.isEmpty()) throw TvAccountException("no channels")
        return TvLoaded(out, emptyList(), true, s.token)
    }

    // its listings a channel at a time (the first 80 channels)
    private fun npGuide(a: TvAccount, channels: List<Channel>, from: Long, until: Long): List<TvProg> {
        val s = npSession(a)
        if (s.state != TvCheckState.SIGNED_IN) failOf(s)
        val out = ArrayList<TvProg>()
        for (id in channels.mapNotNull { c -> c.tvgId.removePrefix("npvr:").takeIf { c.tvgId.startsWith("npvr:") } }.take(80)) {
            val r = http(TvRequest(a.address + "/service?method=channel.listings&channel_id=" + enc(id) + "&start=$from&end=$until&sid=" + enc(s.token),
                headers = mapOf("Accept" to "application/json")))
            if (r.ok) out.addAll(npListings(r.body, id))
        }
        return out
    }

    /* ---- Schedules Direct: a paid guide for the sources' channels (its token; SHA-1 of the password) ---- */

    private const val SD = "https://json.schedulesdirect.org/20141201/"

    fun sdHeaders(token: String = ""): Map<String, String> {
        val h = linkedMapOf("Content-Type" to "application/json", "Accept" to "application/json", "User-Agent" to "JuxtoMediaPlayer/$version")
        if (token.isNotEmpty()) h["token"] = token
        return h
    }

    /** its token answer: {code: 0, token, tokenExpires}; 4003/4001 a wrong account, 4004 locked a while, 4009 too many sign-ins today */
    fun sdTokenOf(a: TvAnswer, user: String): TvCheck {
        val o = json(a) ?: return TvCheck(stateOf(a))
        val token = o.optString("token")
        if (token.isNotEmpty() && o.optInt("code", -1) == 0) return TvCheck(TvCheckState.SIGNED_IN, user, token, tokenUntil = o.optLong("tokenExpires"))
        return TvCheck(when (o.optInt("code", -1)) {
            4001, 4003 -> TvCheckState.WRONG
            4004, 4009 -> TvCheckState.BUSY
            else -> stateOf(a)
        })
    }

    private fun sdCheck(a: TvAccount): TvCheck = sdTokenOf(http(TvRequest(SD + "token", "POST", sdHeaders(),
        JSONObject().put("username", a.username).put("password", TvHttp.sha1(a.password)).put("newToken", false).toString())), a.username)

    /** the stations named as the channels (their name, callsign, affiliate + callsign): {stationID: [channels]} */
    fun sdMatch(channels: List<Channel>, stations: JSONArray): Map<String, List<Channel>> {
        val byName = HashMap<String, MutableList<Channel>>()
        for (c in channels) EpgMatch.normalize(c.name).compact.takeIf { it.isNotEmpty() }?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
        val out = LinkedHashMap<String, List<Channel>>()
        for (i in 0 until stations.length()) {
            val st = stations.optJSONObject(i) ?: continue
            val id = st.optString("stationID").ifEmpty { continue }
            for (n in listOf(st.optString("name"), st.optString("callsign"), st.optString("affiliate") + st.optString("callsign"))) {
                val k = EpgMatch.normalize(n).compact
                if (k.isNotEmpty() && byName.containsKey(k) && !out.containsKey(id)) out[id] = byName[k]!!
            }
        }
        return out
    }

    /** the airings of /schedules: (stationID, programID, start, duration in seconds) */
    class Airing(val station: String, val program: String, val start: Long, val duration: Long)

    fun sdAirings(body: String): List<Airing> {
        val a = try { JSONArray(body) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Airing>()
        for (i in 0 until a.length()) {
            val s = a.optJSONObject(i) ?: continue
            val progs = s.optJSONArray("programs") ?: continue
            for (j in 0 until progs.length()) {
                val p = progs.optJSONObject(j) ?: continue
                out.add(Airing(s.optString("stationID"), p.optString("programID"), isoSeconds(p.optString("airDateTime")), p.optLong("duration")))
            }
        }
        return out
    }

    /** the programmes of /programs: {programID: (title, episode title, description)} */
    fun sdPrograms(body: String): Map<String, Triple<String, String, String>> {
        val a = try { JSONArray(body) } catch (e: Exception) { return emptyMap() }
        val out = HashMap<String, Triple<String, String, String>>()
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            val id = p.optString("programID").ifEmpty { continue }
            val title = p.optJSONArray("titles")?.optJSONObject(0)?.optString("title120") ?: ""
            val d = p.optJSONObject("descriptions")
            val desc = (d?.optJSONArray("description1000") ?: d?.optJSONArray("description100"))?.optJSONObject(0)?.optString("description") ?: ""
            out[id] = Triple(title, p.optString("episodeTitle150"), desc)
        }
        return out
    }

    private fun sdGuide(a: TvAccount, channels: List<Channel>, from: Long, until: Long): List<TvProg> {
        // (its token kept until it ends: the service allows few sign-ins a day)
        var token = if (a.token.isNotEmpty() && a.tokenUntil > System.currentTimeMillis() / 1000 + 600) a.token else ""
        if (token.isEmpty()) {
            val c = sdCheck(a)
            if (c.state != TvCheckState.SIGNED_IN) failOf(c)
            token = c.token
            a.token = token
            a.tokenUntil = c.tokenUntil
        }
        val r = http(TvRequest(SD + "lineups", headers = sdHeaders(token)))
        if (!r.ok) fail(r)
        val lineups = json(r)?.optJSONArray("lineups") ?: JSONArray()
        val stations = JSONArray()
        for (i in 0 until minOf(lineups.length(), 4)) {
            val l = lineups.optJSONObject(i) ?: continue
            if (l.optBoolean("isDeleted") || l.optString("lineup").isEmpty()) continue
            val r2 = http(TvRequest(SD + "lineups/" + enc(l.optString("lineup")), headers = sdHeaders(token), timeoutMs = 60_000))
            val st = if (r2.ok) json(r2)?.optJSONArray("stations") else null
            if (st != null) for (j in 0 until st.length()) stations.put(st.opt(j))
        }
        val matched = sdMatch(channels, stations)
        if (matched.isEmpty()) return emptyList()
        val dates = ArrayList<String>()
        var t = from
        while (t <= until && dates.size < 7) { dates.add(iso(t).take(10)); t += 86400 }
        val req = JSONArray()
        for (id in matched.keys.take(200)) req.put(JSONObject().put("stationID", id).put("date", JSONArray(dates)))
        val r3 = http(TvRequest(SD + "schedules", "POST", sdHeaders(token), req.toString(), timeoutMs = 120_000))
        if (!r3.ok) fail(r3)
        val airings = sdAirings(r3.body)
        val ids = airings.map { it.program }.distinct().take(5000)
        val r4 = http(TvRequest(SD + "programs", "POST", sdHeaders(token), JSONArray(ids).toString(), timeoutMs = 120_000))
        val meta = if (r4.ok) sdPrograms(r4.body) else emptyMap()
        val out = ArrayList<TvProg>()
        for (x in airings) {
            val m = meta[x.program]
            for (c in matched[x.station] ?: emptyList()) out.add(TvProg(c.name, x.start, x.start + x.duration, m?.first ?: "", m?.second ?: "", m?.third ?: ""))
        }
        return out
    }

    /* ---- an M3U and a guide at addresses, with the account when there is one ---- */

    private fun loadM3u(links: Links, auth: TvAuth?): TvLoaded {
        val r = http(TvRequest(links.playlist, auth = auth, timeoutMs = 120_000, limit = 64 shl 20))
        if (!r.ok) fail(r)
        val parsed = M3u.parse(r.body, links.playlist)
        if (parsed.channels.isEmpty()) throw TvAccountException(parsed.error.ifEmpty { "no channels" })
        if (auth != null && !auth.isEmpty) {
            // the player answers the server's sign-in from the address
            val host = hostOf(links.playlist)
            for (c in parsed.channels) if (hostOf(c.url) == host) c.url = withCredentials(c.url, auth.user, auth.password)
        }
        return TvLoaded(parsed.channels, listOfNotNull(links.guide.ifEmpty { null }), false, playlist = r.body)
    }

    private fun checkM3u(url: String, auth: TvAuth?): TvCheck {
        val r = http(TvRequest(url, auth = auth, timeoutMs = 20_000, limit = 64 shl 20))
        if (r.ok) return TvCheck(if (auth != null && !auth.isEmpty) TvCheckState.SIGNED_IN else TvCheckState.WORKS, auth?.user ?: "")
        return TvCheck(if (r.status == 401) (if (auth != null && !auth.isEmpty) TvCheckState.WRONG else TvCheckState.REFUSED) else stateOf(r))
    }

    fun hostOf(url: String) = Regex("""^[a-z]+://(?:[^/@]*@)?([^/:?#]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: ""

    /** the account (user name and password) a type's lists ask for, null when none */
    fun authOf(typeId: String, a: TvAccount): TvAuth? = when (typeId) {
        "m3ulogin" -> TvAuth(a.username, a.password)
        "tvheadend" -> if (a.code.isEmpty() && a.username.isNotEmpty()) TvAuth(a.username, a.password) else null
        else -> null
    }?.takeIf { !it.isEmpty }

    /* ---- the types ---- */

    /** Sign in / Test: one request (a sign-in in steps tells its way: Quick Connect's code). */
    fun check(typeId: String, a: TvAccount, progress: ((TvCheck) -> Unit)? = null): TvCheck = try {
        when (typeId) {
            "jellyfin" -> jfCheck(a, progress)
            "emby" -> embyCheck(a)
            "tvheadend" -> checkM3u(tvheadend(a).playlist, authOf(typeId, a))
            "nextpvr" -> npSession(a).let { if (it.state == TvCheckState.SIGNED_IN) TvCheck(TvCheckState.WORKS) else it }
            "channelsdvr" -> checkM3u(channelsDvr(a).playlist, null)
            "hdhomerun" -> {
                val r = http(TvRequest(a.address + "/discover.json", timeoutMs = 15_000))
                val o = if (r.ok) json(r) else null
                if (o != null && (o.has("DeviceID") || o.has("LineupURL"))) TvCheck(TvCheckState.WORKS, o.optString("FriendlyName").trim().ifEmpty { o.optString("ModelNumber").trim() })
                else TvCheck(stateOf(r))
            }
            "schedulesdirect" -> sdCheck(a)
            "m3ulogin" -> checkM3u(a.url, authOf(typeId, a))
            else -> TvCheck(TvCheckState.NO_ANSWER)
        }
    } catch (e: InterruptedException) {
        TvCheck(TvCheckState.NO_ANSWER)
    }

    /** The channels (and the guides of its own) of an account. Throws [TvAccountException]. */
    fun load(typeId: String, a: TvAccount): TvLoaded = when (typeId) {
        "jellyfin" -> jfLoad(a)
        "emby" -> embyLoad(a)
        "tvheadend" -> loadM3u(tvheadend(a), authOf(typeId, a))
        "nextpvr" -> npLoad(a)
        "channelsdvr" -> loadM3u(channelsDvr(a), null)
        "hdhomerun" -> {
            val r = http(TvRequest(a.address + "/discover.json", timeoutMs = 15_000))
            val d = (if (r.ok) json(r) else null) ?: fail(r)
            val r2 = http(TvRequest(d.optString("LineupURL").ifEmpty { a.address + "/lineup.json" }, timeoutMs = 30_000))
            if (!r2.ok) fail(r2)
            val channels = hdhrChannels(r2.body)
            if (channels.isEmpty()) throw TvAccountException("no channels")
            val auth = d.optString("DeviceAuth")
            TvLoaded(channels, emptyList(), auth.isNotEmpty(), auth)
        }
        "schedulesdirect" -> {
            val c = sdCheck(a)
            if (c.state != TvCheckState.SIGNED_IN) failOf(c)
            TvLoaded(emptyList(), emptyList(), true, c.token, tokenUntil = c.tokenUntil)
        }
        "m3ulogin" -> loadM3u(Links(a.url, a.guide, true), authOf(typeId, a))
        else -> throw TvAccountException("unknown type")
    }

    /** The guide of an account that gives it as JSON (Jellyfin, Emby, NextPVR, HDHomeRun, Schedules Direct). */
    fun guide(typeId: String, a: TvAccount, channels: List<Channel>, from: Long, until: Long): List<TvProg> = when (typeId) {
        "jellyfin" -> jfGuide(a, channels, from, until)
        "emby" -> embyGuide(a, channels, from, until)
        "nextpvr" -> npGuide(a, channels, from, until)
        "hdhomerun" -> if (a.token.isEmpty()) emptyList() else {
            val r = http(TvRequest("https://api.hdhomerun.com/api/guide?DeviceAuth=" + enc(a.token), timeoutMs = 60_000))
            if (!r.ok) fail(r)
            hdhrGuide(r.body)
        }
        "schedulesdirect" -> sdGuide(a, channels, from, until)
        else -> emptyList()
    }

    /* ---- a guide given as JSON written as XMLTV (the guides' index reads it as any other) ---- */

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) when (ch) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;"); '"' -> sb.append("&quot;")
            else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\t') sb.append(ch)
        }
        return sb.toString()
    }

    private val xmltvTime = ThreadLocal<SimpleDateFormat>()

    private fun xmltvTime(seconds: Long): String = (xmltvTime.get() ?: SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC"); xmltvTime.set(this)
    }).format(Date(seconds * 1000)) + " +0000"

    fun writeXmltv(progs: List<TvProg>, out: Writer) {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"$CLIENT\">\n")
        for (ch in progs.map { it.channel }.distinct()) out.write("<channel id=\"${esc(ch)}\"><display-name>${esc(ch)}</display-name></channel>\n")
        for (p in progs) {
            if (p.start <= 0 || p.stop <= p.start) continue
            out.write("<programme start=\"${xmltvTime(p.start)}\" stop=\"${xmltvTime(p.stop)}\" channel=\"${esc(p.channel)}\">")
            out.write("<title>${esc(p.title)}</title>")
            if (p.subtitle.isNotEmpty()) out.write("<sub-title>${esc(p.subtitle)}</sub-title>")
            if (p.desc.isNotEmpty()) out.write("<desc>${esc(p.desc)}</desc>")
            if (p.episode.isNotEmpty()) out.write("<episode-num system=\"onscreen\">${esc(p.episode)}</episode-num>")
            out.write("</programme>\n")
        }
        out.write("</tv>\n")
    }
}
