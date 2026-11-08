/*
 * VLC Redesign, Live TV (IPTV): the add-on's service, as the desktop's modules/misc/iptv/service.cpp. The
 * sources (M3U playlists and Xtream Codes accounts) with their guides, kept and read on a worker thread of its
 * own: the playlists and guides cached on disk, a guide indexed once (ready in a fraction of a second at the next
 * start), the channels with their guide matched by tvg-id then by name. Off (Settings > General), nothing of it
 * is loaded and what it held is let go. The accounts of TV servers and paid guides (TvAccounts) are sources too; a
 * source's channels without a guide find one by themselves (EpgMatch, the free guides of the catalog), and the
 * catalog's entries keep what the app saw of them (works, its channels; not answering after two failures).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import org.videolan.vlc.BuildConfig
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.tools.Settings
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VLC/LiveTv"
/** the guide of an account given as JSON, read by TvAccounts.guide: "account:<source id>" */
private const val ACCOUNT_GUIDE = "account:"

const val KEY_IPTV_ENABLED = "iptv_enabled"
const val KEY_IPTV_REFRESH = "iptv_refresh"
const val KEY_IPTV_GUIDE_DAYS = "iptv_guide_days"
const val KEY_IPTV_GUIDE_HOURS = "iptv_guide_hours"
const val KEY_IPTV_LANGUAGES = "iptv_languages"
const val KEY_IPTV_BANNER = "iptv_banner"
const val KEY_IPTV_DESCRIPTIONS = "iptv_descriptions"
const val KEY_IPTV_PROGRESS = "iptv_progress"
const val KEY_IPTV_NEXT = "iptv_next"
const val KEY_IPTV_START_LAST = "iptv_start_last"
const val KEY_IPTV_ADULT = "iptv_adult"

/** What the interface shows: the channels in their order, the sources, the languages of the guides. */
data class TvState(
    val revision: Int = 0,
    val loaded: Boolean = false,
    val channels: List<TvChannel> = emptyList(),
    val sources: List<SourceStatus> = emptyList(),
    val languages: Set<String> = emptySet(),
) {
    val byKey: Map<String, TvChannel> by lazy { channels.associateBy { it.key } }
}

object LiveTv {

    private lateinit var app: Context
    private val prefs: SharedPreferences by lazy { app.getSharedPreferences("livetv", Context.MODE_PRIVATE) }
    private val settings: SharedPreferences get() = Settings.getInstance(app)

    private var worker: ScheduledExecutorService? = null
    private var quit = AtomicBoolean(false)
    private val lock = Any()

    private class SourceState {
        var channels: List<Channel> = emptyList()
        var headerGuides: List<String> = emptyList()
        var playlistUpdated = 0L
        var error = ""
        var stage = ""
        var received = 0L
        var busy = false
        var hasVod = false
        var hasSeries = false
        /** an account's sign-in refused (an update), its last Sign in / Test */
        var refused = false
        var check: TvCheck? = null
        var checking = false
        /** its account gives its guide as JSON (read by TvAccounts.guide) */
        var jsonGuide = false
        /** the automatic guide: looking, none found, when it was last looked for */
        var autoBusy = false
        var autoNone = false
        var autoTried = 0L
        /** the guide keys found for its channels (by ident: its tvg-id, else its name) and their "+1" */
        var autoKeys: Map<String, Pair<List<String>, Int>> = emptyMap()
    }

    private class GuideData(val url: String, val sourceId: String) {
        var descPath = ""
        val byKey = HashMap<String, List<Entry>>()
        val idByName = HashMap<String, String>()
        val languages = HashSet<String>()
        var updated = 0L
        var signature = ByteArray(0)
        var programmes = 0
        var error = ""
    }

    private val sources = ArrayList<SourceConfig>()
    private val states = HashMap<String, SourceState>()
    private val guides = HashMap<String, GuideData>()
    private var revision = 0

    private val _state = MutableStateFlow(TvState())
    val state: StateFlow<TvState> get() = _state

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> get() = _favorites

    /** A message for the interface ("this channel cannot be played"), its id and its text. */
    val messages = MutableStateFlow<Pair<Long, String>?>(null)

    private val dataDir get() = File(app.filesDir, "livetv")
    private val cacheDir get() = File(app.cacheDir, "livetv")
    private fun sourceDir(id: String) = File(dataDir, "sources/$id")

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _favorites.value = prefs.getString("favorites", "")!!.split('\n').filter { it.isNotEmpty() }
    }

    val isInitialized get() = ::app.isInitialized

    /** The add-on is on (Settings > General, and the first-run setup); on by default as the desktop's. */
    val enabled get() = ::app.isInitialized && settings.getBoolean(KEY_IPTV_ENABLED, true)

    fun setEnabled(on: Boolean) {
        settings.edit { putBoolean(KEY_IPTV_ENABLED, on) }
        if (on) start() else stop()
    }

    /** Starts the service once (the TV page, the player of a channel): what is cached first, then the updates. */
    @Synchronized
    fun start() {
        if (!enabled || worker != null) return
        quit = AtomicBoolean(false)
        val w = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "LiveTv").apply { priority = Thread.NORM_PRIORITY - 1 } }
        worker = w
        w.execute { startup() }
        // the updates of the sources, by the interval of Settings > TV
        w.scheduleWithFixedDelay({ refreshDue() }, 30, 30, TimeUnit.MINUTES)
    }

    /** The add-on switched off: its thread stops, what it held is let go (the cache stays on disk). */
    @Synchronized
    fun stop() {
        val w = worker ?: return
        worker = null
        quit.set(true)
        w.shutdownNow()
        synchronized(lock) {
            sources.clear(); states.clear(); guides.clear()
        }
        _state.value = TvState(revision = ++revision)
    }

    private fun post(job: () -> Unit) {
        val w = worker ?: return
        try {
            w.execute {
                try { job() } catch (e: CancelledException) { } catch (e: Throwable) { Log.w(TAG, "a job failed", e) }
            }
        } catch (e: Exception) { }
    }

    /* The configuration */

    private fun loadConfig() {
        val file = File(dataDir, "sources.json")
        val list = ArrayList<SourceConfig>()
        if (file.exists()) try {
            val root = JSONObject(file.readText())
            val a = root.optJSONArray("sources") ?: JSONArray()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val s = SourceConfig()
                s.id = o.optString("id")
                s.name = o.optString("name")
                s.kind = o.optString("kind", "m3u")
                s.url = TvNet.decrypt(o.optString("url"))
                o.optJSONArray("epg")?.let { e -> for (j in 0 until e.length()) s.epg.add(e.optString(j)) }
                s.server = o.optString("server")
                s.username = o.optString("username")
                s.password = TvNet.decrypt(o.optString("password"))
                s.catalogId = o.optString("catalogId")
                s.enabled = o.optBoolean("enabled", true)
                s.useHeaderGuides = o.optBoolean("useHeaderGuides", true)
                s.accountType = o.optString("accountType")
                s.pin = TvNet.decrypt(o.optString("pin"))
                s.key = TvNet.decrypt(o.optString("key"))
                s.code = TvNet.decrypt(o.optString("code"))
                s.guideUrl = TvNet.decrypt(o.optString("guideUrl"))
                s.token = TvNet.decrypt(o.optString("token"))
                s.userId = o.optString("userId")
                s.tokenUntil = o.optLong("tokenUntil")
                o.optJSONArray("guidesOff")?.let { e -> for (j in 0 until e.length()) s.guidesOff.add(e.optString(j)) }
                o.optJSONObject("autoGuide")?.let { a ->
                    val g = a.optJSONArray("guides")?.let { e -> (0 until e.length()).map { e.optString(it) } } ?: emptyList()
                    s.autoGuide = AutoGuide(a.optInt("found"), a.optInt("total"), g, a.optLong("at"))
                }
                s.autoGuideOff = o.optBoolean("autoGuideOff")
                if (s.id.isNotEmpty()) list.add(s)
            }
        } catch (e: Exception) {
            Log.w(TAG, "the sources could not be read", e)
        }
        synchronized(lock) {
            sources.clear()
            sources.addAll(list)
        }
    }

    private fun saveConfig() {
        val a = JSONArray()
        synchronized(lock) {
            for (s in sources) a.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("kind", s.kind)
                // the secrets encrypted by the keystore: a playlist link often holds a token
                put("url", TvNet.encrypt(s.url))
                put("epg", JSONArray(s.epg))
                put("server", s.server); put("username", s.username); put("password", TvNet.encrypt(s.password))
                put("catalogId", s.catalogId); put("enabled", s.enabled); put("useHeaderGuides", s.useHeaderGuides)
                // an account's secrets as the password: encrypted, never in the settings' copy
                put("accountType", s.accountType); put("pin", TvNet.encrypt(s.pin)); put("key", TvNet.encrypt(s.key))
                put("code", TvNet.encrypt(s.code)); put("guideUrl", TvNet.encrypt(s.guideUrl)); put("token", TvNet.encrypt(s.token))
                put("userId", s.userId); put("tokenUntil", s.tokenUntil); put("guidesOff", JSONArray(s.guidesOff))
                s.autoGuide?.let { a -> put("autoGuide", JSONObject().put("found", a.found).put("total", a.total).put("guides", JSONArray(a.guides)).put("at", a.at)) }
                put("autoGuideOff", s.autoGuideOff)
            })
        }
        dataDir.mkdirs()
        val file = File(dataDir, "sources.json")
        val tmp = File(dataDir, "sources.json.tmp")
        tmp.writeText(JSONObject().put("version", 1).put("sources", a).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** A copy of a source's configuration (the dialog of the sources edits it). */
    fun source(id: String): SourceConfig? = synchronized(lock) { sources.firstOrNull { it.id == id }?.let { copyOf(it) } }

    private fun copyOf(s: SourceConfig) = SourceConfig().also { c ->
        c.id = s.id; c.name = s.name; c.kind = s.kind; c.url = s.url; c.epg.addAll(s.epg); c.server = s.server
        c.username = s.username; c.password = s.password; c.catalogId = s.catalogId; c.enabled = s.enabled
        c.useHeaderGuides = s.useHeaderGuides; c.accountType = s.accountType; c.pin = s.pin; c.key = s.key; c.code = s.code
        c.guideUrl = s.guideUrl; c.token = s.token; c.userId = s.userId; c.tokenUntil = s.tokenUntil; c.guidesOff.addAll(s.guidesOff)
        c.autoGuide = s.autoGuide; c.autoGuideOff = s.autoGuideOff
    }

    /** Adds a source (id empty) or saves one edited; its channels and guides are read at once. */
    fun saveSource(config: SourceConfig) {
        start()
        post {
            synchronized(lock) {
                if (config.id.isEmpty()) {
                    config.id = "s" + System.currentTimeMillis().toString(36)
                    sources.add(config)
                } else {
                    val i = sources.indexOfFirst { it.id == config.id }
                    if (i >= 0) {
                        // the password (PIN, key, code) stays when the field was left empty
                        val old = sources[i]
                        if (config.password.isEmpty()) config.password = old.password
                        if (config.pin.isEmpty()) config.pin = old.pin
                        if (config.key.isEmpty()) config.key = old.key
                        if (config.code.isEmpty()) config.code = old.code
                        // a sign-in of another account, or another server: its token let go
                        if (config.server != old.server || config.username != old.username || config.password != old.password || config.key != old.key) {
                            config.token = ""; config.userId = ""; config.tokenUntil = 0
                        } else { config.token = old.token; config.userId = old.userId; config.tokenUntil = old.tokenUntil }
                        if (config.guidesOff.isEmpty()) config.guidesOff.addAll(old.guidesOff)
                        if (config.autoGuide == null) config.autoGuide = old.autoGuide
                        config.autoGuideOff = config.autoGuideOff || old.autoGuideOff
                        sources[i] = config
                    } else sources.add(config)
                }
            }
            saveConfig()
            publish()
            refreshNow(listOf(config.id), true)
        }
    }

    /** A guide added to a source (the catalog's Guides, Add a Link > Guide). */
    fun addGuide(sourceId: String, url: String) {
        post {
            synchronized(lock) { sources.firstOrNull { it.id == sourceId }?.let { if (!it.epg.contains(url)) it.epg.add(url) } }
            saveConfig()
            refreshNow(listOf(sourceId), true)
        }
    }

    fun removeSource(id: String) {
        post {
            synchronized(lock) {
                sources.removeAll { it.id == id }
                states.remove(id)
            }
            saveConfig()
            sourceDir(id).deleteRecursively()
            loadGuides(false, emptyList())
            rebuild()
        }
    }

    fun setSourceEnabled(id: String, on: Boolean) {
        post {
            synchronized(lock) { sources.firstOrNull { it.id == id }?.enabled = on }
            saveConfig()
            if (on) refreshNow(listOf(id), false) else { loadGuides(false, emptyList()); rebuild() }
        }
    }

    /** One place up (or down): the guides of the first sources come first. */
    fun moveSource(id: String, delta: Int) {
        post {
            synchronized(lock) {
                val i = sources.indexOfFirst { it.id == id }
                val j = i + delta
                if (i < 0 || j < 0 || j >= sources.size) return@post
                val s = sources.removeAt(i)
                sources.add(j, s)
            }
            saveConfig()
            rebuild()
        }
    }

    /** Update: the playlists and guides of these sources (all: empty) downloaded again now. */
    fun refresh(ids: List<String> = emptyList()) {
        start()
        post { refreshNow(ids.ifEmpty { synchronized(lock) { sources.map { it.id } } }, true) }
    }

    /* The worker */

    private fun startup() {
        val took = SystemClock.elapsedRealtime()
        loadConfig()
        val list = synchronized(lock) { sources.filter { it.enabled }.toList() }
        for (s in list) {
            val st = SourceState()
            loadPlaylist(s, false, st)
            if (s.autoGuide != null) st.autoKeys = readAutoKeys(s.id)
            synchronized(lock) { states[s.id] = st }
        }
        loadGuides(false, emptyList())
        rebuild(loaded = true)
        Log.d(TAG, "Live TV ready from the cache in ${SystemClock.elapsedRealtime() - took} ms")
        refreshDue()
    }

    private fun refreshHours(): Int = when (settings.getString(KEY_IPTV_REFRESH, "3")) {
        "0" -> 0
        "1" -> 6
        "2" -> 12
        "4" -> 168
        else -> 24
    }

    private fun ageOf(f: File) = if (f.exists()) (System.currentTimeMillis() - f.lastModified()) / 1000 else -1L

    /** The sources whose playlist is older than the interval (or missing) downloaded again. */
    private fun refreshDue() {
        val hours = refreshHours()
        val due = synchronized(lock) {
            sources.filter { s ->
                if (!s.enabled) return@filter false
                val age = ageOf(playlistFile(s))
                age < 0 || (hours > 0 && age > hours * 3600L - 600)
            }.map { it.id }
        }
        // the guides too (each one by its own age)
        if (due.isNotEmpty() || hours > 0) refreshNow(due, false, guidesByAge = true)
    }

    private fun playlistFile(s: SourceConfig) = File(sourceDir(s.id), when {
        s.kind == "xtream" -> "live.json"
        s.kind == "account" && accountIsJson(s) -> "channels.json"
        else -> "playlist.m3u"
    })

    /** an account whose channels come as JSON (not as an M3U) */
    private fun accountIsJson(s: SourceConfig) = s.accountType in setOf("jellyfin", "emby", "nextpvr", "hdhomerun", "schedulesdirect")

    private fun refreshNow(ids: List<String>, force: Boolean, guidesByAge: Boolean = false) {
        for (id in ids) {
            if (quit.get()) return
            val cfg = synchronized(lock) { sources.firstOrNull { it.id == id && it.enabled } } ?: continue
            val st = synchronized(lock) { states.getOrPut(id) { SourceState().also { if (cfg.autoGuide != null) it.autoKeys = readAutoKeys(id) } } }
            st.busy = true; st.stage = "playlist"; st.received = 0
            publish()
            val ok = loadPlaylist(cfg, true, st)
            st.busy = false; st.stage = ""
            // what the app saw of a catalog entry: works (its channels), else one more failure
            if (cfg.catalogId.isNotEmpty()) setCatalogHealth(cfg.catalogId, ok && st.channels.isNotEmpty(), st.channels.size)
            publish()
        }
        loadGuides(true, if (force) ids else emptyList(), forced = force, byAge = guidesByAge || !force)
        rebuild()
        // a source without a guide finds one by itself (unless the user took it away), once a day the others
        for (id in ids) autoGuideIfDue(id)
    }

    private fun progress(id: String, stage: String, received: Long) {
        val st = synchronized(lock) { states[id] } ?: return
        st.busy = true; st.stage = stage; st.received = received
        publish()
    }

    private fun loadPlaylist(cfg: SourceConfig, download: Boolean, st: SourceState): Boolean {
        val dir = sourceDir(cfg.id).apply { mkdirs() }
        try {
            if (cfg.kind == "xtream") return loadXtream(cfg, download, st, dir)
            if (cfg.kind == "account") return loadAccount(cfg, download, st, dir)
            val file = File(dir, "playlist.m3u")
            if (download || !file.exists()) {
                try {
                    TvNet.download(app, cfg.url, file, quit) { r, _ -> progress(cfg.id, "playlist", r) }
                    st.error = ""
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Exception) {
                    st.error = e.message ?: "the playlist could not be downloaded"
                    Log.i(TAG, "the playlist of ${TvNet.hostOf(cfg.url)} could not be downloaded: ${st.error}")
                    if (!file.exists()) return false
                }
            }
            val head = Xmltv.open(file).use { val b = ByteArray(64 * 1024); val n = it.read(b); if (n > 0) String(b, 0, n, Charsets.UTF_8) else "" }
            val playlist: Playlist
            if (!M3u.isChannelList(head) && (head.contains("#EXT-X-") || !head.contains("#EXTM3U"))) {
                // an HLS address (or a stream) is one channel, not a list
                playlist = Playlist().apply { channels.add(Channel().also { it.name = cfg.name.ifEmpty { TvNet.hostOf(cfg.url) }; it.url = cfg.url }) }
            } else {
                playlist = Xmltv.open(file).bufferedReader(Charsets.UTF_8).use { M3u.parse(it, if (cfg.url.startsWith("http")) cfg.url else "") }
            }
            if (playlist.error.isNotEmpty() && playlist.channels.isEmpty()) st.error = playlist.error
            st.channels = playlist.channels
            st.headerGuides = playlist.guideUrls
            st.playlistUpdated = file.lastModified() / 1000
            return playlist.channels.isNotEmpty()
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            st.error = e.message ?: e.javaClass.simpleName
            return false
        }
    }

    private fun loadXtream(cfg: SourceConfig, download: Boolean, st: SourceState, dir: File): Boolean {
        val account = Xtream.Account(cfg.server, cfg.username, cfg.password)
        val infoFile = File(dir, "account.json")
        val catsFile = File(dir, "categories.json")
        val liveFile = File(dir, "live.json")
        if (download || !liveFile.exists()) {
            try {
                val info = TvNet.fetch(app, Xtream.apiUrl(account), quit, 1 shl 20)
                val parsed = Xtream.parseAccountInfo(info)
                if (!parsed.authenticated) {
                    st.error = parsed.error
                    if (!liveFile.exists()) return false
                } else {
                    infoFile.writeText(info)
                    catsFile.writeText(TvNet.fetch(app, Xtream.apiUrl(account, "get_live_categories"), quit))
                    progress(cfg.id, "playlist", 0)
                    TvNet.download(app, Xtream.apiUrl(account, "get_live_streams"), liveFile, quit) { r, _ -> progress(cfg.id, "playlist", r) }
                    // what else the account has (films, series): their categories, small
                    val vod = try { Xtream.parseCategories(TvNet.fetch(app, Xtream.apiUrl(account, "get_vod_categories"), quit, 4 shl 20)).isNotEmpty() } catch (e: Exception) { false }
                    val series = try { Xtream.parseCategories(TvNet.fetch(app, Xtream.apiUrl(account, "get_series_categories"), quit, 4 shl 20)).isNotEmpty() } catch (e: Exception) { false }
                    File(dir, "library.json").writeText(JSONObject().put("vod", vod).put("series", series).toString())
                    st.error = ""
                }
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Xtream account ${cfg.id} could not be read", e)
                st.error = e.message ?: "the account could not be read"
                if (!liveFile.exists()) return false
            }
        }
        if (!liveFile.exists()) return false
        val info = Xtream.parseAccountInfo(if (infoFile.exists()) infoFile.readText() else "{}")
        val cats = Xtream.parseCategories(if (catsFile.exists()) catsFile.readText() else "[]")
        st.channels = Xtream.parseLiveStreams(liveFile.readText(), account, cats, info)
        st.headerGuides = listOf(Xtream.guideUrl(account))
        st.playlistUpdated = liveFile.lastModified() / 1000
        try {
            val lib = JSONObject(File(dir, "library.json").readText())
            st.hasVod = lib.optBoolean("vod"); st.hasSeries = lib.optBoolean("series")
        } catch (e: Exception) { }
        return st.channels.isNotEmpty()
    }

    /**
     * An account's channels: from its server (an M3U kept as the playlist, or its channels as JSON), else from the
     * cache. A refused sign-in is said on its row; a token it gave is kept (encrypted).
     */
    private fun loadAccount(cfg: SourceConfig, download: Boolean, st: SourceState, dir: File): Boolean {
        val json = accountIsJson(cfg)
        val file = File(dir, if (json) "channels.json" else "playlist.m3u")
        val auth = TvAccounts.authOf(cfg.accountType, cfg.account())
        if (download || !file.exists()) {
            try {
                val loaded = TvAccounts.load(cfg.accountType, cfg.account())
                if (json) file.writeText(channelsJson(loaded.channels).toString()) else file.writeText(loaded.playlist)
                File(dir, "account.json").writeText(JSONObject().put("guides", JSONArray(loaded.guides)).put("jsonGuide", loaded.jsonGuide).toString())
                st.error = ""
                st.refused = false
                // its token (Jellyfin, Emby, NextPVR's session, HDHomeRun's DeviceAuth, Schedules Direct's)
                if (loaded.token.isNotEmpty() && (loaded.token != cfg.token || loaded.userId != cfg.userId)) {
                    synchronized(lock) {
                        sources.firstOrNull { it.id == cfg.id }?.let {
                            it.token = loaded.token; it.userId = loaded.userId.ifEmpty { it.userId }
                            if (loaded.tokenUntil > 0) it.tokenUntil = loaded.tokenUntil
                        }
                    }
                    saveConfig()
                }
            } catch (e: CancelledException) {
                throw e
            } catch (e: TvAccountException) {
                st.refused = e.refused
                st.error = if (e.refused) app.getString(org.videolan.vlc.R.string.rd_os_login_refused_check) else e.message ?: "?"
                if (!file.exists()) return cfg.accountType == "schedulesdirect" && !e.refused
            } catch (e: Exception) {
                st.error = e.message ?: e.javaClass.simpleName
                if (!file.exists()) return false
            }
        }
        val info = try { JSONObject(File(dir, "account.json").readText()) } catch (e: Exception) { JSONObject() }
        st.jsonGuide = info.optBoolean("jsonGuide", json)
        val own = info.optJSONArray("guides")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        st.headerGuides = own + if (st.jsonGuide) listOf(ACCOUNT_GUIDE + cfg.id) else emptyList()
        st.channels = if (!file.exists()) emptyList() else if (json) channelsOf(JSONObject(file.readText())) else {
            val playlist = Xmltv.open(file).bufferedReader(Charsets.UTF_8).use { M3u.parse(it, cfg.url.ifEmpty { cfg.server }) }
            // the player answers the server's sign-in from the address (the cached list keeps none)
            if (auth != null) {
                val host = TvAccounts.hostOf(if (cfg.accountType == "m3ulogin") cfg.url else cfg.server)
                for (c in playlist.channels) if (TvAccounts.hostOf(c.url) == host) c.url = TvAccounts.withCredentials(c.url, auth.user, auth.password)
            }
            playlist.channels
        }
        st.playlistUpdated = if (file.exists()) file.lastModified() / 1000 else 0
        return st.channels.isNotEmpty() || cfg.accountType == "schedulesdirect"
    }

    private fun channelsJson(list: List<Channel>) = JSONObject().put("channels", JSONArray().apply {
        for (c in list) put(JSONObject().put("name", c.name).put("url", c.url).put("id", c.tvgId).put("logo", c.logo).put("group", c.group).put("number", c.number))
    })

    private fun channelsOf(o: JSONObject): List<Channel> {
        val a = o.optJSONArray("channels") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { j ->
            Channel().also { it.name = j.optString("name"); it.url = j.optString("url"); it.tvgId = j.optString("id"); it.logo = j.optString("logo"); it.group = j.optString("group"); it.number = j.optInt("number") }
        }
    }

    /** The guides of a source: its playlist's, those the user added, those found for its channels; less those turned off. */
    private fun guideUrlsOf(s: SourceConfig, st: SourceState?): List<String> {
        val out = ArrayList<String>(s.epg)
        if (s.useHeaderGuides || s.kind == "xtream" || s.kind == "account") st?.headerGuides?.let { out.addAll(it) }
        s.autoGuide?.let { out.addAll(it.guides) }
        return out.distinct().filter { it.isNotEmpty() && it !in s.guidesOff }
    }

    private fun guideKeysOf(c: Channel): List<String> {
        val keys = LinkedHashSet<String>()
        if (c.tvgId.isNotEmpty()) keys.add(Xmltv.guideKey(c.tvgId))
        if (c.tvgName.isNotEmpty()) keys.add(Xmltv.guideKey(c.tvgName))
        keys.add(Xmltv.guideKey(c.name))
        keys.remove("")
        return keys.toList()
    }

    private fun guideDays() = settings.getInt(KEY_IPTV_GUIDE_DAYS, 7).coerceIn(1, 14)

    fun guideLanguages(): Set<String> = (settings.getString(KEY_IPTV_LANGUAGES, "") ?: "").split(',', ' ', ';')
        .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /**
     * The guides of every source read (downloaded first when asked and due), each from its index when it is the
     * one of this file and these channels.
     */
    private fun loadGuides(download: Boolean, onlySources: List<String>, forced: Boolean = false, byAge: Boolean = true) {
        class Wanted(val url: String, val sourceId: String)
        val wanted = ArrayList<Wanted>()
        val filter = GuideFilter()
        synchronized(lock) {
            for (s in sources) {
                if (!s.enabled) continue
                val st = states[s.id] ?: continue
                for (u in guideUrlsOf(s, st)) if (wanted.none { it.url == u }) wanted.add(Wanted(u, s.id))
                for (c in st.channels) for (k in keysOf(s.id, st, c)) filter.keys.add(k)
            }
            // a paid guide (Schedules Direct) is for the channels of every source: its programmes are named by them
            if (sources.any { it.enabled && it.kind == "account" && it.accountType == "schedulesdirect" })
                for (st in states.values) for (c in st.channels) filter.keys.add(Xmltv.guideKey(c.name))
            // the guide ids the user gave channels
            for (v in guideIds().values) Xmltv.guideKey(v).takeIf { it.isNotEmpty() }?.let { filter.keys.add(it) }
        }
        val days = guideDays()
        val hours = refreshHours()
        filter.languages.addAll(guideLanguages())
        val now = System.currentTimeMillis() / 1000
        filter.from = now - days * 86400L
        filter.until = now + days * 86400L
        filter.cancel = quit
        val signature = GuideIndex.signatureOf(filter, days)
        val result = HashMap<String, GuideData>()
        val dir = File(cacheDir, "guides").apply { mkdirs() }
        if (filter.keys.isNotEmpty()) for (w in wanted) {
            if (quit.get()) return
            val base = TvNet.hashOf(w.url)
            val path = File(dir, "$base.xmltv")
            val age = ageOf(path)
            val mine = onlySources.isEmpty() || onlySources.contains(w.sourceId)
            var error = ""
            if (download && mine && (age < 0 || (forced && onlySources.isNotEmpty()) || (byAge && hours > 0 && age > hours * 3600L - 600))) {
                progress(w.sourceId, "guide", 0)
                try {
                    if (w.url.startsWith(ACCOUNT_GUIDE)) accountGuide(w.sourceId, path, days)
                    else TvNet.download(app, w.url, path, quit, guideAuthOf(w.sourceId, w.url)) { r, _ -> progress(w.sourceId, "guide", r) }
                } catch (e: CancelledException) {
                    return
                } catch (e: Exception) {
                    error = e.message ?: "?"
                    Log.i(TAG, "a guide of ${TvNet.hostOf(w.url)} could not be downloaded: $error")
                    // where it broke (debug builds: no address in it)
                    if (BuildConfig.DEBUG) Log.d(TAG, "guide download failure", e)
                }
                synchronized(lock) { states[w.sourceId]?.let { it.busy = false; it.stage = "" } }
            }
            val data = GuideData(w.url, w.sourceId)
            data.error = error
            if (!path.exists()) {
                result[w.url] = data
                continue
            }
            val took = SystemClock.elapsedRealtime()
            data.updated = path.lastModified() / 1000
            data.signature = signature
            val old = synchronized(lock) { guides[w.url] }
            if (old != null && old.signature.contentEquals(signature) && old.updated == data.updated && old.descPath.isNotEmpty()) {
                // read already for the same channels and days
                result[w.url] = old
                continue
            }
            val indexPath = File(dir, "$base.idx")
            var index = GuideIndex.read(indexPath, path, signature)
            val indexed = index != null
            if (index == null) {
                index = GuideIndex.Index()
                // every day ahead the file has: the index serves the next starts
                val all = GuideFilter().also { it.keys.addAll(filter.keys); it.languages.addAll(filter.languages); it.from = filter.from; it.cancel = quit }
                // the descriptions to a file of their own; a new file each time (the last one may be open)
                index.descName = "$base-${System.currentTimeMillis()}.desc"
                val descFile = File(dir, index.descName)
                val read = descFile.outputStream().buffered(256 * 1024).use { Xmltv.parse(path, all, it) }
                if (quit.get()) return
                if (read.error.isNotEmpty() && read.programmes == 0) data.error = read.error
                for ((k, v) in read.lists) index.lists[k] = v
                index.idByName.putAll(read.idByName)
                index.languages.addAll(read.languages)
                if (read.error.isEmpty() && !GuideIndex.write(indexPath, path, signature, index))
                    Log.i(TAG, "the index of a guide of ${TvNet.hostOf(w.url)} could not be written")
            }
            data.descPath = File(dir, index.descName).path
            // the days kept: from the days back to as many ahead
            for ((k, list) in index.lists) {
                val kept = list.filter { !(it.stop > 0 && it.stop < filter.from) && it.start <= filter.until }
                if (kept.isEmpty()) continue
                data.programmes += kept.size
                data.byKey[k] = if (kept is ArrayList) kept.apply { trimToSize() } else kept
            }
            data.idByName.putAll(index.idByName)
            data.languages.addAll(index.languages)
            Log.d(TAG, "guide of ${TvNet.hostOf(w.url)}: ${data.programmes} programmes of ${data.byKey.size} channels, " +
                    "${if (indexed) "from its index" else "read"} in ${SystemClock.elapsedRealtime() - took} ms")
            result[w.url] = data
        }
        synchronized(lock) {
            for ((url, g) in result) {
                val old = guides[url]
                // a guide that could not be read again keeps what it had
                if (g.byKey.isEmpty() && old != null && old.byKey.isNotEmpty()) {
                    old.error = g.error
                } else {
                    if (old != null && old.descPath != g.descPath && old.descPath.isNotEmpty()) File(old.descPath).delete()
                    guides[url] = g
                }
            }
            val it = guides.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (!result.containsKey(e.key) && onlySources.isEmpty()) {
                    if (e.value.descPath.isNotEmpty()) File(e.value.descPath).delete()
                    it.remove()
                }
            }
            // the descriptions of earlier readings that nothing points to
            val inUse = guides.values.map { File(it.descPath).name }.toSet()
            val bases = result.keys.map { TvNet.hashOf(it) }.toSet()
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".desc") && bases.contains(f.name.substringBefore('-')) && !inUse.contains(f.name)) f.delete()
            }
        }
    }

    /** The channels of every source in order, each with its guide (its sources' guides first). */
    private fun rebuild(loaded: Boolean = true) {
        val out = ArrayList<TvChannel>()
        val guideIds = guideIds()
        synchronized(lock) {
            val ordered = ArrayList<GuideData>()
            for (s in sources) {
                if (!s.enabled) continue
                for (u in guideUrlsOf(s, states[s.id])) guides[u]?.let { if (!ordered.contains(it)) ordered.add(it) }
            }
            var sequence = 0
            val used = HashSet<String>()
            for (s in sources) {
                if (!s.enabled) continue
                val st = states[s.id] ?: continue
                val mine = ordered.filter { it.sourceId == s.id } + ordered.filter { it.sourceId != s.id }
                for (c in st.channels) {
                    ++sequence
                    var key = s.id + if (c.tvgId.isEmpty()) "/n:" + c.name else "/i:" + c.tvgId
                    // the same channel twice in a list (two qualities)
                    if (!used.add(key)) {
                        var n = 2
                        while (!used.add("$key#$n")) ++n
                        key = "$key#$n"
                    }
                    val keys = keysOf(s.id, st, c, key)
                    // a "+1" channel matched to its main one: the guide an hour later
                    val shift = if (c.guideShiftSecs == 0) (st.autoKeys[identOf(c)]?.second ?: 0) * 3600 else 0
                    var found: List<Entry>? = null
                    var desc = ""
                    for (g in mine) {
                        var f: List<Entry>? = null
                        for (k in keys) { f = g.byKey[k]; if (f != null) break }
                        if (f == null) for (k in keys) {
                            val id = g.idByName[k] ?: continue
                            f = g.byKey[id]
                            if (f != null) break
                        }
                        if (!f.isNullOrEmpty()) { found = f; desc = g.descPath; break }
                    }
                    val channel = if (shift != 0 && guideIds[key].isNullOrEmpty()) c.copy().also { it.guideShiftSecs = shift } else c
                    out.add(TvChannel(key, s.id, channel, if (c.number > 0) c.number else sequence, found, desc))
                }
            }
            ++revision
        }
        publish(out, loaded)
    }

    private var lastChannels: List<TvChannel> = emptyList()
    private var isLoaded = false

    /** The state for the interface: the channels (as last built) and the sources as they are now. */
    private fun publish(channels: List<TvChannel>? = null, loaded: Boolean? = null) {
        val statuses = ArrayList<SourceStatus>()
        val languages = HashSet<String>()
        synchronized(lock) {
            if (channels != null) lastChannels = channels
            if (loaded != null) isLoaded = loaded
            for (s in sources) {
                val st = states[s.id]
                val mine = lastChannels.filter { it.sourceId == s.id }
                val urls = guideUrlsOf(s, st)
                val g = urls.mapNotNull { guides[it] }
                val error = st?.error?.ifEmpty { g.firstOrNull { it.error.isNotEmpty() && it.byKey.isEmpty() }?.error ?: "" } ?: ""
                // every guide of the source, those turned off too
                val all = (urls + s.guidesOff).distinct()
                val own = st?.headerGuides ?: emptyList()
                val found = s.autoGuide?.guides ?: emptyList()
                val guideStatuses = all.map { u ->
                    val d = guides[u]
                    GuideStatus(u, when { u.startsWith(ACCOUNT_GUIDE) -> "account"; u in s.epg -> "added"; u in found -> "found"; u in own -> "playlist"; else -> "added" },
                        u !in s.guidesOff, d?.byKey?.size ?: 0, d?.programmes ?: 0, d?.error ?: "", d?.updated ?: 0)
                }
                statuses.add(SourceStatus(s.id, s.name, s.kind, s.enabled, s.catalogId, mine.size, mine.count { it.hasGuide },
                    mine.maxOfOrNull { it.catchupDays } ?: 0, st?.busy == true, st?.stage ?: "", st?.received ?: 0, error,
                    st?.playlistUpdated ?: 0, st?.hasVod == true, st?.hasSeries == true, s.epg.toList(), s.accountType,
                    st?.refused == true, st?.check, st?.checking == true, s.autoGuide?.found ?: 0, s.autoGuide?.total ?: 0,
                    st?.autoBusy == true, st?.autoNone == true, guideStatuses, if (s.kind == "account") s.server else ""))
            }
            for (g in guides.values) languages.addAll(g.languages)
        }
        _state.value = TvState(revision, isLoaded, lastChannels, statuses, languages)
    }

    /* Accounts, guides, the automatic guide, the catalog's health */

    /** a channel within its source: its tvg-id, else its name (as the automatic guide keeps its keys) */
    private fun identOf(c: Channel) = if (c.tvgId.isNotEmpty()) "i:" + c.tvgId else "n:" + c.name

    /** the keys a channel is looked for in the guides: the guide id the user gave it, else its own and those found for it */
    private fun keysOf(sourceId: String, st: SourceState, c: Channel, channelKey: String = ""): List<String> {
        val given = if (channelKey.isNotEmpty()) guideIds()[channelKey] else null
        if (!given.isNullOrEmpty()) return listOf(Xmltv.guideKey(given))
        val auto = st.autoKeys[identOf(c)]?.first ?: emptyList()
        return if (auto.isEmpty()) guideKeysOf(c) else (guideKeysOf(c) + auto).distinct()
    }

    @Volatile private var guideIdsCache: Map<String, String>? = null

    /** the guide ids the user gave channels (a channel's menu: Its guide id), by the channel's key */
    fun guideIds(): Map<String, String> {
        guideIdsCache?.let { return it }
        val o = try { JSONObject(prefs.getString("guideIds", "{}")!!) } catch (e: Exception) { JSONObject() }
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotEmpty() }.also { guideIdsCache = it }
    }

    fun setGuideId(channelKey: String, guideId: String) {
        val map = guideIds().toMutableMap()
        if (guideId.isBlank()) map.remove(channelKey) else map[channelKey] = guideId.trim()
        guideIdsCache = map
        prefs.edit { putString("guideIds", JSONObject(map as Map<*, *>).toString()) }
        start()
        post { loadGuides(false, emptyList()); rebuild() }
    }

    /** the account of a guide that asks for its sign-in (an M3U with a sign-in, Tvheadend) */
    private fun guideAuthOf(sourceId: String, url: String): TvAuth? {
        val s = synchronized(lock) { sources.firstOrNull { it.id == sourceId } } ?: return null
        if (s.kind != "account" || url !in (synchronized(lock) { states[sourceId]?.headerGuides } ?: emptyList())) return null
        return TvAccounts.authOf(s.accountType, s.account())
    }

    /** an account's JSON guide written as XMLTV where the guides' index reads it */
    private fun accountGuide(sourceId: String, path: File, days: Int) {
        val s = synchronized(lock) { sources.firstOrNull { it.id == sourceId }?.let { copyOf(it) } } ?: return
        val channels = synchronized(lock) {
            if (s.accountType == "schedulesdirect") states.filterKeys { k -> sources.any { it.id == k && it.enabled } }.values.flatMap { it.channels }
            else states[sourceId]?.channels ?: emptyList()
        }
        val now = System.currentTimeMillis() / 1000
        val a = s.account()
        try {
            val progs = TvAccounts.guide(s.accountType, a, channels, now - 3600 * 6, now + days.coerceAtMost(7) * 86400L)
            val tmp = File(path.path + ".part")
            tmp.bufferedWriter(Charsets.UTF_8).use { TvAccounts.writeXmltv(progs, it) }
            if (!tmp.renameTo(path)) { path.delete(); tmp.renameTo(path) }
            synchronized(lock) { states[sourceId]?.refused = false }
        } catch (e: TvAccountException) {
            synchronized(lock) { states[sourceId]?.let { if (e.refused) it.refused = true } }
            throw java.io.IOException(e.message)
        }
        // a token it took (Schedules Direct's lasts a day)
        if (a.token.isNotEmpty() && a.token != s.token) {
            synchronized(lock) {
                sources.firstOrNull { it.id == sourceId }?.let { it.token = a.token; it.userId = a.userId.ifEmpty { it.userId }; if (a.tokenUntil > 0) it.tokenUntil = a.tokenUntil }
            }
            saveConfig()
        }
    }

    /** Sign in / Test of an account (a new one, or one added): its answer kept for its row; a token kept. */
    fun checkAccount(config: SourceConfig, progress: (TvCheck) -> Unit, done: (TvCheck) -> Unit) {
        start()
        val id = config.id
        synchronized(lock) { states[id]?.let { it.checking = true } }
        publish()
        Thread {
            // (an empty secret of an account being edited: the one kept)
            val kept = if (id.isNotEmpty()) source(id) else null
            val a = config.account()
            if (kept != null) {
                if (a.password.isEmpty()) a.password = kept.password
                if (a.pin.isEmpty()) a.pin = kept.pin
                if (a.key.isEmpty()) a.key = kept.key
                if (a.code.isEmpty()) a.code = kept.code
            }
            val r = TvAccounts.check(config.accountType, a, progress)
            synchronized(lock) {
                states[id]?.let { it.checking = false; it.check = r; it.refused = r.state == TvCheckState.WRONG || r.state == TvCheckState.REFUSED }
                if (r.token.isNotEmpty()) sources.firstOrNull { it.id == id }?.let { it.token = r.token; it.userId = r.userId; it.tokenUntil = r.tokenUntil }
            }
            if (id.isNotEmpty() && r.token.isNotEmpty()) post { saveConfig() }
            publish()
            done(r)
        }.start()
    }

    /** Sign out: an account's secrets and token forgotten (it stays, refused until signed in again). */
    fun signOut(id: String) {
        post {
            synchronized(lock) {
                sources.firstOrNull { it.id == id }?.let { it.password = ""; it.pin = ""; it.key = ""; it.code = ""; it.token = ""; it.userId = ""; it.tokenUntil = 0 }
                states[id]?.let { it.check = null; it.refused = false }
            }
            saveConfig()
            publish()
        }
    }

    /** The TV servers of this network (TvDiscovery), not added yet. */
    fun discover(done: (List<TvFound>) -> Unit) {
        Thread {
            // debug builds: the addresses the tests answer on too (the emulator's host)
            val extra = if (BuildConfig.DEBUG) (settings.getString("rd_debug_discovery_hosts", "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
            val ports = TvDiscovery.Ports.of(if (BuildConfig.DEBUG) settings.getString("rd_debug_discovery_ports", "") ?: "" else "")
            val found = TvDiscovery.find(extra, extraPorts = ports)
            val have = synchronized(lock) { sources.filter { it.kind == "account" }.map { it.server.trimEnd('/').lowercase() }.toSet() }
            done(found.filter { it.address.lowercase() !in have })
        }.start()
    }

    /* Guides: on/off, update, edit, remove */

    fun setGuideOn(sourceId: String, url: String, on: Boolean) {
        post {
            synchronized(lock) { sources.firstOrNull { it.id == sourceId }?.let { if (on) it.guidesOff.remove(url) else if (url !in it.guidesOff) it.guidesOff.add(url) } }
            saveConfig()
            if (on) refreshNow(listOf(sourceId), true) else { loadGuides(false, emptyList()); rebuild() }
        }
    }

    /** A guide the user added, or one found for the channels, taken away (one of the playlist's: turned off). */
    fun removeGuide(sourceId: String, url: String) {
        post {
            synchronized(lock) {
                sources.firstOrNull { it.id == sourceId }?.let { s ->
                    when {
                        s.epg.remove(url) -> {}
                        s.autoGuide?.guides?.contains(url) == true -> s.autoGuide = s.autoGuide?.let { a -> AutoGuide(a.found, a.total, a.guides - url, a.at) }
                        url !in s.guidesOff -> s.guidesOff.add(url)
                    }
                }
            }
            saveConfig()
            loadGuides(false, emptyList())
            rebuild()
        }
    }

    fun replaceGuide(sourceId: String, old: String, new: String) {
        post {
            synchronized(lock) { sources.firstOrNull { it.id == sourceId }?.let { s -> val i = s.epg.indexOf(old); if (i >= 0) s.epg[i] = new.trim() else s.epg.add(new.trim()) } }
            saveConfig()
            refreshNow(listOf(sourceId), true)
        }
    }

    /** a source's guides downloaded again now (Update of a guide) */
    fun refreshGuides(sourceId: String) {
        start()
        post { loadGuides(true, listOf(sourceId), forced = true); rebuild() }
    }

    /* The catalog's health: what the app saw of an entry (works, its channels; not answering after two failures) */

    fun catalogHealth(): Map<String, CatalogHealth> {
        val o = try { JSONObject(prefs.getString("catalogHealth", "{}")!!) } catch (e: Exception) { JSONObject() }
        return o.keys().asSequence().mapNotNull { k -> CatalogHealth.of(o.optJSONObject(k))?.let { k to it } }.toMap()
    }

    private fun setCatalogHealth(id: String, ok: Boolean, channels: Int) {
        val o = try { JSONObject(prefs.getString("catalogHealth", "{}")!!) } catch (e: Exception) { JSONObject() }
        o.put(id, CatalogHealth.next(CatalogHealth.of(o.optJSONObject(id)), ok, channels, System.currentTimeMillis() / 1000).toJson())
        prefs.edit { putString("catalogHealth", o.toString()) }
    }

    /* The automatic guide (EpgMatch): a source's channels without a guide matched to iptv-org's database, the free
     * guides of the catalog that carry them added for its countries */

    @Volatile private var matchIndex: Set<String>? = null
    private val matchTables = HashMap<String, EpgMatch.Table>()

    /** the tables made from iptv-org's channels.json this week (the app's files), else the app's own */
    private fun matchDir() = File(dataDir, "epgmatch")

    private fun knownCountries(): Set<String> {
        matchIndex?.let { return it }
        val text = try {
            File(matchDir(), "index.json").takeIf { it.exists() }?.readText() ?: app.assets.open("livetv/epgmatch/index.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) { "{}" }
        val o = try { JSONObject(text).optJSONObject("countries") ?: JSONObject() } catch (e: Exception) { JSONObject() }
        return o.keys().asSequence().toSet().also { matchIndex = it }
    }

    private fun matchTable(cc: String): EpgMatch.Table? = synchronized(matchTables) {
        matchTables[cc] ?: try {
            val f = File(matchDir(), "$cc.txt")
            val text = if (f.exists()) f.readText() else app.assets.open("livetv/epgmatch/$cc.txt").bufferedReader().use { it.readText() }
            EpgMatch.table(text, cc).also { matchTables[cc] = it }
        } catch (e: Exception) { null }
    }

    /**
     * iptv-org's channels.json (8 MB) and guides.json (25 MB: which channels a guide carries) read once a week into
     * the tables, only the fields needed, on a network that isn't metered (Wi-Fi, Ethernet: the app's own tables
     * meanwhile, those the Samsung TV app ships too).
     */
    private fun refreshMatchTables() {
        val index = File(matchDir(), "index.json")
        val tried = File(dataDir, "epgmatch-tried")
        if (System.currentTimeMillis() - maxOf(index.lastModified(), tried.lastModified()) < 7 * 86400_000L) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        if (cm == null || cm.isActiveNetworkMetered) return
        dataDir.mkdirs()
        tried.writeText("")
        val guidesFile = File(cacheDir, "guides.json")
        val channelsFile = File(cacheDir, "channels.json")
        try {
            fun android.util.JsonReader.text(): String = if (peek() == android.util.JsonToken.STRING) nextString() else { skipValue(); "" }
            TvNet.download(app, "https://iptv-org.github.io/api/guides.json", guidesFile, quit)
            val carried = HashSet<String>()
            android.util.JsonReader(guidesFile.bufferedReader(Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    r.beginObject()
                    while (r.hasNext()) if (r.nextName() == "channel") r.text().takeIf { it.isNotEmpty() }?.let { carried.add(it) } else r.skipValue()
                    r.endObject()
                }
                r.endArray()
            }
            guidesFile.delete()
            if (carried.size < 1000) return
            TvNet.download(app, "https://iptv-org.github.io/api/channels.json", channelsFile, quit)
            val tables = EpgMatch.TableBuilder(carried)
            android.util.JsonReader(channelsFile.bufferedReader(Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    var id = ""; var name = ""; var cc = ""; var closed = false
                    val alt = ArrayList<String>()
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "id" -> id = r.text()
                            "name" -> name = r.text()
                            "country" -> cc = r.text()
                            "alt_names" -> if (r.peek() == android.util.JsonToken.BEGIN_ARRAY) {
                                r.beginArray(); while (r.hasNext()) r.text().takeIf { it.isNotEmpty() }?.let { alt.add(it) }; r.endArray()
                            } else r.skipValue()
                            "closed", "replaced_by" -> if (r.peek() == android.util.JsonToken.NULL) r.nextNull() else if (r.text().isNotEmpty()) closed = true
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    tables.add(id, name, alt, cc, closed)
                }
                r.endArray()
            }
            channelsFile.delete()
            if (tables.countries < 50) return
            val dir = matchDir().apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.name.endsWith(".txt")) it.delete() }
            val counts = JSONObject()
            for ((c, file) in tables.files()) {
                File(dir, "$c.txt").writeText(file.first)
                counts.put(c, file.second)
            }
            File(dir, "index.json").writeText(JSONObject().put("countries", counts).put("source", "https://iptv-org.github.io/api/").toString())
            synchronized(matchTables) { matchTables.clear() }
            matchIndex = null
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "iptv-org's channels could not be read: ${e.javaClass.simpleName}")
        } finally {
            guidesFile.delete()
            channelsFile.delete()
        }
    }

    private fun autoGuideIfDue(id: String) {
        val s = synchronized(lock) { sources.firstOrNull { it.id == id && it.enabled }?.let { copyOf(it) } } ?: return
        if (s.autoGuideOff || s.kind == "account") return
        val st = synchronized(lock) { states[id] } ?: return
        if (st.channels.isEmpty()) return
        val mine = synchronized(lock) { lastChannels.filter { it.sourceId == id } }
        val noGuide = (s.epg + st.headerGuides).none { it !in s.guidesOff }
        val daily = System.currentTimeMillis() - st.autoTried > 86400_000L
        // none of its own, or found before: now (quietly the second); its guides cover a part: once a day, quietly
        if (s.autoGuide != null || noGuide) autoGuide(id, quiet = s.autoGuide != null)
        else if (daily && mine.count { it.hasGuide } < mine.size) autoGuide(id, quiet = true)
    }

    /** Finds a guide for a source's channels: all of them when it has none of its own, else those its guides don't cover. */
    fun autoGuide(id: String, quiet: Boolean = false) {
        start()
        post {
            val s = synchronized(lock) { sources.firstOrNull { it.id == id }?.let { copyOf(it) } } ?: return@post
            val st = synchronized(lock) { states[id] } ?: return@post
            if (st.autoBusy) return@post
            st.autoBusy = true
            publish()
            try {
                refreshMatchTables()
                val own = (s.epg + st.headerGuides).filter { it !in s.guidesOff }
                val covered = synchronized(lock) { lastChannels.filter { it.sourceId == id && it.hasGuide && it.channel.guideShiftSecs == 0 }.map { identOf(it.channel) }.toSet() }
                val need = st.channels.filter { c -> own.isEmpty() || identOf(c) !in covered || st.autoKeys.containsKey(identOf(c)) }
                val catalog = catalog().optJSONArray("sources")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
                val entry = catalog.firstOrNull { s.catalogId.isNotEmpty() && it.optString("id") == s.catalogId }
                val entryCountries = entry?.optJSONArray("countries")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                val order = EpgMatch.countriesOf(need, entryCountries, TvCatalogRules.userCountry(), knownCountries()).take(6)
                val tables = order.mapNotNull { cc -> matchTable(cc)?.let { cc to it } }.toMap()
                val r = EpgMatch.match(need, tables, order)
                val keys = HashMap<String, Pair<List<String>, Int>>()
                need.forEachIndexed { i, c -> r.matches[i]?.let { m -> keys[identOf(c)] = EpgMatch.keys(m) to m.shift } }
                st.autoKeys = keys
                sourceDir(id).mkdirs()
                File(sourceDir(id), "autoguide.json").writeText(JSONObject().apply {
                    for ((k, v) in keys) put(k, JSONObject().put("keys", JSONArray(v.first)).put("shift", v.second))
                }.toString())
                val health = catalogHealth()
                val guides = EpgMatch.guides(r.countries, catalog, own) { health[it]?.notAnswering == true }
                synchronized(lock) {
                    sources.firstOrNull { it.id == id }?.autoGuide = if (r.found > 0) AutoGuide(r.found, r.total, guides.map { it.url }, System.currentTimeMillis()) else null
                }
                st.autoNone = r.found == 0
                st.autoTried = System.currentTimeMillis()
                saveConfig()
                st.autoBusy = false
                loadGuides(true, listOf(id))
                rebuild()
                if (r.found > 0 && !quiet) message(app.getString(org.videolan.vlc.R.string.rd_tv_guide_found, r.found.toString(), r.total.toString()))
            } finally {
                st.autoBusy = false
                publish()
            }
        }
    }

    /** the automatic guide taken away (Undo, Remove): not looked for again by itself */
    fun removeAutoGuide(id: String) {
        post {
            synchronized(lock) {
                sources.firstOrNull { it.id == id }?.let { it.autoGuide = null; it.autoGuideOff = true }
                states[id]?.let { it.autoKeys = emptyMap(); it.autoNone = false }
            }
            File(sourceDir(id), "autoguide.json").delete()
            saveConfig()
            loadGuides(false, emptyList())
            rebuild()
        }
    }

    /** the keys found before, read with the source's cached channels */
    private fun readAutoKeys(id: String): Map<String, Pair<List<String>, Int>> = try {
        val o = JSONObject(File(sourceDir(id), "autoguide.json").readText())
        o.keys().asSequence().associateWith { k ->
            val e = o.optJSONObject(k)
            (e?.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()) to (e?.optInt("shift") ?: 0)
        }
    } catch (e: Exception) { emptyMap() }

    /* Answers for the interface */

    private val descriptions = android.util.LruCache<String, String>(256)

    /** The description of a programme, read from its guide's file (the last ones kept). */
    fun description(channel: TvChannel, e: Entry): String {
        if (e.descLength <= 0) return ""
        val key = channel.descPath + ":" + e.descOffset
        descriptions.get(key)?.let { return it }
        return GuideIndex.readDescription(channel.descPath, e.descOffset, e.descLength).also { descriptions.put(key, it) }
    }

    /** The guides read again (their days or languages changed in the settings). */
    fun reloadGuides() {
        if (worker == null) return
        post { loadGuides(false, emptyList()); rebuild() }
    }

    /** The address of a past programme from the channel's archive, "" when none (with why: too old, not yet). */
    fun catchupUrl(channel: TvChannel, start: Long, stop: Long): Pair<String, String> {
        val c = channel.channel
        if (c.catchup == CatchupMode.NONE) return "" to "no archive"
        val now = System.currentTimeMillis() / 1000
        if (start >= now) return "" to "not yet"
        if (now - start > c.catchupDays * 86400L) return "" to "too old"
        val duration = maxOf(60L, minOf(stop, now) - start).toInt()
        val url = M3u.catchupUrl(c, start, if (stop > now) (stop - start).toInt() else duration, now)
        return if (url.isEmpty()) "" to "no archive" else url to ""
    }

    fun isArchived(channel: TvChannel, e: Entry, now: Long = System.currentTimeMillis() / 1000) =
        channel.channel.catchup != CatchupMode.NONE && e.start < now && now - e.start <= channel.catchupDays * 86400L

    /* Favourites, the channels watched last, the last one */

    fun isFavorite(key: String) = _favorites.value.contains(key)

    fun toggleFavorite(key: String) {
        val list = _favorites.value.toMutableList()
        if (!list.remove(key)) list.add(key)
        _favorites.value = list
        prefs.edit { putString("favorites", list.joinToString("\n")) }
    }

    val recent: List<String> get() = prefs.getString("recent", "")!!.split('\n').filter { it.isNotEmpty() }
    val lastChannel: String get() = prefs.getString("last", "") ?: ""

    fun watched(key: String) {
        val list = recent.toMutableList()
        list.remove(key)
        list.add(0, key)
        while (list.size > 30) list.removeAt(list.size - 1)
        prefs.edit { putString("recent", list.joinToString("\n")); putString("last", key) }
    }

    /* Reminders of the guide (in the app's process, as the desktop's) */

    data class Reminder(val key: String, val start: Long, val title: String)

    val reminders: List<Reminder> get() = (prefs.getString("reminders", "") ?: "").split('\n').mapNotNull {
        val p = it.split('\t')
        if (p.size == 3) Reminder(p[0], p[1].toLongOrNull() ?: 0, p[2]) else null
    }.filter { it.start > System.currentTimeMillis() / 1000 - 600 }

    fun hasReminder(key: String, start: Long) = reminders.any { it.key == key && it.start == start }

    fun toggleReminder(key: String, start: Long, title: String) {
        val list = reminders.toMutableList()
        if (!list.removeAll { it.key == key && it.start == start }) list.add(Reminder(key, start, title.replace('\t', ' ').replace('\n', ' ')))
        prefs.edit { putString("reminders", list.joinToString("\n") { "${it.key}\t${it.start}\t${it.title}" }) }
    }

    /* The catalog of free, legal sources (the build's, or the project's newer copy) */

    private var catalogChecked = 0L

    fun catalog(): JSONObject {
        val bundled = try { JSONObject(app.assets.open("livetv/catalog.json").bufferedReader().use { it.readText() }) } catch (e: Exception) { JSONObject() }
        val cached = File(cacheDir, "catalog.json")
        val fetched = try { if (cached.exists()) JSONObject(cached.readText()) else null } catch (e: Exception) { null }
        // the newer of the two (the dates are ISO: they compare as text)
        if (fetched != null && fetched.optInt("version") == 1 && fetched.optString("updated") >= bundled.optString("updated")) return fetched
        return bundled
    }

    /** The project's copy of the catalog, at most once a day (quietly nothing when it is not there). */
    fun updateCatalog(done: () -> Unit) {
        start()
        post {
            val now = System.currentTimeMillis()
            val stamp = prefs.getLong("catalogChecked", 0)
            if (now - stamp < 86400_000L || now - catalogChecked < 86400_000L) return@post
            catalogChecked = now
            prefs.edit { putLong("catalogChecked", now) }
            try {
                val data = TvNet.fetch(app, "https://raw.githubusercontent.com/KuchiSofts/vlc-redesign/main/modules/misc/iptv/catalog/catalog.json", quit, 8 shl 20)
                val root = JSONObject(data)
                if (root.optInt("version") == 1 && root.optJSONArray("sources") != null) {
                    cacheDir.mkdirs()
                    File(cacheDir, "catalog.json").writeText(data)
                    done()
                }
            } catch (e: Exception) { }
        }
    }

    /** The project's form to suggest a source (as the desktop's suggestionUrl): a new discussion, its fields filled in. */
    fun suggestionUrl(): String {
        val body = "**Name:** \n\n**Website:** \n\n**M3U playlist link:** \n\n**EPG (XMLTV) link:** \n\n" +
                "**Languages / countries:** \n\n**Catch-up (how many days back):** \n\n**Films / series (VOD):** \n\n" +
                "**Free or paid:** \n\n**Why it belongs in the catalog (and that it is legal):** \n"
        return "https://github.com/KuchiSofts/vlc-redesign/discussions/new?category=ideas&title=" +
                M3u.encode("IPTV source: ") + "&body=" + M3u.encode(body)
    }

    /* Films and series of an Xtream Codes account */

    fun account(sourceId: String): Xtream.Account? = source(sourceId)?.takeIf { it.kind == "xtream" }?.let { Xtream.Account(it.server, it.username, it.password) }

    /** The films ("vod") or series of a source, from its cache or the account (on the worker). */
    fun library(sourceId: String, kind: String, done: (List<Xtream.VodItem>) -> Unit) {
        start()
        post {
            val account = account(sourceId) ?: return@post done(emptyList())
            val dir = sourceDir(sourceId)
            val file = File(dir, "$kind.json")
            val cats = File(dir, "$kind-categories.json")
            try {
                if (!file.exists() || ageOf(file) > 24 * 3600) {
                    cats.writeText(TvNet.fetch(app, Xtream.apiUrl(account, if (kind == "vod") "get_vod_categories" else "get_series_categories"), quit))
                    TvNet.download(app, Xtream.apiUrl(account, if (kind == "vod") "get_vod_streams" else "get_series"), file, quit)
                }
                val categories = Xtream.parseCategories(cats.readText())
                done(if (kind == "vod") Xtream.parseVodStreams(file.readText(), account, categories) else Xtream.parseSeries(file.readText(), categories))
            } catch (e: Exception) {
                done(emptyList())
            }
        }
    }

    fun seriesInfo(sourceId: String, seriesId: String, done: (Xtream.SeriesInfo) -> Unit) {
        post {
            val account = account(sourceId) ?: return@post done(Xtream.SeriesInfo())
            try {
                done(Xtream.parseSeriesInfo(TvNet.fetch(app, Xtream.apiUrl(account, "get_series_info", listOf("series_id" to seriesId)), quit), account))
            } catch (e: Exception) {
                done(Xtream.SeriesInfo())
            }
        }
    }

    fun message(text: String) {
        messages.value = SystemClock.elapsedRealtime() to text
    }

    val logoDir get() = File(cacheDir, "logos")
}
