/*
 * VLC Redesign, Live TV (IPTV): the add-on's service, as the desktop's modules/misc/iptv/service.cpp. The
 * sources (M3U playlists and Xtream Codes accounts) with their guides, kept and read on a worker thread of its
 * own: the playlists and guides cached on disk, a guide indexed once (ready in a fraction of a second at the next
 * start), the channels with their guide matched by tvg-id then by name. Off (Settings > General), nothing of it
 * is loaded and what it held is let go.
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
            })
        }
        dataDir.mkdirs()
        val file = File(dataDir, "sources.json")
        val tmp = File(dataDir, "sources.json.tmp")
        tmp.writeText(JSONObject().put("version", 1).put("sources", a).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** A copy of a source's configuration (the dialog of the sources edits it). */
    fun source(id: String): SourceConfig? = synchronized(lock) {
        sources.firstOrNull { it.id == id }?.let { s ->
            SourceConfig().also { c ->
                c.id = s.id; c.name = s.name; c.kind = s.kind; c.url = s.url; c.epg.addAll(s.epg); c.server = s.server
                c.username = s.username; c.password = s.password; c.catalogId = s.catalogId; c.enabled = s.enabled
                c.useHeaderGuides = s.useHeaderGuides
            }
        }
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
                        // the password stays when the field was left empty
                        if (config.password.isEmpty()) config.password = sources[i].password
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

    private fun playlistFile(s: SourceConfig) = File(sourceDir(s.id), if (s.kind == "xtream") "live.json" else "playlist.m3u")

    private fun refreshNow(ids: List<String>, force: Boolean, guidesByAge: Boolean = false) {
        for (id in ids) {
            if (quit.get()) return
            val cfg = synchronized(lock) { sources.firstOrNull { it.id == id && it.enabled } } ?: continue
            val st = synchronized(lock) { states.getOrPut(id) { SourceState() } }
            st.busy = true; st.stage = "playlist"; st.received = 0
            publish()
            loadPlaylist(cfg, true, st)
            st.busy = false; st.stage = ""
            publish()
        }
        loadGuides(true, if (force) ids else emptyList(), forced = force, byAge = guidesByAge || !force)
        rebuild()
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
                val urls = ArrayList(s.epg)
                if (s.useHeaderGuides || s.kind == "xtream") urls.addAll(st.headerGuides)
                for (u in urls) if (wanted.none { it.url == u }) wanted.add(Wanted(u, s.id))
                for (c in st.channels) for (k in guideKeysOf(c)) filter.keys.add(k)
            }
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
                    TvNet.download(app, w.url, path, quit) { r, _ -> progress(w.sourceId, "guide", r) }
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
        synchronized(lock) {
            val ordered = ArrayList<GuideData>()
            for (s in sources) {
                if (!s.enabled) continue
                val urls = ArrayList(s.epg).apply { addAll(states[s.id]?.headerGuides ?: emptyList()) }
                for (u in urls) guides[u]?.let { if (!ordered.contains(it)) ordered.add(it) }
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
                    val keys = guideKeysOf(c)
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
                    out.add(TvChannel(key, s.id, c, if (c.number > 0) c.number else sequence, found, desc))
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
                val g = guides.values.filter { it.sourceId == s.id }
                val error = st?.error?.ifEmpty { g.firstOrNull { it.error.isNotEmpty() && it.byKey.isEmpty() }?.error ?: "" } ?: ""
                statuses.add(SourceStatus(s.id, s.name, s.kind, s.enabled, s.catalogId, mine.size, mine.count { it.hasGuide },
                    mine.maxOfOrNull { it.catchupDays } ?: 0, st?.busy == true, st?.stage ?: "", st?.received ?: 0, error,
                    st?.playlistUpdated ?: 0, st?.hasVod == true, st?.hasSeries == true, s.epg.toList()))
            }
            for (g in guides.values) languages.addAll(g.languages)
        }
        _state.value = TvState(revision, isLoaded, lastChannels, statuses, languages)
    }

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
