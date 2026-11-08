/*
 * VLC Redesign: the subtitle services asked with the user's own free key or account (OnlineSubtitles asks them with
 * the free ones): SubSource (strong in Arabic), SubDL (the Subscene archive), Titlovi (the Balkan languages), Assrt
 * (Chinese), Jimaku (anime in Japanese), BetaSeries (series in French), Subs.ro (Romanian), Napisy24 (Polish, by the
 * file's hash) and Legendas.net (Portuguese of Brazil): their search, their answers read, their errors told apart (a
 * key or a sign-in refused, busy), and the check of a key or an account (Settings › Subtitles: Test, Sign in).
 * SubSource's and SubDL's shapes are those of the maintained Kodi add-on a4kSubtitles (services/subsource.py,
 * subdl.py); the others', those of Bazarr's providers (custom_libs/subliminal_patch/providers).
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
        "titlovi" -> searchTitlovi(context, q)
        "assrt" -> searchAssrt(context, q)
        "jimaku" -> searchJimaku(context, q)
        "betaseries" -> searchBetaSeries(context, q)
        "subsro" -> searchSubsRo(context, q)
        "napisy24" -> searchNapisy24(context, q)
        "legendasnet" -> searchLegendas(context, q)
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
    /** the languages SubSource knows (the app's codes): Add a language lists them */
    val languageCodes: Set<String> get() = subSourceNames.keys

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
    // Titlovi (kodi.titlovi.com, the Balkan languages): signs in for a token (kept until it expires), then searches
    // by the title and the IMDb id; a download is a zip (or a RAR, which can't be opened here)

    private const val TITLOVI = "https://kodi.titlovi.com/api/subtitles"

    private val titloviNames = linkedMapOf("hr" to "Hrvatski", "sr" to "Srpski", "bs" to "Bosanski", "sl" to "Slovenski", "mk" to "Makedonski", "en" to "English")

    fun fromTitlovi(name: String): String = when (name.trim()) {
        "Cirilica" -> "sr"
        else -> titloviNames.entries.firstOrNull { it.value.equals(name.trim(), true) }?.key ?: ""
    }

    /** the token of /gettoken: (token, user id, until), or the problem */
    fun titloviLoginOf(r: Response, now: Long = System.currentTimeMillis()): Pair<Triple<String, String, Long>?, Problem?> {
        if (r.status == 0 || r.error != null) return Pair(null, Problem.NoAnswer("Titlovi"))
        if (r.status == 401 || r.status == 403) return Pair(null, Problem.LoginRefused("Titlovi"))
        if (r.status == 429) return Pair(null, Problem.Busy("Titlovi"))
        if (r.status != 200) return Pair(null, Problem.Status("Titlovi", r.status))
        val o = json(r.body) ?: return Pair(null, Problem.LoginRefused("Titlovi"))
        val token = o.optString("Token").ifEmpty { return Pair(null, Problem.LoginRefused("Titlovi")) }
        val user = o.opt("UserId")?.toString() ?: ""
        val until = isoTime(o.optString("ExpirationDate")).takeIf { it > now } ?: (now + 6 * 3600_000L)
        return Pair(Triple(token, user, until), null)
    }

    /** "2026-11-15T12:00:00.123+01:00" -> its time (0 when it isn't one) */
    fun isoTime(text: String): Long = try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.parse(text.take(19))?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    fun parseTitlovi(json: String, q: Query): List<Result> {
        val items = try { JSONObject(json).optJSONArray("SubtitleResults") } catch (e: Exception) { null } ?: return emptyList()
        val list = ArrayList<Result>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.opt("Id")?.toString()?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } } ?: continue
            val link = o.optString("Link")
            if (!link.startsWith("https://") && !link.startsWith("http://")) continue
            val language = fromTitlovi(o.optString("Lang")).ifEmpty { continue }
            if (q.series && q.season >= 0 && q.episode >= 0) {
                if (intOf(o, "Season") != q.season) continue
                val e = intOf(o, "Episode")
                if (e > 0 && e != q.episode) continue
            }
            val downloads = max(o.optInt("DownloadCount"), 0)
            list.add(Result("titlovi:$id", "titlovi", language, o.optString("Release").ifEmpty { o.optString("Title") }.trim(),
                url = link.replaceFirst("http://", "https://"), downloads = downloads,
                rank = log10(downloads + 1.0) * 3 + o.optDouble("Rating", 0.0).let { if (it.isNaN()) 0.0 else it }))
        }
        return list
    }

    private fun titloviToken(context: Context, force: Boolean): Pair<Pair<String, String>?, Problem?> {
        val user = OnlineSubtitles.credential(context, "titlovi", Field.USERNAME)
        val password = OnlineSubtitles.credential(context, "titlovi", Field.PASSWORD)
        if (user.isEmpty() || password.isEmpty()) return Pair(null, null)
        if (!force) SubtitleSecrets.token(context, "titlovi", user)?.let { return Pair(Pair(it.substringBefore('|'), it.substringAfter('|')), null) }
        val r = OnlineSubtitles.postBody("$TITLOVI/gettoken?username=${enc(user)}&password=${enc(password)}&json=True",
            mapOf("User-Agent" to userAgent, "Accept" to "application/json"), ByteArray(0), null)
        val (login, problem) = titloviLoginOf(r)
        login ?: return Pair(null, problem)
        SubtitleSecrets.setToken(context, "titlovi", user, login.first + "|" + login.second, login.third)
        return Pair(Pair(login.first, login.second), null)
    }

    private suspend fun searchTitlovi(context: Context, q: Query): Pair<List<Result>, Problem?> {
        if (q.title.isEmpty()) return Pair(emptyList(), null)
        val langs = q.languages.mapNotNull { titloviNames[it.substringBefore('-')] }.distinct()
        if (langs.isEmpty()) return Pair(emptyList(), null)
        for (attempt in 0..1) {
            val (token, problem) = titloviToken(context, force = attempt > 0)
            token ?: return Pair(emptyList(), problem)
            val params = ArrayList<String>()
            params += "query=${enc(q.title)}"
            params += "lang=${enc(langs.joinToString("|"))}"
            if (q.series && q.season >= 0 && q.episode >= 0) params += "season=${q.season}"
            if (q.imdb.startsWith("tt")) params += "imdbID=${q.imdb}"
            params += listOf("token=${enc(token.first)}", "userid=${enc(token.second)}", "json=True")
            val r = OnlineSubtitles.get("$TITLOVI/search?" + params.joinToString("&"), mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
            // (a token gone stale: a new one, once)
            if ((r.status == 401 || r.status == 403) && attempt == 0) { delay(REQUEST_GAP_MS); continue }
            if (r.status == 401 || r.status == 403) return Pair(emptyList(), Problem.LoginRefused("Titlovi"))
            if (r.status == 429) return Pair(emptyList(), Problem.Busy("Titlovi"))
            if (!OnlineSubtitles.ok(r)) return Pair(emptyList(), OnlineSubtitles.failure("Titlovi", r))
            return Pair(parseTitlovi(String(r.body, Charsets.UTF_8), q), null)
        }
        return Pair(emptyList(), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Assrt (api.assrt.net, Chinese): the user's token in the address; the search gives the subtitles, their files
    // are listed by /sub/detail when one is downloaded

    private const val ASSRT = "https://api.assrt.net/v1"

    /** Assrt's own status in an answer: 0 is no error */
    fun assrtProblem(r: Response): Problem? {
        if (r.status == 0 || r.error != null) return Problem.NoAnswer("Assrt")
        if (r.status == 429) return Problem.Busy("Assrt")
        val o = json(r.body)
        val status = o?.optInt("status", if (r.status == 200) 0 else -1) ?: if (r.status == 200) 0 else -1
        return when (status) {
            0 -> if (r.status in 200..299) null else Problem.Status("Assrt", r.status)
            20000, 20001, 20002 -> Problem.KeyRefused("Assrt")
            30900 -> Problem.Busy("Assrt")
            // (a keyword too short, nothing found)
            101, 20400 -> null
            else -> if (r.status == 401 || r.status == 403) Problem.KeyRefused("Assrt") else Problem.Status("Assrt", if (status > 0) status else r.status)
        }
    }

    /** the languages of an Assrt subtitle ("langchs", "langcht", "langeng") */
    fun assrtLanguages(langlist: JSONObject?): List<String> {
        val list = ArrayList<String>()
        for (k in langlist?.keys()?.asSequence()?.toList() ?: emptyList()) {
            if (!langlist!!.optBoolean(k, true)) continue
            when (k.removePrefix("lang")) {
                "chs", "chn" -> "zh"
                "cht", "twn" -> "zh-TW"
                "eng" -> "en"
                else -> null
            }?.let { if (it !in list) list.add(it) }
        }
        return list
    }

    fun parseAssrt(json: String, q: Query): List<Result> {
        val subs = try { JSONObject(json).optJSONObject("sub")?.optJSONArray("subs") } catch (e: Exception) { null } ?: return emptyList()
        val list = ArrayList<Result>()
        for (i in 0 until subs.length()) {
            val o = subs.optJSONObject(i) ?: continue
            val id = o.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } } ?: continue
            val languages = assrtLanguages(o.optJSONObject("lang")?.optJSONObject("langlist"))
            // a Chinese one (with English as well, or not): Chinese, the traditional script when only that
            val language = when {
                "zh" in languages -> "zh"
                "zh-TW" in languages -> "zh-TW"
                else -> continue
            }
            var name = o.optString("videoname").trim()
            if (name.isEmpty() || name == "不知道") name = o.opt("native_name")?.let { if (it is JSONArray) it.optString(0) else it.toString() }?.trim() ?: ""
            if (q.series && !forEpisode(listOf(name), q.season, q.episode)) continue
            list.add(Result("assrt:$id", "assrt", language, name, url = "assrt:$id",
                rank = o.optDouble("vote_score", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 10.0) }))
        }
        return list
    }

    /** the file of /sub/detail to download: the episode's, a Chinese one, the first; the archive when none listed */
    fun assrtFile(json: String, season: Int, episode: Int): String? {
        val sub = try { JSONObject(json).optJSONObject("sub")?.optJSONArray("subs")?.optJSONObject(0) } catch (e: Exception) { null } ?: return null
        val files = sub.optJSONArray("filelist")
        if (files == null || files.length() == 0) return sub.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val list = (0 until files.length()).mapNotNull { files.optJSONObject(it) }.filter { it.optString("url").startsWith("http") }
        val ofEpisode = if (season >= 0 && episode >= 0) list.filter { episodeOf(it.optString("f")) == Pair(season, episode) } else emptyList()
        val pool = ofEpisode.ifEmpty { list }
        val chinese = pool.firstOrNull { f -> listOf("chs", "简", "cht", "繁", "chn").any { it in f.optString("f").lowercase(Locale.ROOT) } }
        return (chinese ?: pool.firstOrNull())?.optString("url")
    }

    private suspend fun searchAssrt(context: Context, q: Query): Pair<List<Result>, Problem?> {
        val token = OnlineSubtitles.credential(context, "assrt", Field.KEY)
        if (token.isEmpty() || q.title.isEmpty()) return Pair(emptyList(), null)
        val words = if (q.series && q.season >= 0 && q.episode >= 0) "%s S%02dE%02d".format(Locale.ROOT, q.title, q.season, q.episode)
        else if (q.year > 0) "${q.title} ${q.year}" else q.title
        val r = OnlineSubtitles.get("$ASSRT/sub/search?token=${enc(token)}&q=${enc(words)}&is_file=1", mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
        assrtProblem(r)?.let { return Pair(emptyList(), it) }
        return Pair(parseAssrt(String(r.body, Charsets.UTF_8), q), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Jimaku (jimaku.cc, anime in Japanese): the key in Authorization; the entry of the title, then its files

    private const val JIMAKU = "https://jimaku.cc/api"

    fun jimakuProblem(r: Response): Problem? = when {
        r.status == 0 || r.error != null -> Problem.NoAnswer("Jimaku")
        r.status == 401 || r.status == 403 -> Problem.KeyRefused("Jimaku")
        r.status == 429 -> Problem.Busy("Jimaku")
        r.status == 404 -> null
        r.status != 200 -> Problem.Status("Jimaku", r.status)
        else -> null
    }

    /** the entry of /entries/search named most like the title (its English or its own name) */
    fun jimakuEntry(json: String, q: Query): Long? {
        val items = try { JSONArray(json) } catch (e: Exception) { return null }
        var best: Long? = null
        var bestScore = 0.0
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val id = o.opt("id")?.toString()?.toLongOrNull() ?: continue
            val movie = o.optJSONObject("flags")?.optBoolean("movie") == true
            var score = listOf("english_name", "name", "japanese_name").maxOf { OnlineSubtitles.titleSimilarity(o.optString(it), q.title) }
            if (score < 0.6) continue
            if (movie == !q.series) score += 0.1
            if (score > bestScore) { bestScore = score; best = id }
        }
        return best
    }

    private val whisperRe = Regex("""whisper""", RegexOption.IGNORE_CASE)

    fun parseJimaku(json: String, entry: Long): List<Result> {
        val items = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val list = ArrayList<Result>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val url = o.optString("url")
            if (name.isEmpty() || !url.startsWith("https://")) continue
            val lower = name.lowercase(Locale.ROOT)
            // (7z can't be opened here; a file that small is broken; made by a machine)
            if (lower.endsWith(".7z") || o.optLong("size", 1000L) < 500 || whisperRe.containsMatchIn(name)) continue
            list.add(Result("jimaku:$entry:${OnlineSubtitles.shortHash(url)}", "jimaku", "ja", name.substringBeforeLast('.'), fileName = name, url = url))
        }
        return list
    }

    private suspend fun searchJimaku(context: Context, q: Query): Pair<List<Result>, Problem?> {
        val key = OnlineSubtitles.credential(context, "jimaku", Field.KEY)
        if (key.isEmpty() || q.title.isEmpty()) return Pair(emptyList(), null)
        val headers = mapOf("Authorization" to key, "User-Agent" to userAgent, "Accept" to "application/json")
        val r = OnlineSubtitles.get("$JIMAKU/entries/search?query=${enc(q.title.lowercase(Locale.ROOT))}", headers)
        jimakuProblem(r)?.let { return Pair(emptyList(), it) }
        val entry = jimakuEntry(String(r.body, Charsets.UTF_8), q) ?: return Pair(emptyList(), null)
        delay(REQUEST_GAP_MS)
        val files = OnlineSubtitles.get("$JIMAKU/entries/$entry/files" + (if (q.series && q.episode > 0) "?episode=${q.episode}" else ""), headers)
        jimakuProblem(files)?.let { return Pair(emptyList(), it) }
        return Pair(parseJimaku(String(files.body, Charsets.UTF_8), entry), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // BetaSeries (api.betaseries.com, series in French): the show of the IMDb id, then the episode's subtitles

    private const val BETASERIES = "https://api.betaseries.com"

    /** its errors: 1001 the key, 4001 nothing of that id */
    fun betaSeriesProblem(r: Response): Problem? {
        if (r.status == 0 || r.error != null) return Problem.NoAnswer("BetaSeries")
        if (r.status == 429) return Problem.Busy("BetaSeries")
        val code = json(r.body)?.optJSONArray("errors")?.optJSONObject(0)?.optInt("code") ?: 0
        return when {
            code == 1001 || r.status == 401 || r.status == 403 -> Problem.KeyRefused("BetaSeries")
            code == 4001 || r.status == 404 -> null
            r.status != 200 -> Problem.Status("BetaSeries", r.status)
            else -> null
        }
    }

    fun betaSeriesShow(json: String): Long? = try { JSONObject(json).optJSONObject("show")?.opt("id")?.toString()?.toLongOrNull() } catch (e: Exception) { null }

    fun parseBetaSeries(json: String): List<Result> {
        val o = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        val subs = o.optJSONObject("episode")?.optJSONArray("subtitles") ?: o.optJSONArray("episodes")?.optJSONObject(0)?.optJSONArray("subtitles") ?: return emptyList()
        val list = ArrayList<Result>()
        for (i in 0 until subs.length()) {
            val s = subs.optJSONObject(i) ?: continue
            val id = s.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } } ?: continue
            val language = when (s.optString("language").uppercase(Locale.ROOT)) { "VF" -> "fr"; "VO" -> "en"; else -> continue }
            // (seriessub's links don't download, as Bazarr found)
            if (s.optString("source") == "seriessub") continue
            val url = s.optString("url")
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            list.add(Result("betaseries:$id", "betaseries", language, s.optString("file").substringBeforeLast('.'), fileName = s.optString("file"),
                url = url.replaceFirst("http://", "https://"), rank = s.optDouble("quality", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 5.0) }))
        }
        return list
    }

    private suspend fun searchBetaSeries(context: Context, q: Query): Pair<List<Result>, Problem?> {
        val key = OnlineSubtitles.credential(context, "betaseries", Field.KEY)
        if (key.isEmpty() || !q.series || !q.imdb.startsWith("tt") || q.season < 0 || q.episode < 0) return Pair(emptyList(), null)
        val headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json", "X-BetaSeries-Version" to "3.0")
        val show = OnlineSubtitles.get("$BETASERIES/shows/display?key=${enc(key)}&imdb_id=${q.imdb}&v=3.0", headers)
        betaSeriesProblem(show)?.let { return Pair(emptyList(), it) }
        val id = betaSeriesShow(String(show.body, Charsets.UTF_8)) ?: return Pair(emptyList(), null)
        delay(REQUEST_GAP_MS)
        val r = OnlineSubtitles.get("$BETASERIES/shows/episodes?key=${enc(key)}&id=$id&season=${q.season}&episode=${q.episode}&subtitles=1&v=3.0", headers)
        betaSeriesProblem(r)?.let { return Pair(emptyList(), it) }
        return Pair(parseBetaSeries(String(r.body, Charsets.UTF_8)), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Subs.ro (api.subs.ro, Romanian): the key in X-Subs-Api-Key; by the IMDb id (a series': its own)

    private const val SUBSRO = "https://api.subs.ro/v1.0"

    fun subsRoProblem(r: Response): Problem? = when {
        r.status == 0 || r.error != null -> Problem.NoAnswer("Subs.ro")
        r.status == 401 || r.status == 403 -> Problem.KeyRefused("Subs.ro")
        r.status == 429 -> Problem.Busy("Subs.ro")
        r.status == 404 -> null
        r.status != 200 -> Problem.Status("Subs.ro", r.status)
        else -> null
    }

    private val sezonRe = Regex("""[Ss]ezon(?:ul)?\s*(\d{1,2})""")

    fun parseSubsRo(json: String, q: Query): List<Result> {
        val o = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        if (o.optInt("status", 200) != 200) return emptyList()
        val items = o.optJSONArray("items") ?: return emptyList()
        val list = ArrayList<Result>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            val id = s.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } } ?: continue
            val link = s.optString("downloadLink")
            if (!link.startsWith("https://")) continue
            val title = s.optString("title")
            val description = s.optString("description")
            if (q.series && q.season >= 0) {
                val season = sezonRe.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: sezonRe.find(description)?.groupValues?.get(1)?.toIntOrNull()
                    ?: episodeOf(description)?.first
                if (season != null && season != q.season) continue
                if (!forEpisode(listOf(description), q.season, q.episode)) continue
            }
            list.add(Result("subsro:$id", "subsro", if (s.optString("language") == "en") "en" else "ro",
                description.lineSequence().firstOrNull()?.trim()?.ifEmpty { null } ?: title, url = link))
        }
        return list
    }

    private fun searchSubsRo(context: Context, q: Query): Pair<List<Result>, Problem?> {
        val key = OnlineSubtitles.credential(context, "subsro", Field.KEY)
        if (key.isEmpty() || !q.imdb.startsWith("tt")) return Pair(emptyList(), null)
        val r = OnlineSubtitles.get("$SUBSRO/search/imdbid/${q.imdb.substring(2)}?language=ro", mapOf("X-Subs-Api-Key" to key, "User-Agent" to userAgent, "Accept" to "application/json"))
        subsRoProblem(r)?.let { return Pair(emptyList(), it) }
        return Pair(parseSubsRo(String(r.body, Charsets.UTF_8), q), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Napisy24 (napisy24.pl, Polish): the user's account and the file's hash (OpenSubtitles' kind) in one POST; the
    // answer is a status line ("OK-2|imdb:…|napisId:…") and the zip of the subtitle made for this very file

    private const val NAPISY24 = "https://napisy24.pl/run/CheckSubAgent.php"

    class Napisy24Answer(val status: String, val info: Map<String, String>, val zip: ByteArray?, val problem: Problem?)

    fun napisy24Of(r: Response): Napisy24Answer {
        if (r.status == 0 || r.error != null) return Napisy24Answer("", emptyMap(), null, Problem.NoAnswer("Napisy24"))
        if (r.status != 200) return Napisy24Answer("", emptyMap(), null, if (r.status == 429) Problem.Busy("Napisy24") else Problem.Status("Napisy24", r.status))
        val body = r.body
        var cut = -1
        for (i in 0 until body.size - 1) if (body[i] == '|'.code.toByte() && body[i + 1] == '|'.code.toByte()) { cut = i; break }
        val head = String(body, 0, if (cut >= 0) cut else body.size, Charsets.UTF_8).trim()
        if (head.startsWith("login error", true)) return Napisy24Answer("", emptyMap(), null, Problem.LoginRefused("Napisy24"))
        if (!head.startsWith("OK")) return Napisy24Answer("", emptyMap(), null, Problem.Status("Napisy24", 200))
        val parts = head.split('|')
        val info = parts.drop(1).mapNotNull { p -> p.indexOf(':').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
        val zip = if (cut >= 0 && parts[0].startsWith("OK-2")) body.copyOfRange(cut + 2, body.size) else null
        return Napisy24Answer(parts[0].take(4), info, zip, null)
    }

    private fun napisy24Request(context: Context, hash: String, size: Long, fileName: String): Response {
        val user = OnlineSubtitles.credential(context, "napisy24", Field.USERNAME)
        val password = OnlineSubtitles.credential(context, "napisy24", Field.PASSWORD)
        return OnlineSubtitles.postBody(NAPISY24, mapOf("User-Agent" to userAgent),
            OnlineSubtitles.form(linkedMapOf("postAction" to "CheckSub", "ua" to user, "ap" to password, "fs" to size.toString(),
                "fh" to hash, "fn" to fileName, "n24pref" to "1")), "application/x-www-form-urlencoded", 8 * 1024 * 1024)
    }

    private fun searchNapisy24(context: Context, q: Query): Pair<List<Result>, Problem?> {
        if (q.hash.isEmpty() || q.size <= 0) return Pair(emptyList(), null)
        val a = napisy24Of(napisy24Request(context, q.hash, q.size, q.fileName))
        if (a.problem != null) return Pair(emptyList(), a.problem)
        if (a.zip == null || a.zip.isEmpty()) return Pair(emptyList(), null)
        return Pair(listOf(Result("napisy24:${q.hash}", "napisy24", "pl", a.info["ftitle"]?.trim()?.ifEmpty { null } ?: q.fileName.substringBeforeLast('.'),
            url = "napisy24:${q.hash}:${q.size}:${q.fileName}", exact = true)), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Legendas.net (Portuguese of Brazil): signs in with the email and password for a token, then searches by the
    // IMDb id; a download is a zip, with the token

    private const val LEGENDAS = "https://legendas.net/api/v1/"

    fun legendasLoginOf(r: Response): Pair<String?, Problem?> = when {
        r.status == 0 || r.error != null -> Pair(null, Problem.NoAnswer("Legendas.net"))
        r.status == 401 || r.status == 403 || r.status == 422 -> Pair(null, Problem.LoginRefused("Legendas.net"))
        r.status == 429 -> Pair(null, Problem.Busy("Legendas.net"))
        r.status != 200 -> Pair(null, Problem.Status("Legendas.net", r.status))
        else -> json(r.body)?.optString("access_token")?.ifEmpty { null }?.let { Pair(it, null) } ?: Pair(null, Problem.LoginRefused("Legendas.net"))
    }

    fun parseLegendas(json: String, q: Query): List<Result> {
        val o = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        if ((o.has("success") && !o.optBoolean("success")) || (o.has("status") && o.opt("status") == false)) return emptyList()
        val items = o.optJSONArray(if (q.series) "tv_shows" else "movies") ?: return emptyList()
        val list = ArrayList<Result>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            val id = s.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() } } ?: continue
            val path = s.optString("path").ifEmpty { continue }
            val url = if (path.startsWith("https://")) path else if (path.startsWith("/") && !path.startsWith("//")) "https://legendas.net$path" else continue
            if (q.series && q.season >= 0 && q.episode >= 0) {
                val season = intOf(s, "season")
                val episode = intOf(s, "episode")
                if (season > 0 && season != q.season) continue
                if (episode > 0 && episode != q.episode) continue
            }
            val forced = listOf("forced", "foreign").any { it in s.optString("comment").lowercase(Locale.ROOT) }
            list.add(Result("legendasnet:$id", "legendasnet", "pt-BR", s.optString("release_name").trim(), url = url, foreignOnly = forced))
        }
        return list
    }

    internal fun legendasToken(context: Context, force: Boolean): Pair<String?, Problem?> {
        val email = OnlineSubtitles.credential(context, "legendasnet", Field.EMAIL)
        val password = OnlineSubtitles.credential(context, "legendasnet", Field.PASSWORD)
        if (email.isEmpty() || password.isEmpty()) return Pair(null, null)
        if (!force) SubtitleSecrets.token(context, "legendasnet", email)?.let { return Pair(it, null) }
        val r = OnlineSubtitles.postBody(LEGENDAS + "login", mapOf("User-Agent" to userAgent, "Accept" to "application/json"),
            JSONObject().put("email", email).put("password", password).toString().toByteArray(Charsets.UTF_8), "application/json")
        val (token, problem) = legendasLoginOf(r)
        if (token != null) SubtitleSecrets.setToken(context, "legendasnet", email, token, System.currentTimeMillis() + 12 * 3600_000L)
        return Pair(token, problem)
    }

    private suspend fun searchLegendas(context: Context, q: Query): Pair<List<Result>, Problem?> {
        if (!q.imdb.startsWith("tt") && q.title.isEmpty()) return Pair(emptyList(), null)
        for (attempt in 0..1) {
            val (token, problem) = legendasToken(context, force = attempt > 0)
            token ?: return Pair(emptyList(), problem)
            // (Bazarr sends these as a JSON body of the GET, which Android can't: the same as parameters)
            val params = arrayListOf("page=1", "per_page=25")
            if (q.imdb.startsWith("tt")) params += "imdb_id=${q.imdb}" else params += "name=${enc(q.title)}"
            if (q.series && q.season >= 0 && q.episode >= 0) params += listOf("tv_season=${q.season}", "tv_episode=${q.episode}")
            val r = OnlineSubtitles.get(LEGENDAS + (if (q.series) "search/tv?" else "search/movie?") + params.joinToString("&"),
                mapOf("Authorization" to "Bearer $token", "User-Agent" to userAgent, "Accept" to "application/json"))
            if ((r.status == 401 || r.status == 403) && attempt == 0) { delay(REQUEST_GAP_MS); continue }
            if (r.status == 401 || r.status == 403) return Pair(emptyList(), Problem.LoginRefused("Legendas.net"))
            if (r.status == 429) return Pair(emptyList(), Problem.Busy("Legendas.net"))
            if (r.status == 404) return Pair(emptyList(), null)
            if (!OnlineSubtitles.ok(r)) return Pair(emptyList(), OnlineSubtitles.failure("Legendas.net", r))
            return Pair(parseLegendas(String(r.body, Charsets.UTF_8), q), null)
        }
        return Pair(emptyList(), null)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Downloads

    /** what a download of theirs needs (SubSource, Jimaku, Subs.ro: the key; Legendas.net: the sign-in's token) */
    fun downloadHeaders(context: Context, r: Result): Map<String, String> = when (r.provider) {
        "subsource" -> mapOf("X-API-Key" to OnlineSubtitles.credential(context, "subsource", Field.KEY))
        "jimaku" -> mapOf("Authorization" to OnlineSubtitles.credential(context, "jimaku", Field.KEY))
        "subsro" -> mapOf("X-Subs-Api-Key" to OnlineSubtitles.credential(context, "subsro", Field.KEY))
        "legendasnet" -> legendasToken(context, false).first?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
        else -> emptyMap()
    }

    /**
     * a download that isn't one address: Assrt lists the files of a subtitle first (/sub/detail), Napisy24 gives
     * the subtitle in the answer of its POST; null for the others (their result's address)
     */
    fun fetch(context: Context, r: Result, maxBytes: Int): Response? = when (r.provider) {
        "assrt" -> {
            val token = OnlineSubtitles.credential(context, "assrt", Field.KEY)
            val id = r.url.removePrefix("assrt:")
            val detail = OnlineSubtitles.get("$ASSRT/sub/detail?token=${enc(token)}&id=${enc(id)}", mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
            when (assrtProblem(detail)) {
                null -> {
                    val episode = episodeOf(r.release)
                    assrtFile(String(detail.body, Charsets.UTF_8), episode?.first ?: -1, episode?.second ?: -1)
                        ?.let { OnlineSubtitles.get(it.replaceFirst("http://", "https://"), mapOf("User-Agent" to userAgent), maxBytes, 30_000) }
                        ?: Response(404, ByteArray(0), null)
                }
                is Problem.KeyRefused -> Response(401, ByteArray(0), null)
                is Problem.Busy -> Response(429, ByteArray(0), null)
                else -> Response(if (detail.status == 200) 502 else detail.status, ByteArray(0), detail.error)
            }
        }
        "napisy24" -> {
            val p = r.url.removePrefix("napisy24:").split(':', limit = 3)
            val a = napisy24Of(napisy24Request(context, p.getOrElse(0) { "" }, p.getOrNull(1)?.toLongOrNull() ?: 0L, p.getOrElse(2) { "" }))
            when {
                a.zip != null && a.zip.isNotEmpty() -> Response(200, a.zip, null)
                a.problem is Problem.LoginRefused -> Response(401, ByteArray(0), null)
                a.problem is Problem.NoAnswer -> Response(0, ByteArray(0), "NoAnswer")
                else -> Response(404, ByteArray(0), null)
            }
        }
        else -> null
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
            "assrt" -> assrtProblem(r)
            "jimaku" -> jimakuProblem(r)
            "betaseries" -> betaSeriesProblem(r)
            "subsro" -> subsRoProblem(r)
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

    /** a sign-in's answer: signed in as that user, or why not */
    fun signInCheckOf(signedIn: Boolean, problem: Problem?, user: String): CheckResult = when {
        signedIn -> CheckResult(Check.SIGNED_IN, user)
        problem is Problem.LoginRefused || problem == Problem.LoginFailed -> CheckResult(Check.LOGIN_REFUSED)
        problem is Problem.KeyRefused -> CheckResult(Check.KEY_REFUSED)
        problem is Problem.Busy -> CheckResult(Check.BUSY)
        else -> CheckResult(Check.NO_ANSWER)
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
            "assrt" -> CheckResult(keyCheckOf(id, OnlineSubtitles.get("$ASSRT/user/quota?token=${enc(OnlineSubtitles.credential(context, id, Field.KEY))}",
                mapOf("User-Agent" to userAgent, "Accept" to "application/json"))))
            "jimaku" -> CheckResult(keyCheckOf(id, OnlineSubtitles.get("$JIMAKU/entries/search?query=frieren",
                mapOf("Authorization" to OnlineSubtitles.credential(context, id, Field.KEY), "User-Agent" to userAgent, "Accept" to "application/json"))))
            "betaseries" -> CheckResult(keyCheckOf(id, OnlineSubtitles.get("$BETASERIES/shows/display?key=${enc(OnlineSubtitles.credential(context, id, Field.KEY))}&imdb_id=tt0903747&v=3.0",
                mapOf("User-Agent" to userAgent, "Accept" to "application/json"))))
            "subsro" -> CheckResult(keyCheckOf(id, OnlineSubtitles.get("$SUBSRO/search/imdbid/0133093?language=ro",
                mapOf("X-Subs-Api-Key" to OnlineSubtitles.credential(context, id, Field.KEY), "User-Agent" to userAgent, "Accept" to "application/json"))))
            // the services of an account: a sign-in
            "titlovi" -> titloviToken(context, force = true).let { (token, problem) ->
                signInCheckOf(token != null, problem, OnlineSubtitles.credential(context, id, Field.USERNAME)) }
            "legendasnet" -> legendasToken(context, force = true).let { (token, problem) ->
                signInCheckOf(token != null, problem, OnlineSubtitles.credential(context, id, Field.EMAIL)) }
            "napisy24" -> napisy24Of(napisy24Request(context, "0000000000000000", 1_000_000L, "check.mkv")).let { a ->
                signInCheckOf(a.problem == null, a.problem, OnlineSubtitles.credential(context, id, Field.USERNAME)) }
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
