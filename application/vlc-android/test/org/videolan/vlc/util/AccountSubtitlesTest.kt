package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.util.KeyedSubtitles.Check
import org.videolan.vlc.util.OnlineSubtitles.Problem
import org.videolan.vlc.util.OnlineSubtitles.Query
import org.videolan.vlc.util.OnlineSubtitles.Response
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** VLC Redesign, the subtitle services of a key or an account of wave 2 (KeyedSubtitles): Titlovi, Assrt, Jimaku,
 *  BetaSeries, Subs.ro, Napisy24, Legendas.net, read from saved answers (SubtitleFixtures2: the shapes of Bazarr's
 *  providers, and what the services answered on 2026-11-08 without a key or account); no network */
class AccountSubtitlesTest {

    private fun response(status: Int, body: String = "") = Response(status, body.toByteArray(Charsets.UTF_8), null)
    private val noAnswer = Response(0, ByteArray(0), "UnknownHostException")
    private val film = Query(imdb = "tt0133093", title = "The Matrix", year = 1999, languages = listOf("hr", "sr", "en"))
    private val episode = Query(imdb = "tt0903747", title = "Breaking Bad", series = true, season = 1, episode = 2,
        fileName = "Breaking.Bad.S01E02.720p.mkv", languages = listOf("fr", "en"))

    @Test
    fun theirDeclarations() {
        val ids = OnlineSubtitles.providers.map { it.id }
        assertEquals(listOf("titlovi", "assrt", "jimaku", "betaseries", "subsro", "napisy24", "legendasnet"), ids.drop(7))
        fun p(id: String) = OnlineSubtitles.provider(id)!!
        // each asked only when one of its languages is the user's
        assertEquals(setOf("hr", "sr", "bs", "sl", "mk"), p("titlovi").languages)
        assertTrue(OnlineSubtitles.languageAllows(p("titlovi"), listOf("en", "sr")))
        assertFalse(OnlineSubtitles.languageAllows(p("titlovi"), listOf("en")))
        assertTrue(OnlineSubtitles.languageAllows(p("assrt"), listOf("zh-TW")))
        assertTrue(OnlineSubtitles.languageAllows(p("legendasnet"), listOf("pt-BR")))
        assertFalse(OnlineSubtitles.languageAllows(p("jimaku"), listOf("en", "he")))
        // accounts: a user name (or an email) and a password, both needed; keys: the key
        assertTrue(p("titlovi").login && p("napisy24").login && p("legendasnet").login)
        assertEquals(listOf(OnlineSubtitles.Field.EMAIL, OnlineSubtitles.Field.PASSWORD), p("legendasnet").needs)
        assertTrue(p("assrt").token)
        assertFalse(p("betaseries").movies)
        for (id in listOf("assrt", "jimaku", "betaseries", "subsro")) assertEquals(listOf(OnlineSubtitles.Field.KEY), p(id).needs)
    }

    // Titlovi

    @Test
    fun titloviSignIn() {
        assertTrue(KeyedSubtitles.titloviLoginOf(response(401)).second is Problem.LoginRefused)
        assertTrue(KeyedSubtitles.titloviLoginOf(noAnswer).second is Problem.NoAnswer)
        assertTrue(KeyedSubtitles.titloviLoginOf(response(429)).second is Problem.Busy)
        val now = utc(2026, 11, 8, 12)
        val (token, problem) = KeyedSubtitles.titloviLoginOf(response(200, SubtitleFixtures2.TITLOVI_TOKEN), now)
        assertNull(problem)
        assertEquals("a1b2c3", token!!.first)
        assertEquals("4242", token.second)
        assertEquals(utc(2026, 11, 15, 12), token.third)
        // a date it can't read: six hours
        assertEquals(now + 6 * 3600_000L, KeyedSubtitles.titloviLoginOf(response(200, """{"Token":"t","UserId":1,"ExpirationDate":"soon"}"""), now).first!!.third)
        assertTrue(KeyedSubtitles.titloviLoginOf(response(200, """{"UserId":1}""")).second is Problem.LoginRefused)
    }

    private fun utc(y: Int, m: Int, d: Int, h: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(y, m - 1, d, h, 0, 0)
    }.timeInMillis

    @Test
    fun titloviSubtitles() {
        val list = KeyedSubtitles.parseTitlovi(SubtitleFixtures2.TITLOVI_FILM, film)
        assertEquals(listOf("titlovi:1", "titlovi:2", "titlovi:3"), list.map { it.id })
        assertEquals(listOf("hr", "sr", "sr"), list.map { it.language })
        assertEquals("The.Matrix.1999.1080p.BluRay", list[0].release)
        assertEquals("https://titlovi.com/download/?type=1&mediaid=1", list[0].url)
        assertEquals(1200, list[0].downloads)
        // no release: its title; an address in http: https
        assertEquals("The Matrix", list[2].release)
        assertTrue(list[2].url.startsWith("https://"))
        val eps = KeyedSubtitles.parseTitlovi(SubtitleFixtures2.TITLOVI_EPISODES, episode.copy(languages = listOf("hr")))
        assertEquals(listOf("titlovi:11", "titlovi:13"), eps.map { it.id })
        assertEquals("hr", KeyedSubtitles.fromTitlovi("Hrvatski"))
        assertEquals("sr", KeyedSubtitles.fromTitlovi("Cirilica"))
        assertEquals("", KeyedSubtitles.fromTitlovi("Deutsch"))
    }

    // Assrt

    @Test
    fun assrt() {
        assertTrue(KeyedSubtitles.assrtProblem(response(400, SubtitleFixtures2.ASSRT_WRONG_TOKEN)) is Problem.KeyRefused)
        assertTrue(KeyedSubtitles.assrtProblem(response(200, """{"status":30900,"errmsg":"exceeding the limit"}""")) is Problem.Busy)
        assertTrue(KeyedSubtitles.assrtProblem(noAnswer) is Problem.NoAnswer)
        assertNull(KeyedSubtitles.assrtProblem(response(200, SubtitleFixtures2.ASSRT_SEARCH)))
        assertNull(KeyedSubtitles.assrtProblem(response(200, SubtitleFixtures2.ASSRT_QUOTA)))
        val q = Query(title = "The Matrix", year = 1999, languages = listOf("zh"))
        val list = KeyedSubtitles.parseAssrt(SubtitleFixtures2.ASSRT_SEARCH, q)
        assertEquals(listOf("assrt:602333", "assrt:602334"), list.map { it.id })
        assertEquals("zh", list[0].language)
        assertEquals("The.Matrix.1999.1080p.BluRay", list[0].release)
        assertEquals("assrt:602333", list[0].url)
        // "不知道" (unknown): its own name; only traditional: zh-TW
        assertEquals("黑客帝國", list[1].release)
        assertEquals("zh-TW", list[1].language)
        // the files of /sub/detail: the episode's, in Chinese; none listed: the archive
        assertEquals("https://file.assrt.net/onthefly/1/b.chs.srt", KeyedSubtitles.assrtFile(SubtitleFixtures2.ASSRT_DETAIL, 1, 2))
        assertEquals("https://file.assrt.net/onthefly/1/a.chs.srt", KeyedSubtitles.assrtFile(SubtitleFixtures2.ASSRT_DETAIL, -1, -1))
        assertEquals("https://file.assrt.net/download/602333/x.zip", KeyedSubtitles.assrtFile(SubtitleFixtures2.ASSRT_DETAIL_ARCHIVE, -1, -1))
        assertEquals(setOf("zh", "en"), KeyedSubtitles.assrtLanguages(org.json.JSONObject("""{"langchs":true,"langeng":true,"langdou":true}""")).toSet())
    }

    // Jimaku

    @Test
    fun jimaku() {
        assertTrue(KeyedSubtitles.jimakuProblem(response(401, SubtitleFixtures2.JIMAKU_UNAUTHORIZED)) is Problem.KeyRefused)
        assertTrue(KeyedSubtitles.jimakuProblem(response(429)) is Problem.Busy)
        val q = Query(title = "Frieren: Beyond Journey's End", series = true, season = 1, episode = 2, languages = listOf("ja"))
        assertEquals(1L, KeyedSubtitles.jimakuEntry(SubtitleFixtures2.JIMAKU_ENTRIES, q))
        assertEquals(2L, KeyedSubtitles.jimakuEntry(SubtitleFixtures2.JIMAKU_ENTRIES, Query(title = "Frieren the movie", languages = listOf("ja"))))
        assertNull(KeyedSubtitles.jimakuEntry(SubtitleFixtures2.JIMAKU_ENTRIES, Query(title = "Breaking Bad", languages = listOf("ja"))))
        val files = KeyedSubtitles.parseJimaku(SubtitleFixtures2.JIMAKU_FILES, 1L)
        // not the 7z, the broken one, the machine's
        assertEquals(1, files.size)
        assertEquals("ja", files[0].language)
        assertEquals("[SubsPlease] Sousou no Frieren - 02.ja.srt", files[0].fileName)
        assertTrue(files[0].id.startsWith("jimaku:1:"))
    }

    // BetaSeries

    @Test
    fun betaSeries() {
        assertTrue(KeyedSubtitles.betaSeriesProblem(response(400, SubtitleFixtures2.BETASERIES_WRONG_KEY)) is Problem.KeyRefused)
        assertNull(KeyedSubtitles.betaSeriesProblem(response(400, """{"errors":[{"code":4001,"text":"Série inexistante."}]}""")))
        assertNull(KeyedSubtitles.betaSeriesProblem(response(200, SubtitleFixtures2.BETASERIES_SHOW)))
        assertEquals(481L, KeyedSubtitles.betaSeriesShow(SubtitleFixtures2.BETASERIES_SHOW))
        val list = KeyedSubtitles.parseBetaSeries(SubtitleFixtures2.BETASERIES_EPISODE)
        // VF and VO, not seriessub's
        assertEquals(listOf("betaseries:11", "betaseries:12"), list.map { it.id })
        assertEquals(listOf("fr", "en"), list.map { it.language })
        assertEquals("Breaking.Bad.S01E02.fr", list[0].release)
    }

    // Subs.ro

    @Test
    fun subsRo() {
        assertTrue(KeyedSubtitles.subsRoProblem(response(403, SubtitleFixtures2.SUBSRO_WRONG_KEY)) is Problem.KeyRefused)
        assertTrue(KeyedSubtitles.subsRoProblem(response(429)) is Problem.Busy)
        val films = KeyedSubtitles.parseSubsRo(SubtitleFixtures2.SUBSRO_FILM, Query(imdb = "tt0133093", title = "The Matrix", languages = listOf("ro")))
        assertEquals(listOf("subsro:abc1"), films.map { it.id })
        assertEquals("ro", films[0].language)
        assertEquals("The.Matrix.1999.1080p.BluRay", films[0].release)
        val eps = KeyedSubtitles.parseSubsRo(SubtitleFixtures2.SUBSRO_SERIES, episode.copy(languages = listOf("ro")))
        assertEquals(listOf("subsro:s1", "subsro:s4"), eps.map { it.id })
        assertTrue(KeyedSubtitles.parseSubsRo("""{"status":404,"items":[]}""", episode).isEmpty())
    }

    // Napisy24

    private fun napisy24Answer(head: String, withZip: Boolean): Response {
        val out = ByteArrayOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        if (withZip) {
            out.write("||".toByteArray())
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("The.Matrix.1999.txt"))
                z.write("1\n00:00:01,000 --> 00:00:02,000\nCześć\n".toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return Response(200, out.toByteArray(), null)
    }

    @Test
    fun napisy24() {
        assertTrue(KeyedSubtitles.napisy24Of(response(200, SubtitleFixtures2.NAPISY24_LOGIN_ERROR)).problem is Problem.LoginRefused)
        val none = KeyedSubtitles.napisy24Of(response(200, "OK-0"))
        assertNull(none.problem)
        assertNull(none.zip)
        assertTrue(KeyedSubtitles.napisy24Of(noAnswer).problem is Problem.NoAnswer)
        val found = KeyedSubtitles.napisy24Of(napisy24Answer("OK-2|imdb:133093|napisId:12345|ftitle:The Matrix|fps:23.976", true))
        assertNull(found.problem)
        assertEquals("OK-2", found.status)
        assertEquals("12345", found.info["napisId"])
        assertEquals("The Matrix", found.info["ftitle"])
        assertNotNull(found.zip)
        // the zip is the subtitle the download unpacks
        val r = OnlineSubtitles.Result("napisy24:x", "napisy24", "pl", "The Matrix", url = "napisy24:x:1:The.Matrix.mkv", exact = true)
        val (unpacked, problem) = OnlineSubtitles.subtitleOf(found.zip!!, r, "The.Matrix.1999.mkv")
        assertNull(problem)
        assertTrue(unpacked!!.text.contains("Cześć"))
    }

    // Legendas.net

    @Test
    fun legendas() {
        assertTrue(KeyedSubtitles.legendasLoginOf(response(401, SubtitleFixtures2.LEGENDAS_WRONG_LOGIN)).second is Problem.LoginRefused)
        assertEquals("eyJ0eXAi.x.y", KeyedSubtitles.legendasLoginOf(response(200, """{"access_token":"eyJ0eXAi.x.y","token_type":"bearer"}""")).first)
        assertTrue(KeyedSubtitles.legendasLoginOf(response(200, "{}")).second is Problem.LoginRefused)
        val films = KeyedSubtitles.parseLegendas(SubtitleFixtures2.LEGENDAS_MOVIES, Query(imdb = "tt0133093", title = "The Matrix", languages = listOf("pt-BR")))
        assertEquals(listOf("legendasnet:5", "legendasnet:6"), films.map { it.id })
        assertEquals("https://legendas.net/storage/legendas/the-matrix.zip", films[0].url)
        assertEquals("pt-BR", films[0].language)
        assertTrue(films[1].foreignOnly)
        val eps = KeyedSubtitles.parseLegendas(SubtitleFixtures2.LEGENDAS_TV, episode.copy(languages = listOf("pt-BR")))
        assertEquals(listOf("legendasnet:8"), eps.map { it.id })
        assertTrue(KeyedSubtitles.parseLegendas("""{"success":false,"movies":[]}""", film).isEmpty())
    }

    // Test and Sign in

    @Test
    fun checks() {
        assertEquals(Check.KEY_REFUSED, KeyedSubtitles.keyCheckOf("assrt", response(400, SubtitleFixtures2.ASSRT_WRONG_TOKEN)))
        assertEquals(Check.KEY_WORKS, KeyedSubtitles.keyCheckOf("assrt", response(200, SubtitleFixtures2.ASSRT_QUOTA)))
        assertEquals(Check.KEY_REFUSED, KeyedSubtitles.keyCheckOf("jimaku", response(401, SubtitleFixtures2.JIMAKU_UNAUTHORIZED)))
        assertEquals(Check.KEY_WORKS, KeyedSubtitles.keyCheckOf("jimaku", response(200, SubtitleFixtures2.JIMAKU_ENTRIES)))
        assertEquals(Check.KEY_REFUSED, KeyedSubtitles.keyCheckOf("betaseries", response(400, SubtitleFixtures2.BETASERIES_WRONG_KEY)))
        assertEquals(Check.KEY_WORKS, KeyedSubtitles.keyCheckOf("betaseries", response(200, SubtitleFixtures2.BETASERIES_SHOW)))
        assertEquals(Check.KEY_REFUSED, KeyedSubtitles.keyCheckOf("subsro", response(403, SubtitleFixtures2.SUBSRO_WRONG_KEY)))
        assertEquals(Check.KEY_WORKS, KeyedSubtitles.keyCheckOf("subsro", response(200, SubtitleFixtures2.SUBSRO_FILM)))
        assertEquals(Check.NO_ANSWER, KeyedSubtitles.keyCheckOf("subsro", noAnswer))
        val signed = KeyedSubtitles.signInCheckOf(true, null, "chen")
        assertEquals(Check.SIGNED_IN, signed.check)
        assertEquals("chen", signed.user)
        assertEquals(Check.LOGIN_REFUSED, KeyedSubtitles.signInCheckOf(false, Problem.LoginRefused("Titlovi"), "").check)
        assertEquals(Check.NO_ANSWER, KeyedSubtitles.signInCheckOf(false, Problem.NoAnswer("Titlovi"), "").check)
        assertEquals(Check.BUSY, KeyedSubtitles.signInCheckOf(false, Problem.Busy("Legendas.net"), "").check)
    }
}
