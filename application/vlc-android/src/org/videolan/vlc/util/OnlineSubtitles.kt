/*
 * VLC Redesign: subtitles from the internet, as the desktop app finds them (modules/gui/qt/player/
 * subtitle_search.cpp and subtitle_providers.cpp, ported). The video is identified from its name (its title, year,
 * season and episode read from it; IMDb's suggestions, else Wikidata, then Stremio's Cinemeta give its IMDb id); the
 * free services are asked together, without an account: OpenSubtitles.org (also by the file's own hash: those made for
 * this very file), Wizdom (Hebrew), Addic7ed through Gestdown (series) and YIFY Subtitles (films); with the user's own
 * free key or account, OpenSubtitles.com and the services of KeyedSubtitles. Their subtitles are
 * ranked by language (the user's first) and by how close they are to this file. One is downloaded, unpacked (gzip,
 * zip), converted to UTF-8 (the service's encoding, else the code page of its language), cleaned of the services' ads
 * and saved; the player then adds it and remembers it for the file.
 */
package org.videolan.vlc.util

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.tools.KEY_SUBTITLE_PREFERRED_LANGUAGE
import org.videolan.tools.Settings
import org.videolan.vlc.BuildConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min

object OnlineSubtitles {
    private const val TAG = "VLC/OnlineSubtitles"

    /** Settings › Subtitles: look online by itself when a video starts without subtitles in the user's languages */
    const val KEY_AUTO = "rd_sub_online_auto"
    /** the second language (when there are none in the first) */
    const val KEY_SECOND = "rd_sub_second_language"
    /** the services turned off ("rd_sub_service_<id>" false) */
    private const val KEY_SERVICE = "rd_sub_service_"
    /** the title the user chose for a file ("rd_sub_match_<key>": imdb|title|year|series) */
    private const val KEY_MATCH = "rd_sub_match_"
    /** the user's own OpenSubtitles.com account (free): its API key, and the username and password that raise how
     *  many subtitles it may download a day; kept in the app's own settings, never logged */
    const val KEY_OSCOM_KEY = "rd_opensubtitles_api_key"
    const val KEY_OSCOM_USER = "rd_opensubtitles_username"
    const val KEY_OSCOM_PASSWORD = "rd_opensubtitles_password"
    /** where the free key is made (an account, then API consumers › New consumer) */
    const val OSCOM_KEY_PAGE = "https://www.opensubtitles.com/en/consumers"

    private const val MAX_LIST_BYTES = 6 * 1024 * 1024
    private const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
    private const val MAX_UNPACKED_BYTES = 16 * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 1000
    private const val REQUEST_GAP_MS = 600L

    val userAgent: String get() = "VLC-Redesign/${BuildConfig.RD_VERSION_NAME}"
    private val jsonUserAgent: String get() = "VLC-Redesign/${BuildConfig.RD_VERSION_NAME} (https://github.com/KuchiSofts/vlc-redesign)"

    // ---------------------------------------------------------------------------------------------------------------
    // What is searched, what is found

    data class Query(
        val imdb: String = "", val title: String = "", val year: Int = 0, val series: Boolean = false,
        val season: Int = -1, val episode: Int = -1, val fileName: String = "", val hash: String = "",
        val size: Long = 0, val languages: List<String> = emptyList(),
        /** asked in other languages too ("Every other language too"); off: only in those of the list */
        val everyOther: Boolean = true
    )

    data class Result(
        val id: String, val provider: String, val language: String, val release: String, val fileName: String = "",
        val url: String, val encoding: String = "", val exact: Boolean = false, val hearingImpaired: Boolean = false,
        val foreignOnly: Boolean = false, val downloads: Int = 0, val rank: Double = 0.0, val fps: Double = 0.0
    )

    /** what the user gives a service: an API key (or token), a user name, a password, an email (to sign in with) */
    enum class Field(val pref: String) { KEY("key"), USERNAME("username"), PASSWORD("password"), EMAIL("email") }

    /**
     * a service: its id, its name, whether it has films and series, its languages (asked only when one of them is
     * one of the user's; empty: all), the fields the user gives it (none: free) and those of them it can do without,
     * the page where its free key or account is made, whether its key is called a token
     */
    data class Provider(
        val id: String, val name: String, val movies: Boolean, val series: Boolean, val languages: Set<String> = emptySet(),
        val fields: List<Field> = emptyList(), val optional: Set<Field> = emptySet(), val page: String = "", val token: Boolean = false
    ) {
        /** it needs something of the user's (a key or an account) */
        val keyed: Boolean get() = fields.isNotEmpty()
        /** it signs in (a user name or an email, and a password) */
        val login: Boolean get() = Field.PASSWORD in fields
        /** the fields that must be given before it is asked */
        val needs: List<Field> get() = fields.filter { it !in optional }
    }

    val providers = listOf(
        Provider("opensubtitles", "OpenSubtitles", movies = true, series = true),
        // asked only with the user's own key (the account raises how many may be downloaded a day)
        Provider("opensubtitlescom", "OpenSubtitles.com", movies = true, series = true,
            fields = listOf(Field.KEY, Field.USERNAME, Field.PASSWORD), optional = setOf(Field.USERNAME, Field.PASSWORD), page = OSCOM_KEY_PAGE),
        Provider("subsource", "SubSource", movies = true, series = true, fields = listOf(Field.KEY), page = "https://subsource.net"),
        Provider("subdl", "SubDL", movies = true, series = true, fields = listOf(Field.KEY), page = "https://subdl.com/panel/api"),
        Provider("wizdom", "Wizdom", movies = true, series = true, languages = setOf("he")),
        Provider("addic7ed", "Addic7ed", movies = false, series = true),
        Provider("yify", "YIFY Subtitles", movies = true, series = false),
        // with the user's free key or account, each for its languages (Bazarr's providers: their requests)
        Provider("titlovi", "Titlovi", movies = true, series = true, languages = setOf("hr", "sr", "bs", "sl", "mk"),
            fields = listOf(Field.USERNAME, Field.PASSWORD), page = "https://titlovi.com"),
        Provider("assrt", "Assrt", movies = true, series = true, languages = setOf("zh"), fields = listOf(Field.KEY),
            page = "https://assrt.net/usercp.php", token = true),
        Provider("jimaku", "Jimaku", movies = true, series = true, languages = setOf("ja"), fields = listOf(Field.KEY), page = "https://jimaku.cc/account"),
        Provider("betaseries", "BetaSeries", movies = false, series = true, languages = setOf("fr"), fields = listOf(Field.KEY),
            page = "https://www.betaseries.com/api/"),
        Provider("subsro", "Subs.ro", movies = true, series = true, languages = setOf("ro"), fields = listOf(Field.KEY), page = "https://subs.ro/api"),
        Provider("napisy24", "Napisy24", movies = true, series = true, languages = setOf("pl"), fields = listOf(Field.USERNAME, Field.PASSWORD),
            page = "https://napisy24.pl"),
        Provider("legendasnet", "Legendas.net", movies = true, series = true, languages = setOf("pt"), fields = listOf(Field.EMAIL, Field.PASSWORD),
            page = "https://legendas.net")
    )

    fun provider(id: String) = providers.firstOrNull { it.id == id }

    /** a service is asked for these languages: one of them is one of the user's (Wizdom: Hebrew) */
    fun languageAllows(p: Provider, wanted: List<String>) = p.languages.isEmpty() ||
        wanted.any { it in p.languages || it.substringBefore('-') in p.languages }

    /** what is known of the video playing */
    data class Media(
        val name: String, val title: String, val year: Int, val season: Int, val episode: Int, val tokens: List<String>,
        val hash: String = "", val size: Long = 0, val fps: Double = 0.0, val key: String = name
    )

    /** the title it was taken for */
    data class Match(val imdb: String, val title: String, val year: Int, val series: Boolean, val strong: Boolean)

    /** a result in the list shown: its score, the rank of its language among the user's */
    data class Ranked(val result: Result, val score: Double, val languageRank: Int, val hearingImpaired: Boolean)

    /** a service asked: its name, how many it found, whether it failed to answer (and why) */
    data class Asked(val name: String, val count: Int, val failed: Boolean, val id: String = "", val problem: Problem? = null)

    class Outcome(val media: Media, val match: Match?, val results: List<Ranked>, val error: Problem?, val anyAnswered: Boolean,
                  val asked: List<Asked> = emptyList()) {
        /** "Sintel (2010)", "Breaking Bad · S01E02" */
        val matchTitle: String
            get() {
                val m = match ?: return ""
                return if (m.series && media.season >= 0 && media.episode >= 0) "%s · S%02dE%02d".format(m.title, media.season, media.episode)
                else if (m.year > 0) "${m.title} (${m.year})" else m.title
            }

        /** the best of a language that may load by itself: made for the file, or of a title surely identified */
        fun bestOf(language: String): Ranked? = results.firstOrNull { it.result.language == language && (it.result.exact || match?.strong == true) }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The user's languages and services

    /** "iw", "heb", "pt_BR", "pt-rBR", "Hebrew" -> "he", "pt-BR" (empty when unknown) */
    fun normalize(code: String?): String {
        var c = code?.trim()?.replace('_', '-')?.replace("-r", "-") ?: return ""
        if (c.isEmpty()) return ""
        val parts = c.split('-')
        var lang = parts[0].lowercase(Locale.ROOT)
        val region = parts.getOrNull(1)?.uppercase(Locale.ROOT) ?: ""
        if (lang.length == 3 && lang !in setOf("pob", "zht", "spl", "spn", "scc", "mne")) lang = iso3To1[lang] ?: lang
        lang = when (lang) {
            "iw" -> "he"; "in" -> "id"; "ji" -> "yi"; "pob" -> return "pt-BR"; "zht" -> return "zh-TW"; "spl", "spn", "ea" -> return "es-419"
            "scc" -> "sr"; "mne" -> "cnr"; else -> lang
        }
        if (lang.length == 3) lang = iso3To1[lang] ?: lang
        if (lang.length > 3) {
            // a name ("Hebrew", "Portuguese (Brazil)")
            return fromEnglishName(c)
        }
        c = when {
            lang == "pt" && region == "BR" -> "pt-BR"
            lang == "zh" && (region == "TW" || region == "HK" || region == "HANT") -> "zh-TW"
            lang == "es" && region == "419" -> "es-419"
            else -> lang
        }
        return c
    }

    // ISO 639-2 (bibliographic and terminology) to 639-1
    private val iso3To1: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (l in Locale.getISOLanguages()) try {
            map[Locale(l).isO3Language] = l
        } catch (_: Exception) {}
        for ((b, t) in iso2bToT) map[b] = map[t] ?: continue
        map
    }
    private val iso2bToT = mapOf(
        "fre" to "fra", "ger" to "deu", "chi" to "zho", "cze" to "ces", "dut" to "nld", "gre" to "ell", "per" to "fas",
        "rum" to "ron", "slo" to "slk", "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "geo" to "kat",
        "ice" to "isl", "mac" to "mkd", "may" to "msa", "tib" to "bod", "wel" to "cym"
    )

    /** ISO 639-2/B ("heb", "fre"), as file names of subtitles carry it for VLC */
    fun iso639_2(code: String): String {
        val lang = code.substringBefore('-')
        val t = try { Locale(if (lang == "he") "iw" else lang).isO3Language } catch (_: Exception) { "" }
        if (t.isEmpty()) return lang
        return iso2bToT.entries.firstOrNull { it.value == t }?.key ?: t
    }

    /** the code OpenSubtitles.org has for a language */
    fun openSubtitlesCode(code: String): String = when (code) {
        "pt-BR" -> "pob"; "zh-TW" -> "zht"; "es-419" -> "spl"; "sr" -> "scc"; "cnr" -> "mne"; "zh" -> "chi"; "he" -> "heb"
        else -> iso639_2(code).takeIf { it.length == 3 } ?: ""
    }

    /** a language a service names in English */
    fun fromEnglishName(name: String): String {
        val n = name.trim().lowercase(Locale.ROOT)
        if (n.isEmpty()) return ""
        if ("brazil" in n) return "pt-BR"
        if ("traditional" in n || "big 5" in n) return "zh-TW"
        if ("latin america" in n || "latino" in n) return "es-419"
        if (n.startsWith("farsi") || n.startsWith("persian")) return "fa"
        if (n.startsWith("chinese")) return "zh"
        if (n.startsWith("serbian")) return "sr"
        if (n.startsWith("norwegian")) return "no"
        val base = n.substringBefore('(').trim()
        for (l in Locale.getISOLanguages()) {
            val english = Locale(l).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT)
            if (english == base) return normalize(l)
        }
        return ""
    }

    /** "עברית", "English" (the language in its own words) */
    fun nativeName(code: String): String {
        val parts = code.split('-')
        val locale = Locale(if (parts[0] == "he") "iw" else parts[0], parts.getOrNull(1)?.takeIf { it.length == 2 } ?: "")
        val name = locale.getDisplayName(locale).ifEmpty { code }
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Languages to search (Settings › Subtitles): the languages the services are asked for, each on or off, in the
    // order of the results; by default the preferred language and the second one (on), then the languages chosen in
    // the player (off until turned on). "Every other language too" (off): the services asked in no other language,
    // which makes the search faster

    const val KEY_LANGUAGES = "rd_sub_search_languages"
    const val KEY_EVERY_OTHER = "rd_sub_every_other"
    private const val KEY_PICKED = "rd_sub_picked_languages"

    data class SearchLanguage(val code: String, val on: Boolean)

    /** "he:1,en:1,fr:0" */
    internal fun parseLanguages(text: String?): List<SearchLanguage> = (text ?: "").split(',').mapNotNull { item ->
        val code = normalize(item.substringBefore(':')).ifEmpty { return@mapNotNull null }
        SearchLanguage(code, item.substringAfter(':', "1") != "0")
    }.distinctBy { it.code }

    internal fun formatLanguages(list: List<SearchLanguage>) = list.joinToString(",") { it.code + ":" + if (it.on) "1" else "0" }

    /** the preferred language (else the app's) */
    fun preferredLanguage(context: Context): String = normalize(Settings.getInstance(context).getString(KEY_SUBTITLE_PREFERRED_LANGUAGE, null)).ifEmpty {
        normalize(RdCompat.primaryLocale(context.resources.configuration).toLanguageTag())
    }

    /** the list as kept, with the preferred language at its top (on) and the second one in it */
    internal fun completeLanguages(stored: List<SearchLanguage>?, preferred: String, second: String, picked: List<String>): List<SearchLanguage> {
        val list = (stored ?: buildList {
            if (preferred.isNotEmpty()) add(SearchLanguage(preferred, true))
            if (second.isNotEmpty()) add(SearchLanguage(second, true))
            for (p in picked) add(SearchLanguage(p, false))
        }).distinctBy { it.code }.toMutableList()
        if (preferred.isNotEmpty() && list.none { it.code == preferred }) list.add(0, SearchLanguage(preferred, true))
        if (second.isNotEmpty() && list.none { it.code == second }) list.add(minOf(1, list.size), SearchLanguage(second, true))
        return list
    }

    fun searchLanguages(context: Context): List<SearchLanguage> {
        val settings = Settings.getInstance(context)
        val stored = settings.getString(KEY_LANGUAGES, null)?.let { parseLanguages(it) }
        return completeLanguages(stored, preferredLanguage(context), normalize(settings.getString(KEY_SECOND, null)), pickedLanguages(context))
    }

    fun setSearchLanguages(context: Context, list: List<SearchLanguage>) =
        Settings.getInstance(context).edit().putString(KEY_LANGUAGES, formatLanguages(list.distinctBy { it.code })).apply()

    fun everyOtherLanguage(context: Context) = Settings.getInstance(context).getBoolean(KEY_EVERY_OTHER, false)

    fun pickedLanguages(context: Context) = (Settings.getInstance(context).getString(KEY_PICKED, null) ?: "").split(',').map { normalize(it) }.filter { it.isNotEmpty() }

    /** a subtitle language the user chose in the player: in the list of Languages to search, off until turned on */
    fun learnLanguage(context: Context, language: String?) {
        val code = normalize(language).ifEmpty { return }
        val settings = Settings.getInstance(context)
        val picked = (listOf(code) + pickedLanguages(context)).distinct().take(6)
        val edit = settings.edit().putString(KEY_PICKED, picked.joinToString(","))
        settings.getString(KEY_LANGUAGES, null)?.let { stored ->
            val list = parseLanguages(stored)
            if (list.none { it.code == code }) edit.putString(KEY_LANGUAGES, formatLanguages(list + SearchLanguage(code, false)))
        }
        edit.apply()
    }

    /** the user's languages, in their order: those switched on of Languages to search (the preferred one at least) */
    fun wantedLanguages(context: Context): List<String> {
        val on = searchLanguages(context).filter { it.on }.map { it.code }
        return on.ifEmpty { listOfNotNull(preferredLanguage(context).ifEmpty { null }) }
    }

    /** a result in one of the languages asked for (pt and pt-BR are told apart in the order, not here) */
    internal fun inLanguages(language: String, wanted: List<String>) = language in wanted || wanted.any { it.substringBefore('-') == language.substringBefore('-') }

    /** its switch (a service that needs the user's key or account is asked only when they are given as well). On by
     *  default: the free ones (OpenSubtitles, Wizdom, Addic7ed, YIFY Subtitles); the others are off until the user
     *  turns them on, or their key or sign-in is checked and works (OpenSubtitles.com stays on for a user who had
     *  given its key before) */
    fun switchOn(context: Context, id: String): Boolean {
        val settings = Settings.getInstance(context)
        if (settings.contains(KEY_SERVICE + id)) return settings.getBoolean(KEY_SERVICE + id, false)
        // (an OpenSubtitles.com key of before was stored with its switch on: SubtitleSecrets.migrate)
        return provider(id)?.keyed == false
    }

    /** a key or sign-in checked and working: the service turns on by itself the first time */
    fun onCredentialsWork(context: Context, id: String) {
        val settings = Settings.getInstance(context)
        if (!settings.contains(KEY_SERVICE + id)) settings.edit().putBoolean(KEY_SERVICE + id, true).apply()
    }

    /** whether the search asks it: in the list, switched on, and given what it needs */
    fun serviceEnabled(context: Context, id: String): Boolean {
        val p = provider(id) ?: return false
        return !isRemoved(context, id) && switchOn(context, id) && hasCredentials(context, p)
    }

    fun setServiceEnabled(context: Context, id: String, enabled: Boolean) = Settings.getInstance(context).edit().putBoolean(KEY_SERVICE + id, enabled).apply()

    /** the services taken out of the list (Settings › Subtitles: Remove; Add a service brings one back) */
    private const val KEY_REMOVED = "rd_sub_services_removed"

    fun removedServices(context: Context): List<String> =
        (Settings.getInstance(context).getString(KEY_REMOVED, null) ?: "").split(',').filter { id -> id.isNotEmpty() && providers.any { it.id == id } }

    fun isRemoved(context: Context, id: String) = id in removedServices(context)

    fun setRemoved(context: Context, id: String, removed: Boolean) {
        val list = removedServices(context).toMutableList()
        list.remove(id)
        if (removed) list.add(id)
        Settings.getInstance(context).edit().putString(KEY_REMOVED, list.joinToString(",")).apply()
    }

    /** the user gave the service all it needs (for a free one: nothing) */
    fun hasCredentials(context: Context, p: Provider) = p.needs.all { SubtitleSecrets.has(context, p.id, it.pref) }

    /** the user's key, user name, password or email for a service ("" when not given); off the main thread (it is
     *  kept encrypted by the Android keystore) */
    fun credential(context: Context, id: String, field: Field): String {
        val v = SubtitleSecrets.get(context, id, field.pref)
        // (a password is kept as typed)
        return if (field == Field.PASSWORD) v else v.trim()
    }

    // What the last search or check found of a service: its key or its sign-in refused (shown on its row until a
    // search succeeds or they change), or busy (a 429) a short while
    private const val KEY_STATE = "rd_sub_service_state_"
    private const val BUSY_SHOWN_MS = 5 * 60_000L

    enum class ServiceState { KEY_REFUSED, LOGIN_REFUSED, BUSY }

    fun serviceState(context: Context, id: String): ServiceState? {
        val v = Settings.getInstance(context).getString(KEY_STATE + id, null) ?: return null
        return when {
            v == "key" -> ServiceState.KEY_REFUSED
            v == "login" -> ServiceState.LOGIN_REFUSED
            v.startsWith("busy:") -> if (System.currentTimeMillis() - (v.substring(5).toLongOrNull() ?: 0L) < BUSY_SHOWN_MS) ServiceState.BUSY else null
            else -> null
        }
    }

    fun setServiceState(context: Context, id: String, state: ServiceState?) {
        val settings = Settings.getInstance(context)
        val v = when (state) {
            ServiceState.KEY_REFUSED -> "key"
            ServiceState.LOGIN_REFUSED -> "login"
            ServiceState.BUSY -> "busy:${System.currentTimeMillis()}"
            null -> null
        }
        if (settings.getString(KEY_STATE + id, null) == v) return
        settings.edit().apply { if (v == null) remove(KEY_STATE + id) else putString(KEY_STATE + id, v) }.apply()
    }

    /** a service's answer to a search: its state on its row follows */
    internal fun stateOf(problem: Problem?): ServiceState? = when (problem) {
        is Problem.KeyRefused -> ServiceState.KEY_REFUSED
        is Problem.LoginRefused, Problem.LoginFailed -> ServiceState.LOGIN_REFUSED
        is Problem.Busy -> ServiceState.BUSY
        else -> null
    }

    fun oscomKey(context: Context) = credential(context, "opensubtitlescom", Field.KEY)

    /** a service that needs a key or an account is in the list without them: the sheet says where to give them */
    fun keyedServiceWithout(context: Context) = providers.any { it.keyed && !isRemoved(context, it.id) && !hasCredentials(context, it) } &&
        providers.none { it.keyed && serviceEnabled(context, it.id) }

    // ---------------------------------------------------------------------------------------------------------------
    // HTTP

    class Response(val status: Int, val body: ByteArray, val error: String?)

    internal fun get(url: String, headers: Map<String, String>, maxBytes: Int = MAX_LIST_BYTES, timeoutMs: Int = 15_000): Response {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            for ((k, v) in headers) connection.setRequestProperty(k, v)
            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) return Response(status, ByteArray(0), "too large")
                }
            }
            Response(status, out.toByteArray(), null)
        } catch (e: Exception) {
            // (its kind only: a message may carry the address, and a key with it)
            Response(0, ByteArray(0), e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun post(url: String, headers: Map<String, String>, json: String, timeoutMs: Int = 15_000): Response {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "POST"
            connection.doOutput = true
            for ((k, v) in headers) connection.setRequestProperty(k, v)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            Response(status, stream?.use { readLimited(it, MAX_LIST_BYTES) } ?: ByteArray(0), null)
        } catch (e: Exception) {
            // (its kind only: a message may carry the address, and a key with it)
            Response(0, ByteArray(0), e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /** a POST of a form or of nothing (the parameters in the address): the answer as bytes, at most maxBytes */
    internal fun postBody(url: String, headers: Map<String, String>, body: ByteArray, contentType: String?, maxBytes: Int = MAX_LIST_BYTES,
                          timeoutMs: Int = 15_000): Response {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "POST"
            connection.doOutput = true
            // (a length, even 0: Titlovi refuses a POST without one)
            connection.setFixedLengthStreamingMode(body.size)
            for ((k, v) in headers) connection.setRequestProperty(k, v)
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            val data = stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
            Response(status, data, null)
        } catch (e: Exception) {
            Response(0, ByteArray(0), e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    internal fun form(values: Map<String, String>) = values.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }.toByteArray(Charsets.UTF_8)

    internal fun ok(r: Response) = r.error == null && r.status == 200

    /** what went wrong, shown in the interface language by the screen that asked */
    sealed class Problem {
        data class NoAnswer(val service: String) : Problem()
        data class Busy(val service: String) : Problem()
        data class Status(val service: String, val code: Int) : Problem()
        data class KeyRefused(val service: String) : Problem()
        /** its user name (or email) and password were refused */
        data class LoginRefused(val service: String) : Problem()
        data class Quota(val service: String) : Problem()
        object LoginFailed : Problem()
        object Rar : Problem()
        object Unpack : Problem()
        object NoSubtitleInArchive : Problem()
        object Vip : Problem()
        object Empty : Problem()
        object Save : Problem()
        object Offline : Problem()
        data class Download(val code: Int) : Problem()
    }

    internal fun failure(name: String, r: Response): Problem = when (r.status) {
        0 -> Problem.NoAnswer(name)
        429 -> Problem.Busy(name)
        else -> Problem.Status(name, r.status)
    }

    internal fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    // ---------------------------------------------------------------------------------------------------------------
    // Names

    /** lower case words, without accents or punctuation: names compare in any script */
    fun simplified(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).lowercase(Locale.ROOT).replace("&", " and ")
        val out = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            out.append(if (Character.isLetterOrDigit(c)) c else ' ')
        }
        return out.toString().trim().replace(Regex("\\s+"), " ")
    }

    fun titleSimilarity(a: String, b: String): Double {
        val x = simplified(a)
        val y = simplified(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val sx = x.split(' ').toSet()
        val sy = y.split(' ').toSet()
        return (sx intersect sy).size.toDouble() / (sx union sy).size.toDouble()
    }

    data class ParsedName(val title: String, val year: Int, val season: Int, val episode: Int, val tokens: List<String>)

    private val episodeRes = listOf(
        Regex("""(?:^|[\s._\-\[(])s(\d{1,2})[\s._\-]*e(\d{1,3})(?=\D|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[\s._\-\[(])(\d{1,2})x(\d{1,3})(?=\D|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[\s._\-])season[\s._\-]*(\d{1,2})[\s._\-]*episode[\s._\-]*(\d{1,3})""", RegexOption.IGNORE_CASE)
    )
    private val yearRe = Regex("""(?:^|[\s._\-\[(])((?:19|20)\d\d)(?=[\s._\-\])]|$)""")
    private val junkRe = Regex(
        """(?:^|[\s._\-\[(])(2160p|1440p|1080p|1080i|720p|576p|480p|4k|8k|uhd|hdr10\+?|hdr|dv|dovi|""" +
            """web[\s._\-]?dl|webrip|web|bluray|blu[\s._\-]?ray|bdrip|brrip|dvdrip|dvdscr|hdtv|hdrip|remux|""" +
            """x264|x265|h[\s.]?264|h[\s.]?265|hevc|avc|av1|xvid|divx|aac[\d.]*|ac3|e?ac3|ddp?[\d.]*|""" +
            """dts(?:[\s._\-]?hd)?|truehd|atmos|10bit|8bit|proper|repack|extended|unrated|uncut|""" +
            """remastered|imax|internal|limited|multi|dual|subbed|dubbed)(?=[\s._\-\])]|$)""", RegexOption.IGNORE_CASE
    )
    // (every bracket escaped: Android's ICU refuses a bare "]" or "}" that Java takes)
    private val bracketsRe = Regex("""\[[^\]]*\]|\{[^}]*\}|\([^)]*\)""")
    private val tokenRe = Regex("""[^\p{L}\p{N}]+""")

    /** "Home.Sweet.Home.Alone.2021.2160p.WEB.x265-[YTS.MX].mkv": the title, its year, a season and episode */
    fun parseName(name: String, folder: String = ""): ParsedName {
        var base = name.substringBeforeLast('.', name)
        if (base.isEmpty()) base = name
        val tokens = base.lowercase(Locale.ROOT).split(tokenRe).filter { it.isNotEmpty() }
        var cut = base.length
        var season = -1
        var episode = -1
        var year = 0
        for (re in episodeRes) {
            val m = re.find(base) ?: continue
            if (m.range.first < cut) {
                cut = m.range.first
                season = m.groupValues[1].toInt()
                episode = m.groupValues[2].toInt()
            }
        }
        for (m in yearRe.findAll(base)) {
            if (m.groups[1]!!.range.first == 0) continue
            if (m.range.first < cut) {
                cut = m.range.first
                year = m.groupValues[1].toInt()
            }
            break
        }
        junkRe.find(base)?.let { if (it.range.first > 0 && it.range.first < cut) cut = it.range.first }
        var title = base.substring(0, cut).replace(bracketsRe, " ").replace('.', ' ').replace('_', ' ')
            .trim().replace(Regex("\\s+"), " ")
        while (title.isNotEmpty() && title.last() in "-([{,") title = title.dropLast(1)
        title = title.trim()
        if (folder.isNotEmpty() && title.length <= 2) {
            val f = parseName(folder)
            if (f.title.isNotEmpty()) return ParsedName(f.title, if (year == 0) f.year else year, season, episode, tokens)
        }
        return ParsedName(title, year, season, episode, tokens)
    }

    /** the OpenSubtitles hash: the size plus the 64-bit words of the first and the last 64 KiB */
    fun openSubtitlesHash(file: File): Pair<String, Long>? = try {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            val chunk = 65536
            if (size < chunk) return null
            val head = ByteArray(chunk)
            val tail = ByteArray(chunk)
            raf.readFully(head)
            raf.seek(size - chunk)
            raf.readFully(tail)
            var hash = size
            val h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
            val t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
            for (i in 0 until chunk / 8) {
                hash += h.get(i)
                hash += t.get(i)
            }
            Pair("%016x".format(hash), size)
        }
    } catch (e: Exception) {
        null
    }

    /** what is known of a media: its name read, its local file hashed */
    fun mediaOf(uri: Uri, title: String?, fps: Double = 0.0): Media {
        val path = if (uri.scheme == "file" || uri.scheme == null) uri.path else null
        val name = path?.let { File(it).name } ?: uri.lastPathSegment?.takeIf { '.' in it } ?: (title ?: uri.lastPathSegment ?: "")
        val folder = path?.let { File(it).parentFile?.name } ?: ""
        val parsed = parseName(name, folder)
        val hashed = path?.let { openSubtitlesHash(File(it)) }
        return Media(name, parsed.title.ifEmpty { title ?: "" }, parsed.year, parsed.season, parsed.episode, parsed.tokens,
            hashed?.first ?: "", hashed?.second ?: 0L, fps, key = hashed?.let { "${it.first}:${it.second}" } ?: uri.toString())
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Identifying the video

    private fun jsonGet(url: String) = get(url, mapOf("User-Agent" to jsonUserAgent, "Accept" to "application/json"), 4 * 1024 * 1024, 20_000)

    private class Candidate(val imdb: String, val label: String, val description: String, val year: Int, val series: Boolean, val film: Boolean, val rank: Int)

    private fun strongTitle(media: Media, similarity: Double, year: Int, series: Boolean): Boolean {
        if (similarity < 0.999) return false
        if (media.year > 0 && year > 0) return abs(year - media.year) <= 1
        if (series && media.season >= 0 && media.episode >= 0) return true
        // one word without a year ("silent", "home"): too many titles share it
        return ' ' in simplified(media.title)
    }

    private fun bestCandidate(media: Media, candidates: List<Candidate>): Match? {
        val wantSeries = media.season >= 0
        var bestScore = -1e9
        var best: Match? = null
        for (c in candidates) {
            val similarity = titleSimilarity(c.label, media.title)
            if (similarity < 0.5) continue
            var score = similarity * 50
            if (media.year != 0 && c.year != 0) score += if (c.year == media.year) 30.0 else if (abs(c.year - media.year) == 1) 15.0 else -20.0
            score += if (wantSeries) (if (c.series) 30.0 else -40.0) else (if (c.film) 20.0 else if (c.series) -20.0 else 0.0)
            score += (10 - c.rank) * 2
            if (score > bestScore) {
                bestScore = score
                best = Match(c.imdb, c.label, c.year, c.series, strongTitle(media, similarity, c.year, c.series))
            }
        }
        return best
    }

    /** IMDb's suggestions (what its search box shows) */
    private fun imdbCandidates(words: String): List<Candidate> {
        val first = words.firstOrNull() ?: 'x'
        val shard = if (first.isLetterOrDigit() && first.code < 128) first.toString() else "x"
        val r = jsonGet("https://v2.sg.media-imdb.com/suggestion/$shard/${enc(words)}.json")
        if (!ok(r)) return emptyList()
        return parseImdb(String(r.body, Charsets.UTF_8))
    }

    internal fun parseImdbForTest(json: String) = parseImdb(json).map { listOf(it.imdb, it.label, it.year, it.series, it.film) }

    private fun parseImdb(json: String): List<Candidate> {
        val list = ArrayList<Candidate>()
        val items = try { JSONObject(json).optJSONArray("d") } catch (e: Exception) { null } ?: return list
        var rank = 0
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val imdb = o.optString("id")
            if (!imdb.startsWith("tt")) continue
            val kind = o.optString("qid")
            val series = kind == "tvSeries" || kind == "tvMiniSeries"
            val film = kind == "movie" || kind == "tvMovie" || kind == "short" || kind == "video"
            if (!series && !film) continue
            list.add(Candidate(imdb, o.optString("l"), o.optString("q"), o.optInt("y"), series, film, rank++))
        }
        return list
    }

    private val seriesClasses = setOf("Q5398426", "Q1259759", "Q581714", "Q526877", "Q117467246", "Q63952888")
    private val filmClasses = setOf("Q11424", "Q24862", "Q202866", "Q506240", "Q229390", "Q17517379", "Q24869", "Q93204",
        "Q29168811", "Q226730", "Q20650540")

    /** Wikidata: the items named so, then their IMDb id (P345), what they are (P31) and their date (P577, P580) */
    private fun wikidataCandidates(query: String): List<Candidate> {
        val r = jsonGet("https://www.wikidata.org/w/api.php?action=wbsearchentities&search=${enc(query)}&language=en&uselang=en&type=item&limit=10&format=json")
        if (!ok(r)) return emptyList()
        val search = try { JSONObject(String(r.body, Charsets.UTF_8)).optJSONArray("search") } catch (e: Exception) { null } ?: return emptyList()
        val ids = ArrayList<String>()
        val labels = HashMap<String, Pair<String, String>>()
        for (i in 0 until search.length()) {
            val o = search.optJSONObject(i) ?: continue
            val id = o.optString("id").ifEmpty { continue }
            ids.add(id)
            labels[id] = Pair(o.optString("label"), o.optString("description"))
        }
        if (ids.isEmpty()) return emptyList()
        val e = jsonGet("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${enc(ids.joinToString("|"))}&props=claims&format=json")
        val all = if (ok(e)) try { JSONObject(String(e.body, Charsets.UTF_8)).optJSONObject("entities") } catch (x: Exception) { null } else null
        val list = ArrayList<Candidate>()
        for ((rank, id) in ids.withIndex()) {
            val claims = all?.optJSONObject(id)?.optJSONObject("claims") ?: JSONObject()
            fun values(property: String): List<Any?> {
                val arr = claims.optJSONArray(property) ?: return emptyList()
                return (0 until arr.length()).map { arr.optJSONObject(it)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value") }
            }
            val imdb = values("P345").firstOrNull { (it as? String)?.startsWith("tt") == true } as? String ?: continue
            var series = false
            var film = false
            for (v in values("P31")) {
                val cls = (v as? JSONObject)?.optString("id") ?: continue
                series = series || cls in seriesClasses
                film = film || cls in filmClasses
            }
            val description = labels[id]?.second ?: ""
            if (!series && !film) {
                series = description.contains("series", true)
                film = description.contains("film", true) || description.contains("movie", true)
            }
            if (description.contains("episode of", true)) continue
            var year = 0
            for (property in listOf("P577", "P580")) {
                for (v in values(property)) {
                    val y = ((v as? JSONObject)?.optString("time") ?: "").let { if (it.length >= 5) it.substring(1, 5).toIntOrNull() ?: 0 else 0 }
                    if (y > 0 && (year == 0 || y < year)) year = y
                }
                if (year != 0) break
            }
            list.add(Candidate(imdb, labels[id]?.first ?: "", description, year, series, film, rank))
        }
        return list
    }

    /** Stremio's catalogue, when Wikidata doesn't know the title */
    private fun cinemetaMatch(media: Media): Match? {
        val series = media.season >= 0
        val r = jsonGet("https://v3-cinemeta.strem.io/catalog/${if (series) "series" else "movie"}/top/search=${enc(media.title)}.json")
        if (!ok(r)) return null
        val metas = try { JSONObject(String(r.body, Charsets.UTF_8)).optJSONArray("metas") } catch (e: Exception) { null } ?: return null
        var bestScore = -1e9
        var best: Match? = null
        for (i in 0 until metas.length()) {
            val o = metas.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val name = o.optString("name")
            val year = o.optString("releaseInfo").take(4).toIntOrNull() ?: 0
            val similarity = titleSimilarity(name, media.title)
            if (!id.startsWith("tt") || similarity < 0.6) continue
            var score = similarity * 50 - (i + 1) * 2
            if (media.year != 0 && year != 0) score += if (year == media.year) 30.0 else if (abs(year - media.year) == 1) 15.0 else -20.0
            if (score > bestScore) {
                bestScore = score
                best = Match(id, name, year, series, strongTitle(media, similarity, year, series))
            }
        }
        return best
    }

    /** the title of a media: the one the user chose for this file, else IMDb's, Wikidata's, Cinemeta's */
    fun identify(context: Context, media: Media): Match? {
        rememberedMatch(context, media)?.let { return it }
        if (media.title.isEmpty()) return null
        var words = simplified(media.title)
        if (media.year > 0) words += " ${media.year}"
        imdbCandidates(words).let { c -> if (c.isNotEmpty()) bestCandidate(media, c)?.let { return it } }
        wikidataCandidates(media.title).let { c -> if (c.isNotEmpty()) bestCandidate(media, c)?.let { return it } }
        return cinemetaMatch(media)
    }

    /** titles for a name the user typed (when the video was taken for another): (match, detail) */
    fun findTitles(query: String): List<Pair<Match, String>> {
        val words = simplified(query)
        if (words.isEmpty()) return emptyList()
        val candidates = imdbCandidates(words).ifEmpty { wikidataCandidates(query) }
        return candidates.take(8).map {
            Pair(Match(it.imdb, it.label, it.year, it.series, true), it.description.ifEmpty { if (it.year > 0) it.year.toString() else "" })
        }
    }

    private fun matchKey(media: Media) = KEY_MATCH + sha1(media.key).take(20)

    private fun rememberedMatch(context: Context, media: Media): Match? {
        val v = Settings.getInstance(context).getString(matchKey(media), null) ?: return null
        val p = v.split('|')
        if (p.size != 4 || !p[0].startsWith("tt")) return null
        return Match(p[0], p[1], p[2].toIntOrNull() ?: 0, p[3] == "series", true)
    }

    /** this file is that title from now on */
    fun rememberMatch(context: Context, media: Media, match: Match) {
        Settings.getInstance(context).edit().putString(matchKey(media),
            listOf(match.imdb, match.title.replace('|', ' '), match.year.toString(), if (match.series) "series" else "movie").joinToString("|")).apply()
    }

    internal fun shortHash(text: String) = sha1(text).take(12)

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------------------------------------------------------
    // The services

    private val decorationRe = Regex("""^[\s*\-:.|]+|[\s*\-:.|]+$""")

    private fun osUrl(segments: List<String>) = "https://rest.opensubtitles.org/search/" + segments.sorted().joinToString("/")

    internal fun parseOpenSubtitles(json: String, results: MutableList<Result>, seen: MutableSet<String>) {
        val items = try { JSONArray(json) } catch (e: Exception) { return }
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            fun text(key: String) = o.opt(key)?.toString()?.trim() ?: ""
            val id = text("IDSubtitleFile")
            if (id.isEmpty() || id in seen) continue
            if (text("SubAutoTranslation") == "1") continue // made by a machine
            val language = normalize(text("SubLanguageID")).ifEmpty { normalize(text("ISO639")) }
            val url = text("SubDownloadLink")
            val host = try { java.net.URI(url).host ?: "" } catch (e: Exception) { "" }
            if (language.isEmpty() || !url.startsWith("https://") || !host.endsWith("opensubtitles.org")) continue
            seen.add(id)
            val fileName = text("SubFileName")
            val downloads = text("SubDownloadsCnt").toIntOrNull() ?: 0
            results.add(Result(
                id = "opensubtitles:$id", provider = "opensubtitles", language = language,
                release = text("MovieReleaseName").replace(decorationRe, "").ifEmpty { fileName.substringBeforeLast('.') },
                fileName = fileName, url = url, encoding = text("SubEncoding"), exact = text("MatchedBy") == "moviehash",
                hearingImpaired = text("SubHearingImpaired") == "1", foreignOnly = text("SubForeignPartsOnly") == "1",
                downloads = downloads, fps = text("MovieFPS").toDoubleOrNull() ?: 0.0,
                rank = log10(downloads + 1.0) * 3 + (if (text("SubFromTrusted") == "1") 5.0 else 0.0) + (if (text("SubBad") == "1") -30.0 else 0.0)
            ))
        }
    }

    /** the requests of OpenSubtitles.org: by the file's hash, then by the title in each language (and in every
     *  language when the user asks for every other one too) */
    internal fun openSubtitlesUrls(q: Query): List<String> {
        val wanted = q.languages.map { openSubtitlesCode(it) }.filter { it.isNotEmpty() }.distinct()
        val episode = if (q.series && q.season >= 0 && q.episode >= 0) listOf("episode-${q.episode}", "season-${q.season}") else emptyList()
        val urls = ArrayList<String>()
        if (q.hash.isNotEmpty() && q.size > 0) urls.add(osUrl(listOf("moviebytesize-${q.size}", "moviehash-${q.hash}")))
        val imdb = if (q.imdb.startsWith("tt")) q.imdb.substring(2) else ""
        if (imdb.isNotEmpty()) {
            val padded = "imdbid-%07d".format(imdb.toLongOrNull() ?: 0L)
            for (code in wanted) urls.add(osUrl(episode + padded + "sublanguageid-$code"))
            if (q.everyOther || wanted.isEmpty()) urls.add(osUrl(episode + padded))
        } else if (q.title.isNotEmpty()) {
            val encoded = enc(q.title.lowercase(Locale.ROOT))
            for (code in wanted) urls.add(osUrl(episode + "query-$encoded" + "sublanguageid-$code"))
        }
        return urls
    }

    private suspend fun searchOpenSubtitles(q: Query): Pair<List<Result>, Problem?> {
        val urls = openSubtitlesUrls(q)
        val results = ArrayList<Result>()
        val seen = HashSet<String>()
        var error: Problem? = null
        var answered = 0
        for ((n, url) in urls.withIndex()) {
            if (n > 0) delay(REQUEST_GAP_MS)
            val r = get(url, mapOf("User-Agent" to userAgent, "X-User-Agent" to userAgent))
            if (ok(r)) {
                answered++
                parseOpenSubtitles(String(r.body, Charsets.UTF_8), results, seen)
            } else if (error == null) error = failure("OpenSubtitles", r)
        }
        return Pair(results, if (answered == 0 && urls.isNotEmpty()) error else null)
    }

    internal fun parseWizdom(json: String): List<Result> {
        val items = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val list = ArrayList<Result>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.opt("id")?.toString() ?: continue
            if (id.isEmpty()) continue
            list.add(Result("wizdom:$id", "wizdom", "he", o.optString("versioname").replace(decorationRe, ""),
                url = "https://wizdom.xyz/api/files/sub/$id", rank = o.optDouble("score", 0.0) * 5))
        }
        return list
    }

    private fun searchWizdom(q: Query): Pair<List<Result>, Problem?> {
        if (!q.imdb.startsWith("tt")) return Pair(emptyList(), null)
        val season = if (q.series) maxOf(q.season, 0) else 0
        val episode = if (q.series) maxOf(q.episode, 0) else 0
        val r = get("https://wizdom.xyz/api/search?action=by_id&imdb=${q.imdb}&season=$season&episode=$episode&version=${enc(q.fileName.substringBeforeLast('.'))}",
            mapOf("User-Agent" to userAgent))
        if (r.error == null && r.status == 500) return Pair(emptyList(), null) // (it answers 500 when it has nothing)
        if (!ok(r)) return Pair(emptyList(), failure("Wizdom", r))
        return Pair(parseWizdom(String(r.body, Charsets.UTF_8)), null)
    }

    private suspend fun searchAddic7ed(q: Query): Pair<List<Result>, Problem?> {
        if (!q.series || q.season < 0 || q.episode < 0 || q.title.isEmpty()) return Pair(emptyList(), null)
        val r = get("https://api.gestdown.info/shows/search/${enc(q.title)}", mapOf("User-Agent" to userAgent))
        if (r.error == null && r.status == 404) return Pair(emptyList(), null)
        if (!ok(r)) return Pair(emptyList(), failure("Addic7ed", r))
        val wanted = simplified(q.title)
        var showId = ""
        var best = 0.0
        val shows = try { JSONObject(String(r.body, Charsets.UTF_8)).optJSONArray("shows") } catch (e: Exception) { null } ?: JSONArray()
        for (i in 0 until shows.length()) {
            val o = shows.optJSONObject(i) ?: continue
            val name = simplified(o.optString("name"))
            val score = if (name == wanted) 2.0 else if (name.startsWith(wanted)) 1.0 else 0.0
            if (score > best) {
                best = score
                showId = o.optString("id")
            }
        }
        if (showId.isEmpty()) return Pair(emptyList(), null)
        val results = ArrayList<Result>()
        for ((n, language) in q.languages.take(2).withIndex()) {
            if (n > 0) delay(REQUEST_GAP_MS)
            val e = get("https://api.gestdown.info/subtitles/get/$showId/${q.season}/${q.episode}/${language.substringBefore('-')}", mapOf("User-Agent" to userAgent))
            if (!ok(e)) continue
            val items = try { JSONObject(String(e.body, Charsets.UTF_8)).optJSONArray("matchingSubtitles") } catch (x: Exception) { null } ?: continue
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val id = o.optString("subtitleId")
                val uri = o.optString("downloadUri")
                if (id.isEmpty() || !uri.startsWith("/") || !o.optBoolean("completed", true)) continue
                val downloads = o.optInt("downloadCount")
                results.add(Result("addic7ed:$id", "addic7ed", fromEnglishName(o.optString("language")).ifEmpty { language },
                    o.optString("version"), url = "https://api.gestdown.info$uri", hearingImpaired = o.optBoolean("hearingImpaired"),
                    downloads = downloads, rank = log10(downloads + 1.0) * 3 + if (o.optBoolean("corrected")) 3.0 else 0.0))
            }
        }
        return Pair(results, null)
    }

    private val yifyRowRe = Regex("""<tr data-id="(\d+)">(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val yifyRatingRe = Regex("""<span class="label[^"]*">\s*(-?\d+)\s*</span>""")
    private val yifyLanguageRe = Regex("""<span class="sub-lang">([^<]+)</span>""")
    private val yifyLinkRe = Regex("""<a href="/subtitles/([A-Za-z0-9._\-]+)">\s*(?:<span class="text-muted">[^<]*</span>)?\s*([^<]*)</a>""")

    internal fun parseYify(page: String): List<Result> {
        val list = ArrayList<Result>()
        for (row in yifyRowRe.findAll(page)) {
            val cells = row.groupValues[2]
            val link = yifyLinkRe.find(cells) ?: continue
            val language = yifyLanguageRe.find(cells)?.let { fromEnglishName(it.groupValues[1]) } ?: continue
            if (language.isEmpty()) continue
            val rating = yifyRatingRe.find(cells)?.groupValues?.get(1)?.toDoubleOrNull()
            list.add(Result("yify:${row.groupValues[1]}", "yify", language, link.groupValues[2].trim(),
                url = "https://yifysubtitles.ch/subtitle/${link.groupValues[1]}.zip", hearingImpaired = "hi-subtitle" in cells,
                rank = if (rating != null) min(rating, 30.0) * 0.5 else 0.0))
        }
        return list
    }

    private fun searchYify(q: Query): Pair<List<Result>, Problem?> {
        if (q.series || !q.imdb.startsWith("tt")) return Pair(emptyList(), null)
        val r = get("https://yifysubtitles.ch/movie-imdb/${q.imdb}", mapOf("User-Agent" to userAgent))
        if (r.error == null && r.status == 404) return Pair(emptyList(), null)
        if (!ok(r)) return Pair(emptyList(), failure("YIFY", r))
        return Pair(parseYify(String(r.body, Charsets.UTF_8)), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // OpenSubtitles.com, with the user's own free key (api.opensubtitles.com): by the IMDb id or the title, and by the
    // file's hash; a download asks it for a link (signed in when the user gave the account, for a higher quota)

    private const val OSCOM_API = "https://api.opensubtitles.com/api/v1/"

    private fun oscomHeaders(key: String, token: String? = null) = HashMap<String, String>().apply {
        put("Api-Key", key)
        put("User-Agent", "VLC-Redesign v${BuildConfig.RD_VERSION_NAME}")
        put("Accept", "application/json")
        if (!token.isNullOrEmpty()) put("Authorization", "Bearer $token")
    }

    /** the code OpenSubtitles.com has for a language ("pt-br", "zh-tw", "zh-cn", "he") */
    fun oscomCode(code: String): String = when (code) {
        "pt-BR" -> "pt-br"; "zh-TW" -> "zh-tw"; "zh" -> "zh-cn"; "pt" -> "pt-pt"; "es-419" -> "ea"
        else -> code.substringBefore('-')
    }

    internal fun parseOscom(json: String): List<Result> {
        val data = try { JSONObject(json).optJSONArray("data") } catch (e: Exception) { null } ?: return emptyList()
        val list = ArrayList<Result>()
        for (i in 0 until data.length()) {
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            if (a.optBoolean("ai_translated") || a.optBoolean("machine_translated")) continue
            val file = a.optJSONArray("files")?.optJSONObject(0) ?: continue
            val fileId = file.optLong("file_id")
            if (fileId <= 0) continue
            val language = normalize(a.optString("language")).ifEmpty { continue }
            val downloads = a.optInt("download_count")
            val fileName = file.optString("file_name")
            list.add(Result(
                id = "opensubtitlescom:$fileId", provider = "opensubtitlescom", language = language,
                release = a.optString("release").replace(decorationRe, "").ifEmpty { fileName.substringBeforeLast('.') },
                fileName = fileName, url = fileId.toString(), exact = a.optBoolean("moviehash_match"),
                hearingImpaired = a.optBoolean("hearing_impaired"), foreignOnly = a.optBoolean("foreign_parts_only"),
                downloads = downloads, fps = a.optDouble("fps", 0.0).let { if (it.isNaN()) 0.0 else it },
                rank = log10(downloads + 1.0) * 3 + (if (a.optBoolean("from_trusted")) 5.0 else 0.0)
            ))
        }
        return list
    }

    private fun searchOscom(context: Context, q: Query): Pair<List<Result>, Problem?> {
        val key = oscomKey(context)
        if (key.isEmpty()) return Pair(emptyList(), null)
        val params = ArrayList<String>()
        if (q.imdb.startsWith("tt")) params.add("${if (q.series) "parent_imdb_id" else "imdb_id"}=${q.imdb.substring(2).toLongOrNull() ?: 0}")
        else if (q.title.isNotEmpty()) params.add("query=${enc(q.title.lowercase(Locale.ROOT))}")
        if (q.hash.isNotEmpty()) params.add("moviehash=${q.hash}")
        if (params.isEmpty()) return Pair(emptyList(), null)
        if (q.series && q.season >= 0 && q.episode >= 0) params += listOf("season_number=${q.season}", "episode_number=${q.episode}")
        val languages = q.languages.map { oscomCode(it) }.filter { it.isNotEmpty() }.distinct().sorted()
        if (languages.isNotEmpty()) params.add("languages=${languages.joinToString(",")}")
        // (it asks for the parameters in alphabetical order)
        val r = get(OSCOM_API + "subtitles?" + params.sorted().joinToString("&"), oscomHeaders(key))
        if (!ok(r)) return Pair(emptyList(), if (r.status == 401 || r.status == 403) Problem.KeyRefused("OpenSubtitles.com") else failure("OpenSubtitles.com", r))
        return Pair(parseOscom(String(r.body, Charsets.UTF_8)), null)
    }

    /** what a sign-in of OpenSubtitles.com answered: its token and the host to download from, or why not */
    internal class OscomLogin(val token: String?, val base: String, val problem: Problem?)

    internal fun oscomLoginOf(status: Int, body: String): OscomLogin {
        // (403: the key itself refused; 401: the user name or the password)
        if (status == 401) return OscomLogin(null, OSCOM_API, Problem.LoginRefused("OpenSubtitles.com"))
        if (status == 403) return OscomLogin(null, OSCOM_API, Problem.KeyRefused("OpenSubtitles.com"))
        if (status == 429) return OscomLogin(null, OSCOM_API, Problem.Busy("OpenSubtitles.com"))
        if (status != 200) return OscomLogin(null, OSCOM_API, if (status == 0) Problem.NoAnswer("OpenSubtitles.com") else Problem.Status("OpenSubtitles.com", status))
        val o = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
        val token = o.optString("token").ifEmpty { null } ?: return OscomLogin(null, OSCOM_API, Problem.LoginFailed)
        val host = o.optString("base_url").removePrefix("https://").substringBefore('/')
        return OscomLogin(token, if (host == "vip-api.opensubtitles.com") "https://$host/api/v1/" else OSCOM_API, null)
    }

    /** signs in to OpenSubtitles.com with the user's account (its token kept, 12 hours) */
    internal fun oscomSignIn(context: Context, force: Boolean = false): OscomLogin {
        val key = oscomKey(context)
        val user = credential(context, "opensubtitlescom", Field.USERNAME)
        val password = credential(context, "opensubtitlescom", Field.PASSWORD)
        if (user.isEmpty() || password.isEmpty()) return OscomLogin(null, OSCOM_API, null)
        if (!force) SubtitleSecrets.token(context, "opensubtitlescom", user)?.let { return OscomLogin(it.substringBefore('|'), it.substringAfter('|', OSCOM_API), null) }
        val login = post(OSCOM_API + "login", oscomHeaders(key), JSONObject().put("username", user).put("password", password).toString())
        val result = oscomLoginOf(login.status, String(login.body, Charsets.UTF_8))
        if (result.token != null) SubtitleSecrets.setToken(context, "opensubtitlescom", user, result.token + "|" + result.base, System.currentTimeMillis() + 12 * 3600_000L)
        return result
    }

    /** the link to download a file of OpenSubtitles.com from, or the error to show */
    private fun oscomLink(context: Context, fileId: String): Pair<String?, Problem?> {
        val key = oscomKey(context)
        val login = oscomSignIn(context)
        if (login.problem != null) return Pair(null, login.problem)
        val base = login.base
        val token = login.token
        val r = post(base + "download", oscomHeaders(key, token), JSONObject().put("file_id", fileId.toLongOrNull() ?: 0L).toString())
        if (r.status == 406 || r.status == 429) return Pair(null, Problem.Quota("OpenSubtitles.com"))
        if (r.status == 401 || r.status == 403) return Pair(null, Problem.KeyRefused("OpenSubtitles.com"))
        if (!ok(r)) return Pair(null, failure("OpenSubtitles.com", r))
        val link = try { JSONObject(String(r.body, Charsets.UTF_8)).optString("link") } catch (e: Exception) { "" }
        if (!link.startsWith("https://")) return Pair(null, Problem.Download(r.status))
        return Pair(link, null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The search

    private val ignoredTokens = setOf("the", "a", "an", "of", "and", "srt", "sub", "subs", "subtitles", "heb", "eng", "he", "en")
    private val hearingRe = Regex("""(?:^|[\s._\-\[(])(hi|sdh|cc|hearing[\s._\-]?impaired)(?=[\s._\-\])]|$)""", RegexOption.IGNORE_CASE)

    /** by language, the user's first; in each, the closest to this file first */
    fun rank(found: List<Result>, media: Media, wanted: List<String>): List<Ranked> {
        val fileTokens = media.tokens.toSet()
        val fileRelease = simplified(media.name.substringBeforeLast('.'))
        val fileGroup = media.tokens.lastOrNull() ?: ""
        val list = ArrayList<Pair<Ranked, Int>>()
        for ((order, r) in found.withIndex()) {
            var score = if (r.exact) 100.0 else 0.0
            val release = simplified(r.release)
            val subtitleName = simplified(r.fileName.substringBeforeLast('.'))
            if (fileRelease.isNotEmpty() && (release == fileRelease || subtitleName == fileRelease)) score += 50
            var shared = 0
            var group = false
            for (token in "${r.release} ${r.fileName}".lowercase(Locale.ROOT).split(tokenRe)) {
                if (token.length > 1 && token in fileTokens && token !in ignoredTokens) shared++
                if (fileGroup.length > 1 && token == fileGroup) group = true
            }
            score += min(shared, 8) * 5
            if (group) score += 15
            if (r.fps > 1 && media.fps > 1) score += if (abs(r.fps - media.fps) < 0.05) 15 else -15
            val hi = r.hearingImpaired || hearingRe.containsMatchIn(r.fileName) || hearingRe.containsMatchIn(r.release)
            if (hi) score -= 3
            if (r.foreignOnly) score -= 25
            score += r.rank.coerceIn(-30.0, 20.0)
            val languageRank = wanted.indexOf(r.language).let { if (it < 0) wanted.size else it }
            list.add(Pair(Ranked(r, score, languageRank, hi), order))
        }
        return list.sortedWith(compareBy<Pair<Ranked, Int>>({ it.first.languageRank }, { nativeName(it.first.result.language) },
            { -it.first.score }, { it.second })).map { it.first }
    }

    /** identifies the media, then asks the services */
    suspend fun searchAll(context: Context, media: Media): Outcome = search(context, media, withContext(Dispatchers.IO) { identify(context, media) })

    /** whether a subtitle of that language was downloaded for the media already (its folder of the app) */
    fun hasDownloaded(context: Context, media: Media, language: String): Boolean {
        val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "subtitles/" + sha1(media.key).take(20))
        val tag = "." + iso639_2(language) + "."
        return folder.listFiles()?.any { tag in it.name && !it.name.endsWith(".part") } == true
    }

    /** what OpenSubtitles knows of this very file (its hash): the title most of the subtitles made for it are filed
     *  under (the series for an episode), its season and episode; strong when those subtitles agree on it */
    data class Identity(val match: Match, val season: Int, val episode: Int)

    private val quotedRe = Regex("^\"([^\"]+)\"")

    internal fun identityOfHash(json: String): Identity? {
        val votes = LinkedHashMap<String, Int>()
        val identities = HashMap<String, Identity>()
        var total = 0
        val array = try { JSONArray(json) } catch (e: Exception) { return null }
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            fun text(key: String) = o.opt(key)?.toString()?.trim() ?: ""
            if (text("MatchedBy") != "moviehash") continue
            val series = text("MovieKind") == "episode"
            val number = (if (series) text("SeriesIMDBParent") else text("IDMovieImdb")).toLongOrNull() ?: 0L
            if (number <= 0) continue
            var title = text("MovieName")
            var season = -1
            var episode = -1
            if (series) {
                // (the series in quotes, the episode after it: "\"Breaking Bad\" Pilot")
                quotedRe.find(title)?.let { title = it.groupValues[1] }
                season = text("SeriesSeason").toIntOrNull() ?: 0
                episode = text("SeriesEpisode").toIntOrNull() ?: 0
                if (season <= 0 && episode <= 0) { season = -1; episode = -1 }
            }
            if (title.isEmpty()) continue
            val imdb = "tt%07d".format(Locale.ROOT, number)
            val key = "$imdb/$season/$episode"
            votes[key] = (votes[key] ?: 0) + 1
            total++
            if (key !in identities)
                identities[key] = Identity(Match(imdb, title, if (series) 0 else text("MovieYear").toIntOrNull() ?: 0, series, false), season, episode)
        }
        val best = votes.maxByOrNull { it.value } ?: return null
        val identity = identities[best.key] ?: return null
        return identity.copy(match = identity.match.copy(strong = best.value * 2 > total))
    }

    private fun identifyByHash(media: Media): Identity? {
        if (media.hash.isEmpty() || media.size <= 0) return null
        val r = get(osUrl(listOf("moviebytesize-${media.size}", "moviehash-${media.hash}")), mapOf("User-Agent" to userAgent, "X-User-Agent" to userAgent))
        return if (ok(r)) identityOfHash(String(r.body, Charsets.UTF_8)) else null
    }

    /** asks the services together for the media taken for match (null: not identified) */
    suspend fun search(context: Context, mediaAsked: Media, match: Match?): Outcome = withContext(Dispatchers.IO) {
        val wanted = wantedLanguages(context)
        var m = match
        var media = mediaAsked
        // its name didn't say what it is, or only a guess: OpenSubtitles may know this very file (its hash). With its
        // title every service is asked (YIFY and Wizdom go by the IMDb id), not only the hash's
        if (m == null || !m.strong) {
            val found = identifyByHash(media)
            // (about this very file: it wins over a guess from the name)
            if (found != null) {
                Log.d(TAG, "the file's hash says ${found.match.title} (${found.match.imdb})${if (found.match.strong) "" else ", a guess"}")
                m = found.match
                if (found.match.series && media.season < 0 && found.season >= 0) media = media.copy(season = found.season, episode = found.episode)
            }
        }
        val query = Query(
            imdb = m?.imdb ?: "", title = m?.title ?: media.title, year = m?.year ?: media.year,
            series = m?.series ?: (media.season >= 0), season = media.season, episode = media.episode,
            fileName = media.name, hash = media.hash, size = media.size, languages = wanted, everyOther = everyOtherLanguage(context)
        )
        // the services on (with the user's key or account when they need one), of this kind of video, of one of the
        // user's languages (Wizdom: when Hebrew is one of them)
        val asked = providers.filter { serviceEnabled(context, it.id) && (if (query.series) it.series else it.movies) && languageAllows(it, wanted) }
        val answers = coroutineScope {
            asked.map { p ->
                async {
                    try {
                        when (p.id) {
                            "opensubtitles" -> searchOpenSubtitles(query)
                            "opensubtitlescom" -> searchOscom(context, query)
                            "wizdom" -> searchWizdom(query)
                            "addic7ed" -> searchAddic7ed(query)
                            "yify" -> searchYify(query)
                            else -> KeyedSubtitles.search(context, p, query)
                        }
                    } catch (e: Exception) {
                        Pair(emptyList<Result>(), Problem.NoAnswer(p.name))
                    }
                }
            }.awaitAll()
        }
        // its row in the settings: a refused key or sign-in, or busy; cleared by a search it answered
        asked.forEachIndexed { i, p ->
            val problem = answers[i].second
            val state = stateOf(problem)
            if (problem == null || state != null) setServiceState(context, p.id, state)
        }
        // only the languages of the list, unless every other one is asked for too (the line of the services counts
        // those shown)
        val kept = answers.map { a -> a.first.filter { query.everyOther || inLanguages(it.language, wanted) } }
        val found = kept.flatten()
        val errors = answers.mapNotNull { it.second }
        Log.d(TAG, "\"${media.title}\" is ${m?.title ?: "unknown"} (${m?.imdb ?: "-"}${if (m?.strong == true) ", sure" else ""}), the hash ${media.hash.ifEmpty { "unknown" }}: ${found.size} found")
        Outcome(media, m, rank(found, media, wanted), errors.firstOrNull(), answers.any { it.second == null } || asked.isEmpty(),
            asked.mapIndexed { i, p -> Asked(p.name, kept[i].size, answers[i].second != null, p.id, answers[i].second) })
    }

    // ---------------------------------------------------------------------------------------------------------------
    // What was downloaded

    private fun isSubtitleName(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("srt", "ass", "ssa", "vtt", "sub", "smi", "txt")

    private fun validUtf8(data: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
        true
    } catch (e: Exception) {
        false
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    /** the subtitles of a zip archive: (name, data), at most MAX_UNPACKED_BYTES in all */
    internal fun unzip(zip: ByteArray): List<Pair<String, ByteArray>> {
        val files = ArrayList<Pair<String, ByteArray>>()
        var total = 0
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                var count = 0
                while (count++ < MAX_ARCHIVE_ENTRIES) {
                    val entry = z.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name.startsWith("__MACOSX")) continue
                    // (listed without data: an archive of other files is told apart from one that can't be read)
                    if (!isSubtitleName(name)) {
                        files.add(Pair(name, ByteArray(0)))
                        continue
                    }
                    val data = readLimited(z, minOf(MAX_DOWNLOAD_BYTES, MAX_UNPACKED_BYTES - total)) ?: continue
                    total += data.size
                    files.add(Pair(name, data))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "unzip: ${e.message}")
        }
        return files
    }

    /** the Windows code page of a language's subtitles that aren't in UTF-8 */
    internal fun codePageOf(language: String): String = when (language) {
        "he", "yi" -> "windows-1255"
        "ar", "fa", "ur", "prs", "ckb" -> "windows-1256"
        "ru", "uk", "bg", "be", "mk", "sr", "kk", "ky", "tg", "mn" -> "windows-1251"
        "el" -> "windows-1253"; "tr", "az" -> "windows-1254"
        "cs", "sk", "pl", "hu", "ro", "hr", "sl", "bs", "sq", "cnr" -> "windows-1250"
        "lt", "lv", "et" -> "windows-1257"; "vi" -> "windows-1258"; "th" -> "x-windows-874"
        "ja" -> "Shift_JIS"; "zh" -> "GBK"; "zh-TW", "yue" -> "Big5"; "ko" -> "EUC-KR"
        else -> "windows-1252"
    }

    /** the Java name of an encoding a service names ("CP1255", "windows-1256", "ISO-8859-8", "UTF-8") */
    internal fun charsetNamed(encoding: String): String? {
        val e = encoding.trim().lowercase(Locale.ROOT).replace("_", "")
        if (e.isEmpty()) return null
        if (e == "utf-8" || e == "utf8") return "UTF-8"
        Regex("""^(?:cp|windows-?|ms)(\d{3,4})$""").find(e)?.let { return if (it.groupValues[1] == "874") "x-windows-874" else "windows-${it.groupValues[1]}" }
        Regex("""^iso-?8859-(\d{1,2})$""").find(e)?.let { return "ISO-8859-${it.groupValues[1]}" }
        return when (e) {
            "koi8-r" -> "KOI8-R"; "koi8-u" -> "KOI8-U"; "gb2312", "gbk" -> "GBK"; "gb18030" -> "GB18030"; "big5" -> "Big5"
            "shift-jis", "shiftjis", "sjis" -> "Shift_JIS"; "euc-kr" -> "EUC-KR"; "euc-jp" -> "EUC-JP"; "tis-620" -> "TIS-620"
            "ascii", "us-ascii" -> "windows-1252"
            else -> null
        }
    }

    private fun decodeWith(data: ByteArray, charset: String): String = try {
        val decoder: CharsetDecoder = Charset.forName(charset).newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        decoder.decode(ByteBuffer.wrap(data)).toString()
    } catch (e: Exception) {
        String(data, Charsets.ISO_8859_1)
    }

    /** its BOM, else UTF-8 when it is, else the encoding the service said, else the code page of its language */
    internal fun decodeText(data: ByteArray, encoding: String, language: String): String {
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) return String(data, 3, data.size - 3, Charsets.UTF_8)
        if (data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) return String(data, 2, data.size - 2, Charsets.UTF_16LE)
        if (data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) return String(data, 2, data.size - 2, Charsets.UTF_16BE)
        if (validUtf8(data)) return String(data, Charsets.UTF_8)
        val named = charsetNamed(encoding)
        return decodeWith(data, if (named == null || named == "UTF-8") codePageOf(language) else named)
    }

    private fun scriptRange(charset: String): IntRange? = when (charset) {
        "windows-1255" -> 0x0590..0x05FF; "windows-1256" -> 0x0600..0x06FF; "windows-1251" -> 0x0400..0x04FF
        "windows-1253" -> 0x0370..0x03FF; "x-windows-874" -> 0x0E00..0x0E7F
        else -> null
    }

    private fun hasScriptOf(text: String, charset: String): Boolean {
        val range = scriptRange(charset) ?: return true
        return text.any { it.code in range }
    }

    /** text of another code page saved as UTF-8 by way of Windows-1252 (Addic7ed's Hebrew): back to its letters */
    internal fun repairDoubleEncoding(text: String, charset: String): String {
        if (hasScriptOf(text, charset)) return text
        if (text.count { it.code in 0xC0..0xFF } < 20) return text
        val bytes = try {
            Charset.forName("windows-1252").newEncoder().onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(text)).let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        } catch (e: Exception) {
            return text
        }
        val repaired = decodeWith(bytes, charset)
        return if (hasScriptOf(repaired, charset)) repaired else text
    }

    private val adRe = Regex("""osdb\.link|(?:get|try)ray\.app|opensubtitles\.(?:org|com)|Advertise your product|become VIP member|api\.opensubtitles""", RegexOption.IGNORE_CASE)

    /** the ads a service adds as subtitles of their own */
    internal fun withoutAds(text: String): String {
        val blocks = text.split(Regex("""\r?\n[ \t]*\r?\n"""))
        if (blocks.size < 3 || "-->" !in text) return text
        return blocks.filter { !adRe.containsMatchIn(it) }.joinToString("\n\n")
    }

    private fun extensionOf(text: String, name: String): String {
        val start = text.take(512).trim()
        if (start.startsWith("WEBVTT")) return "vtt"
        if ("[Script Info]" in start) return "ass"
        val suffix = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (suffix == "sub" || suffix == "smi" || suffix == "ssa") suffix else "srt"
    }

    class Unpacked(val text: String, val extension: String)

    /** a download: unpacked, converted to UTF-8; an error to show otherwise */
    fun subtitleOf(download: ByteArray, result: Result, videoName: String): Pair<Unpacked?, Problem?> {
        var data = download
        var name = result.fileName
        if (data.size > 4 && String(data, 0, 4, Charsets.ISO_8859_1) == "Rar!") return Pair(null, Problem.Rar)
        if (data.size > 2 && data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) {
            data = try { GZIPInputStream(ByteArrayInputStream(data)).use { readLimited(it, MAX_UNPACKED_BYTES) } } catch (e: Exception) { null }
                ?: return Pair(null, Problem.Unpack)
        } else if (data.size > 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() && data[2] == 3.toByte() && data[3] == 4.toByte()) {
            // the subtitle of the archive named most like the video, in UTF-8 rather than not
            val files = unzip(data)
            val video = simplified(videoName.substringBeforeLast('.'))
            var best = -1e9
            var chosen: Pair<String, ByteArray>? = null
            for (file in files) {
                if (file.second.isEmpty()) continue
                val entry = simplified(file.first.substringAfterLast('/').substringBeforeLast('.'))
                var score = if (validUtf8(file.second)) 5.0 else 0.0
                if (video.isNotEmpty() && entry == video) score += 20
                for (word in video.split(' ')) if (word.length > 1 && word in entry) score += 1
                score += log10(file.second.size + 1.0) * 0.1
                if (score > best) {
                    best = score
                    chosen = file
                }
            }
            chosen ?: return Pair(null, if (files.isEmpty()) Problem.Unpack else Problem.NoSubtitleInArchive)
            data = chosen.second
            name = chosen.first
        }
        var text = decodeText(data, result.encoding, result.language)
        val page = codePageOf(result.language)
        if (page != "windows-1252") text = repairDoubleEncoding(text, page)
        if (name.contains("only-for-vip", true) || text.contains("osdb.link/vip", true))
            return Pair(null, Problem.Vip)
        text = withoutAds(text)
        if (text.isBlank()) return Pair(null, Problem.Empty)
        return Pair(Unpacked(text, extensionOf(text, name)), null)
    }

    private fun safeFileName(name: String): String {
        var safe = name.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' || it == '-') it else '_' }.joinToString("")
        while (".." in safe) safe = safe.replace("..", ".")
        if (safe.startsWith(".")) safe = "_" + safe.substring(1)
        return safe.take(100)
    }

    /** downloads a result into the app's subtitle folder of the media: its file, or the error to show */
    suspend fun download(context: Context, result: Result, media: Media): Pair<File?, Problem?> = withContext(Dispatchers.IO) {
        val folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "subtitles/" + sha1(media.key).take(20))
        val release = result.release.replace(Regex("""[\\/:*?"<>|\s\p{Cc}]+"""), ".").take(80)
        val stem = "$release-${safeFileName(result.id.replace(':', '-'))}.${iso639_2(result.language)}"
        for (ext in listOf("srt", "vtt", "ass", "ssa", "sub", "smi")) File(folder, "$stem.$ext").takeIf { it.exists() }?.let { return@withContext Pair(it, null) }
        val headers = HashMap<String, String>()
        headers["User-Agent"] = userAgent
        if (result.provider == "opensubtitles") headers["X-User-Agent"] = userAgent
        if (result.provider == "yify") headers["Referer"] = "https://yifysubtitles.ch/"
        headers.putAll(KeyedSubtitles.downloadHeaders(context, result))
        val url = if (result.provider == "opensubtitlescom") {
            val (link, error) = oscomLink(context, result.url)
            if (link == null) stateOf(error)?.let { setServiceState(context, result.provider, it) }
            link ?: return@withContext Pair(null, error)
        } else result.url
        val r = KeyedSubtitles.fetch(context, result, MAX_DOWNLOAD_BYTES) ?: get(url, headers, MAX_DOWNLOAD_BYTES, 30_000)
        if (!ok(r) || r.body.isEmpty()) {
            // a key or a sign-in refused: said so, and on the service's row
            KeyedSubtitles.downloadProblem(result, r.status)?.let { p ->
                stateOf(p)?.let { setServiceState(context, result.provider, it) }
                return@withContext Pair(null, p)
            }
            return@withContext Pair(null, if (r.status == 0) Problem.Offline else Problem.Download(r.status))
        }
        val (unpacked, error) = subtitleOf(r.body, result, media.name)
        unpacked ?: return@withContext Pair(null, error)
        folder.mkdirs()
        val file = File(folder, "$stem.${unpacked.extension}")
        try {
            val tmp = File(folder, "$stem.part")
            tmp.writeBytes(unpacked.text.toByteArray(Charsets.UTF_8))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return@withContext Pair(null, Problem.Save)
            }
        } catch (e: Exception) {
            return@withContext Pair(null, Problem.Save)
        }
        Pair(file, null)
    }
}
