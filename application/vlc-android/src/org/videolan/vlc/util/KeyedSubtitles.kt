/*
 * VLC Redesign: the subtitle services asked with the user's own free key or account (OnlineSubtitles asks them with
 * the free ones): SubSource (strong in Arabic) and SubDL (the Subscene archive), their search, their answers read,
 * their errors told apart (a key refused, busy), and the check of a key (Settings › Subtitles: Test). Their shapes
 * are those of the maintained Kodi add-on a4kSubtitles (services/subsource.py, subdl.py).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.vlc.util.OnlineSubtitles.Field
import org.videolan.vlc.util.OnlineSubtitles.Problem
import org.videolan.vlc.util.OnlineSubtitles.Provider
import org.videolan.vlc.util.OnlineSubtitles.Query
import org.videolan.vlc.util.OnlineSubtitles.Response
import org.videolan.vlc.util.OnlineSubtitles.Result
import org.videolan.vlc.util.OnlineSubtitles.enc
import org.videolan.vlc.util.OnlineSubtitles.fromEnglishName
import org.videolan.vlc.util.OnlineSubtitles.normalize
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

internal object KeyedSubtitles {
    private const val REQUEST_GAP_MS = 400L

    private val userAgent get() = OnlineSubtitles.userAgent

    /** a search of one of them (the others are OnlineSubtitles' own) */
    suspend fun search(context: Context, p: Provider, q: Query): Pair<List<Result>, Problem?> = when (p.id) {
        "subsource" -> searchSubSource(context, q)
        "subdl" -> searchSubDl(context, q)
        else -> Pair(emptyList(), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Episodes in release names

    // (every bracket escaped: Android's ICU refuses a bare "]" or "}" that Java takes)
    private val releaseEpisodeRe = Regex("""(?:^|[^a-z0-9])s(\d{1,2})[\s._\-]*e(\d{1,3})(?=\D|$)|(?:^|[^a-z0-9])(\d{1,2})x(\d{1,3})(?=\D|$)""", RegexOption.IGNORE_CASE)

    /** the season and episode a release names ("S01E02", "1x02"), null when it names none */
    fun episodeOf(release: String): Pair<Int, Int>? {
        val m = releaseEpisodeRe.find(release) ?: return null
        val s = m.groupValues[1].ifEmpty { m.groupValues[3] }.toIntOrNull() ?: return null
        val e = m.groupValues[2].ifEmpty { m.groupValues[4] }.toIntOrNull() ?: return null
        return Pair(s, e)
    }

    /** a subtitle's releases name this episode, or no episode at all (the season as a whole) */
    fun forEpisode(releases: List<String>, season: Int, episode: Int): Boolean {
        if (season < 0 || episode < 0) return true
        var named = false
        for (r in releases) {
            val e = episodeOf(r) ?: continue
            named = true
            if (e.first == season && e.second == episode) return true
        }
        return !named
    }

    private fun json(body: ByteArray): JSONObject? = try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { null }

    private fun strings(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() && s != "null" } }
        null, JSONObject.NULL -> emptyList()
        else -> listOf(v.toString().trim()).filter { it.isNotEmpty() }
    }

    private fun intOf(o: JSONObject, key: String): Int = o.opt(key)?.toString()?.trim()?.toIntOrNull() ?: -1

    // ---------------------------------------------------------------------------------------------------------------
    // SubSource (api.subsource.net/api/v1, the key in X-API-Key): the title first (by its IMDb id, else its name),
    // then its subtitles in the user's languages; a download is a zip

    private const val SUBSOURCE = "https://api.subsource.net/api/v1"

    /** SubSource's names of a language (a4kSubtitles' code_langs, by the app's codes) */
    private val subSourceNames: Map<String, List<String>> = mapOf(
        "ar" to listOf("arabic"), "en" to listOf("english"), "bn" to listOf("bengali"), "id" to listOf("indonesian"),
        "ab" to listOf("abkhazian"), "af" to listOf("afrikaans"), "sq" to listOf("albanian"), "am" to listOf("amharic"),
        "an" to listOf("aragonese"), "hy" to listOf("armenian"), "as" to listOf("assamese"), "fa" to listOf("farsi_persian"),
        "ast" to listOf("asturian"), "az" to listOf("azerbaijani"), "eu" to listOf("basque"), "be" to listOf("belarusian"),
        "zh" to listOf("chinese_simplified", "chinese", "chinese_bilingual", "chinese_bg_code"),
        "zh-TW" to listOf("chinese_traditional", "big_5_code", "chinese_cantonese"),
        "bs" to listOf("bosnian"), "pt" to listOf("portuguese"), "pt-BR" to listOf("brazilian_portuguese"), "br" to listOf("breton"),
        "bg" to listOf("bulgarian"), "my" to listOf("burmese"), "ca" to listOf("catalan"), "hr" to listOf("croatian"),
        "cs" to listOf("czech"), "da" to listOf("danish"), "prs" to listOf("dari"), "nl" to listOf("dutch"), "eo" to listOf("espranto"),
        "et" to listOf("estonian"), "ext" to listOf("extremaduran"), "fil" to listOf("filipino"), "fi" to listOf("finnish"),
        "fr" to listOf("french", "french_france", "french_canada"), "gd" to listOf("gaelic"), "gl" to listOf("gaelician"),
        "ka" to listOf("georgian"), "de" to listOf("german"), "el" to listOf("greek"), "kl" to listOf("greenlandic"),
        "he" to listOf("hebrew"), "hi" to listOf("hindi"), "hu" to listOf("hungarian"), "is" to listOf("icelandic"),
        "ig" to listOf("igbo"), "ia" to listOf("interlingua"), "ga" to listOf("irish"), "it" to listOf("italian"),
        "ja" to listOf("japanese"), "kn" to listOf("kannada"), "kk" to listOf("kazakh"), "km" to listOf("khmer"),
        "ko" to listOf("korean"), "ku" to listOf("kurdish"), "ky" to listOf("kyrgyz"), "lv" to listOf("latvian"),
        "lt" to listOf("lithuanian"), "lb" to listOf("luxembourgish"), "mk" to listOf("macedonian"), "ml" to listOf("malayalam"),
        "mni" to listOf("manipuri"), "mr" to listOf("marathi"), "ms" to listOf("malay"), "mn" to listOf("mongolian"),
        "cnr" to listOf("montenegrin"), "nv" to listOf("navajo"), "ne" to listOf("nepali"), "se" to listOf("northen_sami"),
        "no" to listOf("norwegian"), "nb" to listOf("norwegian"), "oc" to listOf("occitan"), "ps" to listOf("pashto", "pushto"),
        "or" to listOf("odia"), "pl" to listOf("polish"), "ro" to listOf("romanian"), "ru" to listOf("russian"),
        "sat" to listOf("santli"), "sr" to listOf("serbian"), "sd" to listOf("sindhi"), "si" to listOf("sinhala", "sinhalese"),
        "sk" to listOf("slovak"), "sl" to listOf("slovenian"), "so" to listOf("somali"), "wen" to listOf("sorbian"),
        "es" to listOf("spanish", "spanish_spain"), "es-419" to listOf("spanish_latin_america"), "sw" to listOf("swahili"),
        "sv" to listOf("swedish"), "syl" to listOf("sylheti"), "syr" to listOf("syriac"), "tl" to listOf("tagalog"),
        "ta" to listOf("tamil"), "tt" to listOf("tatar"), "te" to listOf("telugu"), "tet" to listOf("tetum"), "th" to listOf("thai"),
        "tok" to listOf("toki_pona"), "tr" to listOf("turkish"), "tk" to listOf("turkmen"), "uk" to listOf("ukrainian"),
        "ur" to listOf("urdu"), "uz" to listOf("uzbek"), "vi" to listOf("vietnamese"), "cy" to listOf("welsh")
    )
    private val fromSubSourceNames: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for ((code, names) in subSourceNames) for (n in names) if (n !in m) m[n] = code
        m
    }

    /** the names SubSource has for one of the app's languages ("pt-BR" -> brazilian_portuguese) */
    fun subSourceLanguages(code: String): List<String> = subSourceNames[code]
        ?: subSourceNames[code.substringBefore('-')]
        ?: listOf(Locale(code.substringBefore('-')).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT).replace(' ', '_')).filter { it.length > 2 }

    /** "farsi_persian", "brazilian_portuguese", "Arabic" -> "fa", "pt-BR", "ar" */
    fun fromSubSource(name: String): String {
        val n = name.trim().lowercase(Locale.ROOT)
        if (n.isEmpty()) return ""
        return fromSubSourceNames[n] ?: fromEnglishName(n.replace('_', ' '))
    }

    /** what went wrong in an answer of SubSource (null: none; a 200 that says it has nothing is no error) */
    fun subSourceProblem(r: Response): Problem? = when {
        r.status == 0 || r.error != null -> Problem.NoAnswer("SubSource")
        r.status == 401 || r.status == 403 -> Problem.KeyRefused("SubSource")
        r.status == 429 -> Problem.Busy("SubSource")
        r.status == 404 -> null
        r.status != 200 -> Problem.Status("SubSource", r.status)
        else -> null
    }

    /** the title of /movies/search: a film's entry of its IMDb id (else the first), a series' entry of the season */
    fun subSourceMovie(json: String, q: Query): Long? {
        val data = try { JSONObject(json).optJSONArray("data") } catch (e: Exception) { null } ?: return null
        var first: Long? = null
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.opt("movieId")?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: continue
            if (q.series) {
                if (q.season < 0) return id
                if (intOf(o, "season") == q.season) return id
                continue
            }
            if (first == null) first = id
            if (q.imdb.startsWith("tt") && o.optString("imdbId") == q.imdb) return id
        }
        return first
    }

    fun parseSubSource(json: String, q: Query): List<Result> {
        val data = try { JSONObject(json).optJSONArray("data") } catch (e: Exception) { null } ?: return emptyList()
        val list = ArrayList<Result>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.opt("subtitleId")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } } ?: continue
            val language = fromSubSource(o.optString("language")).ifEmpty { continue }
            val releases = strings(o.opt("releaseInfo"))
            if (q.series && !forEpisode(releases, q.season, q.episode)) continue
            val rating = o.optJSONObject("rating")?.optInt("total") ?: o.optInt("rating")
            list.add(Result(
                id = "subsource:$id", provider = "subsource", language = language, release = releases.firstOrNull() ?: "",
                url = "$SUBSOURCE/subtitles/$id/download", hearingImpaired = o.optBoolean("hearingImpaired"),
                rank = log10(max(rating, 0) + 1.0) * 3
            ))
        }
        return list
    }

    private fun subSourceHeaders(key: String) = mapOf("X-API-Key" to key, "User-Agent" to userAgent, "Accept" to "application/json")

    private suspend fun searchSubSource(context: Context, q: Query): Pair<List<Result>, Problem?> {
        val key = OnlineSubtitles.credential(context, "subsource", Field.KEY)
        if (key.isEmpty()) return Pair(emptyList(), null)
        val headers = subSourceHeaders(key)
        val searches = ArrayList<String>()
        if (q.imdb.startsWith("tt")) searches.add("$SUBSOURCE/movies/search?searchType=imdb&imdb=${q.imdb}")
        if (q.title.isNotEmpty()) searches.add("$SUBSOURCE/movies/search?searchType=text&q=${enc(q.title)}" +
            (if (q.year > 0) "&year=${q.year}" else "") + "&type=" + (if (q.series) "tvseries" else "movie"))
        var movieId: Long? = null
        for ((n, url) in searches.withIndex()) {
            if (n > 0) delay(REQUEST_GAP_MS)
            val r = OnlineSubtitles.get(url, headers)
            subSourceProblem(r)?.let { return Pair(emptyList(), it) }
            movieId = subSourceMovie(String(r.body, Charsets.UTF_8), q)
            if (movieId != null) break
        }
        movieId ?: return Pair(emptyList(), null)
        val names = q.languages.flatMap { subSourceLanguages(it) }.distinct()
        delay(REQUEST_GAP_MS)
        val r = OnlineSubtitles.get("$SUBSOURCE/subtitles?movieId=$movieId" + (if (names.isNotEmpty()) "&language=" + enc(names.joinToString(",")) else ""), headers)
        subSourceProblem(r)?.let { return Pair(emptyList(), it) }
        return Pair(parseSubSource(String(r.body, Charsets.UTF_8), q), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // SubDL (api.subdl.com): v2 with the key as a bearer token, v1 (the key in the address) when v2 answers 404;
    // a download is a zip of dl.subdl.com

    private const val SUBDL_V2 = "https://api.subdl.com/api/v2/subtitles/search"
    private const val SUBDL_V1 = "https://api.subdl.com/api/v1/subtitles"

    /** SubDL's code of a language: ISO 639-1 for v2, its own upper case codes for v1 ("BR_PT", "ZH_BG") */
    fun subDlCode(code: String, v1: Boolean): String = when (code) {
        "pt-BR" -> if (v1) "BR_PT" else "pt"
        "zh-TW" -> if (v1) "ZH_BG" else "zh"
        else -> code.substringBefore('-').let { if (v1) it.uppercase(Locale.ROOT) else it }
    }

    /** a language of SubDL's answers: "language" or "lang", a code ("AR", "BR_PT") or a name ("arabic") */
    fun fromSubDl(vararg values: String): String {
        for (raw in values) {
            val v = raw.trim()
            if (v.isEmpty()) continue
            when (v.uppercase(Locale.ROOT)) {
                "BR_PT", "PT_BR", "PT-BR" -> return "pt-BR"
                "ZH_BG", "ZH_TW", "ZH-TW" -> return "zh-TW"
            }
            val code = if (v.length <= 3 && v.all { it.isLetter() }) normalize(v.lowercase(Locale.ROOT)) else fromEnglishName(v.replace('_', ' ').replace('-', ' '))
            if (code.isNotEmpty()) return code
        }
        return ""
    }

    /** an "error" in SubDL's answer: its key refused, busy, or (anything else) nothing found */
    private fun subDlError(o: JSONObject): Problem? {
        val e = o.opt("error") ?: return null
        val text = ((e as? JSONObject)?.let { it.optString("code") + " " + it.optString("message") } ?: e.toString()).lowercase(Locale.ROOT)
        return when {
            "auth" in text || "api key" in text || "api_key" in text || "token" in text -> Problem.KeyRefused("SubDL")
            "limit" in text || "too many" in text -> Problem.Busy("SubDL")
            else -> null
        }
    }

    fun subDlProblem(r: Response): Problem? {
        if (r.status == 0 || r.error != null) return Problem.NoAnswer("SubDL")
        if (r.status == 401 || r.status == 403) return Problem.KeyRefused("SubDL")
        if (r.status == 429) return Problem.Busy("SubDL")
        json(r.body)?.let { o -> subDlError(o)?.let { return it } }
        if (r.status == 404 || r.status == 400) return null
        return if (r.status != 200) Problem.Status("SubDL", r.status) else null
    }

    fun parseSubDl(json: String, q: Query): List<Result> {
        val o = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        if (o.has("error") || !o.optBoolean("status", true)) return emptyList()
        val items = o.optJSONArray("subtitles") ?: return emptyList()
        val list = ArrayList<Result>()
        val seen = HashSet<String>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            val url = s.optString("url")
            if (!url.startsWith("/") || ".." in url || !seen.add(url)) continue
            val language = fromSubDl(s.optString("language"), s.optString("lang")).ifEmpty { continue }
            val release = s.optString("release_name").ifEmpty { s.optString("name") }.trim()
            if (q.series && q.season >= 0 && q.episode >= 0) {
                val season = intOf(s, "season")
                val episode = intOf(s, "episode")
                if (season > 0 && season != q.season) continue
                if (!s.optBoolean("full_season") && episode > 0 && episode != q.episode) continue
                if (episode <= 0 && !s.optBoolean("full_season") && !forEpisode(listOf(release), q.season, q.episode)) continue
            }
            val downloads = max(s.optInt("downloads"), 0)
            list.add(Result(
                id = "subdl:" + url.substringAfterLast('/').substringBeforeLast('.'), provider = "subdl", language = language,
                release = release, url = "https://dl.subdl.com$url", hearingImpaired = s.optBoolean("hi"), downloads = downloads,
                rank = log10(downloads + 1.0) * 3
            ))
        }
        return list
    }

    private suspend fun searchSubDl(context: Context, q: Query): Pair<List<Result>, Problem?> {
        val key = OnlineSubtitles.credential(context, "subdl", Field.KEY)
        if (key.isEmpty() || (!q.imdb.startsWith("tt") && q.title.isEmpty())) return Pair(emptyList(), null)
        val episode = q.series && q.season >= 0 && q.episode >= 0
        val p2 = ArrayList<String>()
        if (q.series) {
            if (q.title.isEmpty()) return Pair(emptyList(), null)
            p2 += listOf("type=tv", "film_name=${enc(q.title)}")
            if (episode) p2 += listOf("season=${q.season}", "episode=${q.episode}")
            if (q.fileName.isNotEmpty()) p2 += "file_name=${enc(q.fileName.substringBeforeLast('.'))}"
        } else {
            p2 += "type=movie"
            p2 += if (q.imdb.startsWith("tt")) "imdb_id=${q.imdb}" else "film_name=${enc(q.title)}"
            if (q.year > 0) p2 += "year=${q.year}"
        }
        val codes = q.languages.map { subDlCode(it, false) }.filter { it.isNotEmpty() }.distinct()
        if (codes.isNotEmpty()) p2 += "languages=${codes.joinToString(",")}"
        var r = OnlineSubtitles.get("$SUBDL_V2?" + p2.joinToString("&"), mapOf("Authorization" to "Bearer $key", "User-Agent" to userAgent, "Accept" to "application/json"))
        if (r.error == null && r.status == 404 && subDlError(json(r.body) ?: JSONObject()) == null) {
            // v2 not there (any more, or yet): v1
            val p1 = ArrayList<String>()
            p1 += "api_key=${enc(key)}"
            p1 += if (q.imdb.startsWith("tt")) "imdb_id=${q.imdb}" else "film_name=${enc(q.title)}"
            p1 += "type=" + if (q.series) "tv" else "movie"
            if (episode) p1 += listOf("season_number=${q.season}", "episode_number=${q.episode}")
            val v1 = q.languages.map { subDlCode(it, true) }.filter { it.isNotEmpty() }.distinct()
            if (v1.isNotEmpty()) p1 += "languages=${v1.joinToString(",")}"
            p1 += "subs_per_page=30"
            delay(REQUEST_GAP_MS)
            r = OnlineSubtitles.get("$SUBDL_V1?" + p1.joinToString("&"), mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
        }
        subDlProblem(r)?.let { return Pair(emptyList(), it) }
        return Pair(parseSubDl(String(r.body, Charsets.UTF_8), q), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Downloads

    /** what a download of theirs needs (SubSource: the key) */
    fun downloadHeaders(context: Context, r: Result): Map<String, String> = when (r.provider) {
        "subsource" -> mapOf("X-API-Key" to OnlineSubtitles.credential(context, "subsource", Field.KEY))
        else -> emptyMap()
    }

    /** a download refused: the key (or the sign-in) of the service, to say so and show it on its row */
    fun downloadProblem(r: Result, status: Int): Problem? {
        val p = OnlineSubtitles.provider(r.provider) ?: return null
        if (!p.keyed || (status != 401 && status != 403)) return if (status == 429) Problem.Busy(p.name) else null
        return if (p.login && Field.KEY !in p.fields) Problem.LoginRefused(p.name) else Problem.KeyRefused(p.name)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Test / Sign in (Settings › Subtitles): one cheap request with what the user gave

    enum class Check { KEY_WORKS, SIGNED_IN, KEY_REFUSED, LOGIN_REFUSED, NO_ANSWER, BUSY }

    class CheckResult(val check: Check, val user: String = "")

    /** a key check's answer: works, refused, busy, no answer */
    fun keyCheckOf(id: String, r: Response): Check {
        val problem = when (id) {
            "subsource" -> subSourceProblem(r)
            "subdl" -> subDlProblem(r)
            else -> when {
                r.status == 0 || r.error != null -> Problem.NoAnswer(id)
                r.status == 401 || r.status == 403 -> Problem.KeyRefused(id)
                r.status == 429 -> Problem.Busy(id)
                r.status != 200 -> Problem.Status(id, r.status)
                else -> null
            }
        }
        return when (problem) {
            null -> if (r.status in 200..499) Check.KEY_WORKS else Check.NO_ANSWER
            is Problem.KeyRefused -> Check.KEY_REFUSED
            is Problem.LoginRefused -> Check.LOGIN_REFUSED
            is Problem.Busy -> Check.BUSY
            else -> Check.NO_ANSWER
        }
    }

    private fun checkOscom(context: Context): CheckResult {
        val key = OnlineSubtitles.oscomKey(context)
        val user = OnlineSubtitles.credential(context, "opensubtitlescom", Field.USERNAME)
        if (user.isNotEmpty() && OnlineSubtitles.credential(context, "opensubtitlescom", Field.PASSWORD).isNotEmpty()) {
            val login = OnlineSubtitles.oscomSignIn(context, force = true)
            return when {
                login.token != null -> CheckResult(Check.SIGNED_IN, user)
                login.problem is Problem.KeyRefused -> CheckResult(Check.KEY_REFUSED)
                login.problem is Problem.LoginRefused || login.problem == Problem.LoginFailed -> CheckResult(Check.LOGIN_REFUSED)
                login.problem is Problem.Busy -> CheckResult(Check.BUSY)
                else -> CheckResult(Check.NO_ANSWER)
            }
        }
        val r = OnlineSubtitles.get("https://api.opensubtitles.com/api/v1/subtitles?imdb_id=133093&languages=en",
            mapOf("Api-Key" to key, "User-Agent" to "VLC-Redesign v${org.videolan.vlc.BuildConfig.RD_VERSION_NAME}", "Accept" to "application/json"))
        return CheckResult(keyCheckOf("opensubtitlescom", r))
    }

    /** checks the service's key or account; a refusal shows on its row, a success clears it and (the first time)
     *  turns the service on */
    suspend fun check(context: Context, id: String): CheckResult = withContext(Dispatchers.IO) {
        val result = when (id) {
            "opensubtitlescom" -> checkOscom(context)
            "subsource" -> CheckResult(keyCheckOf(id, OnlineSubtitles.get("$SUBSOURCE/movies/search?searchType=imdb&imdb=tt0133093",
                subSourceHeaders(OnlineSubtitles.credential(context, id, Field.KEY)))))
            "subdl" -> CheckResult(keyCheckOf(id, OnlineSubtitles.get("$SUBDL_V2?type=movie&imdb_id=tt0133093&languages=en",
                mapOf("Authorization" to "Bearer " + OnlineSubtitles.credential(context, id, Field.KEY), "User-Agent" to userAgent, "Accept" to "application/json"))))
            else -> CheckResult(Check.NO_ANSWER)
        }
        when (result.check) {
            Check.KEY_WORKS, Check.SIGNED_IN -> {
                OnlineSubtitles.setServiceState(context, id, null)
                OnlineSubtitles.onCredentialsWork(context, id)
            }
            Check.KEY_REFUSED -> OnlineSubtitles.setServiceState(context, id, OnlineSubtitles.ServiceState.KEY_REFUSED)
            Check.LOGIN_REFUSED -> OnlineSubtitles.setServiceState(context, id, OnlineSubtitles.ServiceState.LOGIN_REFUSED)
            Check.BUSY -> OnlineSubtitles.setServiceState(context, id, OnlineSubtitles.ServiceState.BUSY)
            Check.NO_ANSWER -> {}
        }
        result
    }
}
