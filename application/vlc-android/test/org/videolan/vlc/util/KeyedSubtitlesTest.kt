package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.util.KeyedSubtitles.Check
import org.videolan.vlc.util.OnlineSubtitles.Problem
import org.videolan.vlc.util.OnlineSubtitles.Query
import org.videolan.vlc.util.OnlineSubtitles.Response

/** VLC Redesign, the subtitle services of the user's own free key (KeyedSubtitles): SubSource and SubDL read from
 *  saved answers (SubtitleFixtures: the shapes of a4kSubtitles' services, and the error answers the services gave
 *  on 2026-11-08 to a wrong key), their errors, the check of a key; the services' declarations (no network) */
class KeyedSubtitlesTest {

    private fun response(status: Int, body: String = "") = Response(status, body.toByteArray(Charsets.UTF_8), null)
    private val film = Query(imdb = "tt0133093", title = "The Matrix", year = 1999, languages = listOf("ar", "en"))
    private val episode = Query(imdb = "tt0903747", title = "Breaking Bad", series = true, season = 1, episode = 2,
        fileName = "Breaking.Bad.S01E02.720p.mkv", languages = listOf("ar", "en"))

    @Test
    fun theServicesAndTheirDefaults() {
        val ids = OnlineSubtitles.providers.map { it.id }
        // in the order of the settings' table
        assertEquals(listOf("opensubtitles", "opensubtitlescom", "subsource", "subdl", "wizdom", "addic7ed", "yify"), ids.take(7))
        // the four free ones (on by default) need nothing; every other one a key or an account (off by default)
        assertEquals(listOf("opensubtitles", "wizdom", "addic7ed", "yify"), OnlineSubtitles.providers.filter { !it.keyed }.map { it.id })
        for (p in OnlineSubtitles.providers.filter { it.keyed }) {
            assertTrue(p.id, p.page.startsWith("https://"))
            assertTrue(p.id, p.needs.isNotEmpty())
        }
        // OpenSubtitles.com: the key alone is enough, the account optional
        val oscom = OnlineSubtitles.provider("opensubtitlescom")!!
        assertEquals(listOf(OnlineSubtitles.Field.KEY), oscom.needs)
        assertTrue(oscom.login)
    }

    @Test
    fun languageOnlyServices() {
        val wizdom = OnlineSubtitles.provider("wizdom")!!
        assertFalse(OnlineSubtitles.languageAllows(wizdom, listOf("en", "fr")))
        assertTrue(OnlineSubtitles.languageAllows(wizdom, listOf("en", "he")))
        assertTrue(OnlineSubtitles.languageAllows(OnlineSubtitles.provider("opensubtitles")!!, listOf("en")))
        assertTrue(OnlineSubtitles.languageAllows(OnlineSubtitles.provider("subsource")!!, emptyList()))
    }

    @Test
    fun statesOfTheRows() {
        assertEquals(OnlineSubtitles.ServiceState.KEY_REFUSED, OnlineSubtitles.stateOf(Problem.KeyRefused("SubSource")))
        assertEquals(OnlineSubtitles.ServiceState.LOGIN_REFUSED, OnlineSubtitles.stateOf(Problem.LoginRefused("Titlovi")))
        assertEquals(OnlineSubtitles.ServiceState.LOGIN_REFUSED, OnlineSubtitles.stateOf(Problem.LoginFailed))
        assertEquals(OnlineSubtitles.ServiceState.BUSY, OnlineSubtitles.stateOf(Problem.Busy("SubDL")))
        assertNull(OnlineSubtitles.stateOf(Problem.NoAnswer("SubDL")))
        assertNull(OnlineSubtitles.stateOf(null))
    }

    @Test
    fun episodesOfReleases() {
        assertEquals(Pair(1, 2), KeyedSubtitles.episodeOf("Breaking.Bad.S01E02.720p.WEB-DL"))
        assertEquals(Pair(1, 2), KeyedSubtitles.episodeOf("breaking bad 1x02 hdtv"))
        assertNull(KeyedSubtitles.episodeOf("Breaking.Bad.Season.1.Complete"))
        assertTrue(KeyedSubtitles.forEpisode(listOf("Show.S01E03.x264", "Show.S01E02.x264"), 1, 2))
        assertFalse(KeyedSubtitles.forEpisode(listOf("Show.S01E03.x264"), 1, 2))
        // a whole season: kept
        assertTrue(KeyedSubtitles.forEpisode(listOf("Show.Season.1.Complete.720p"), 1, 2))
        assertTrue(KeyedSubtitles.forEpisode(emptyList(), 1, 2))
    }

    // SubSource

    @Test
    fun subSourceLanguages() {
        assertEquals(listOf("hebrew"), KeyedSubtitles.subSourceLanguages("he"))
        assertEquals(listOf("arabic"), KeyedSubtitles.subSourceLanguages("ar"))
        assertEquals(listOf("farsi_persian"), KeyedSubtitles.subSourceLanguages("fa"))
        assertEquals(listOf("brazilian_portuguese"), KeyedSubtitles.subSourceLanguages("pt-BR"))
        assertEquals(listOf("spanish_latin_america"), KeyedSubtitles.subSourceLanguages("es-419"))
        assertTrue("chinese_traditional" in KeyedSubtitles.subSourceLanguages("zh-TW"))
        assertTrue("chinese_simplified" in KeyedSubtitles.subSourceLanguages("zh"))
        // a language of no table: its English name
        assertEquals(listOf("yoruba"), KeyedSubtitles.subSourceLanguages("yo"))
        assertEquals("fa", KeyedSubtitles.fromSubSource("farsi_persian"))
        assertEquals("pt-BR", KeyedSubtitles.fromSubSource("brazilian_portuguese"))
        assertEquals("zh-TW", KeyedSubtitles.fromSubSource("big_5_code"))
        assertEquals("es-419", KeyedSubtitles.fromSubSource("spanish_latin_america"))
        assertEquals("ar", KeyedSubtitles.fromSubSource("Arabic"))
        assertEquals("", KeyedSubtitles.fromSubSource(""))
    }

    @Test
    fun subSourceTitle() {
        // a film: the entry of its IMDb id, not the first
        assertEquals(456L, KeyedSubtitles.subSourceMovie(SubtitleFixtures.SUBSOURCE_FILM_SEARCH, film))
        // none of its id: the first
        assertEquals(123L, KeyedSubtitles.subSourceMovie(SubtitleFixtures.SUBSOURCE_FILM_SEARCH, film.copy(imdb = "tt9999999")))
        // a series: the entry of the episode's season
        assertEquals(901L, KeyedSubtitles.subSourceMovie(SubtitleFixtures.SUBSOURCE_SERIES_SEARCH, episode))
        assertEquals(902L, KeyedSubtitles.subSourceMovie(SubtitleFixtures.SUBSOURCE_SERIES_SEARCH, episode.copy(season = 2)))
        assertNull(KeyedSubtitles.subSourceMovie(SubtitleFixtures.SUBSOURCE_SERIES_SEARCH, episode.copy(season = 7)))
        assertNull(KeyedSubtitles.subSourceMovie("""{"success":true,"data":[]}""", film))
        assertNull(KeyedSubtitles.subSourceMovie("<html>", film))
    }

    @Test
    fun subSourceSubtitles() {
        val list = KeyedSubtitles.parseSubSource(SubtitleFixtures.SUBSOURCE_FILM_SUBTITLES, film)
        assertEquals(3, list.size)
        val ar = list[0]
        assertEquals("subsource:9", ar.id)
        assertEquals("subsource", ar.provider)
        assertEquals("ar", ar.language)
        assertEquals("The.Matrix.1999.1080p.BluRay.x264-YIFY", ar.release)
        assertEquals("https://api.subsource.net/api/v1/subtitles/9/download", ar.url)
        assertFalse(ar.hearingImpaired)
        assertTrue(ar.rank > 0)
        assertEquals("fa", list[1].language)
        assertTrue(list[1].hearingImpaired)
        assertEquals("pt-BR", list[2].language)
        // an episode: those of S01E02 and of the whole season, not of S01E03
        val eps = KeyedSubtitles.parseSubSource(SubtitleFixtures.SUBSOURCE_SEASON_SUBTITLES, episode)
        assertEquals(listOf("subsource:21", "subsource:23"), eps.map { it.id })
    }

    @Test
    fun subSourceErrors() {
        assertTrue(KeyedSubtitles.subSourceProblem(response(401, SubtitleFixtures.SUBSOURCE_WRONG_KEY)) is Problem.KeyRefused)
        assertTrue(KeyedSubtitles.subSourceProblem(response(401, SubtitleFixtures.SUBSOURCE_NO_KEY)) is Problem.KeyRefused)
        assertTrue(KeyedSubtitles.subSourceProblem(response(403)) is Problem.KeyRefused)
        assertTrue(KeyedSubtitles.subSourceProblem(response(429)) is Problem.Busy)
        assertTrue(KeyedSubtitles.subSourceProblem(Response(0, ByteArray(0), "UnknownHostException")) is Problem.NoAnswer)
        assertEquals(Problem.Status("SubSource", 502), KeyedSubtitles.subSourceProblem(response(502)))
        assertNull(KeyedSubtitles.subSourceProblem(response(200, SubtitleFixtures.SUBSOURCE_FILM_SUBTITLES)))
        assertNull(KeyedSubtitles.subSourceProblem(response(404)))
    }

    // SubDL

    @Test
    fun subDlLanguages() {
        assertEquals("pt", KeyedSubtitles.subDlCode("pt-BR", v1 = false))
        assertEquals("BR_PT", KeyedSubtitles.subDlCode("pt-BR", v1 = true))
        assertEquals("ZH_BG", KeyedSubtitles.subDlCode("zh-TW", v1 = true))
        assertEquals("HE", KeyedSubtitles.subDlCode("he", v1 = true))
        assertEquals("ar", KeyedSubtitles.subDlCode("ar", v1 = false))
        assertEquals("ar", KeyedSubtitles.fromSubDl("AR", "arabic"))
        assertEquals("pt-BR", KeyedSubtitles.fromSubDl("BR_PT", "Brazillian Portuguese"))
        assertEquals("pt-BR", KeyedSubtitles.fromSubDl("", "Brazillian Portuguese"))
        assertEquals("zh-TW", KeyedSubtitles.fromSubDl("ZH_BG", ""))
        assertEquals("fa", KeyedSubtitles.fromSubDl("", "farsi_persian"))
        assertEquals("he", KeyedSubtitles.fromSubDl("hebrew", ""))
    }

    @Test
    fun subDlSubtitles() {
        val list = KeyedSubtitles.parseSubDl(SubtitleFixtures.SUBDL_FILM, film)
        assertEquals(3, list.size)
        assertEquals("subdl:3197651-3213944", list[0].id)
        assertEquals("ar", list[0].language)
        assertEquals("The.Matrix.1999.REMASTERED.1080p.BluRay", list[0].release)
        assertEquals("https://dl.subdl.com/subtitle/3197651-3213944.zip", list[0].url)
        assertEquals("en", list[1].language)
        assertTrue(list[1].hearingImpaired)
        // no release name: its name
        assertEquals("The Matrix", list[2].release)
        assertEquals("pt-BR", list[2].language)
        // an episode: S01E02 and the whole season; not S01E03, not season 2
        val eps = KeyedSubtitles.parseSubDl(SubtitleFixtures.SUBDL_EPISODES, episode)
        assertEquals(listOf("subdl:100-1", "subdl:100-3"), eps.map { it.id })
        // "nothing found" is no error
        assertTrue(KeyedSubtitles.parseSubDl(SubtitleFixtures.SUBDL_NOT_FOUND, film).isEmpty())
        assertNull(KeyedSubtitles.subDlProblem(response(200, SubtitleFixtures.SUBDL_NOT_FOUND)))
        // a link outside the archive's folder is refused
        assertTrue(KeyedSubtitles.parseSubDl("""{"status":true,"subtitles":[{"url":"https://evil.example/x.zip","language":"EN"}]}""", film).isEmpty())
    }

    @Test
    fun subDlErrors() {
        assertTrue(KeyedSubtitles.subDlProblem(response(401, SubtitleFixtures.SUBDL_V2_WRONG_KEY)) is Problem.KeyRefused)
        assertTrue(KeyedSubtitles.subDlProblem(response(403, SubtitleFixtures.SUBDL_V1_WRONG_KEY)) is Problem.KeyRefused)
        // a 200 that says the key is wrong
        assertTrue(KeyedSubtitles.subDlProblem(response(200, """{"status":false,"error":"Invalid API key"}""")) is Problem.KeyRefused)
        assertTrue(KeyedSubtitles.subDlProblem(response(429)) is Problem.Busy)
        assertTrue(KeyedSubtitles.subDlProblem(response(200, """{"status":false,"error":"rate limit reached"}""")) is Problem.Busy)
        assertTrue(KeyedSubtitles.subDlProblem(Response(0, ByteArray(0), "SocketTimeoutException")) is Problem.NoAnswer)
        assertEquals(Problem.Status("SubDL", 500), KeyedSubtitles.subDlProblem(response(500, "<html>")))
        assertNull(KeyedSubtitles.subDlProblem(response(200, SubtitleFixtures.SUBDL_FILM)))
    }

    // Test (the settings' check of a key)

    @Test
    fun keyChecks() {
        assertEquals(Check.KEY_WORKS, KeyedSubtitles.keyCheckOf("subsource", response(200, SubtitleFixtures.SUBSOURCE_FILM_SEARCH)))
        assertEquals(Check.KEY_REFUSED, KeyedSubtitles.keyCheckOf("subsource", response(401, SubtitleFixtures.SUBSOURCE_WRONG_KEY)))
        assertEquals(Check.BUSY, KeyedSubtitles.keyCheckOf("subsource", response(429)))
        assertEquals(Check.NO_ANSWER, KeyedSubtitles.keyCheckOf("subsource", Response(0, ByteArray(0), "UnknownHostException")))
        assertEquals(Check.NO_ANSWER, KeyedSubtitles.keyCheckOf("subsource", response(503)))
        assertEquals(Check.KEY_WORKS, KeyedSubtitles.keyCheckOf("subdl", response(200, SubtitleFixtures.SUBDL_FILM)))
        assertEquals(Check.KEY_REFUSED, KeyedSubtitles.keyCheckOf("subdl", response(401, SubtitleFixtures.SUBDL_V2_WRONG_KEY)))
        assertEquals(Check.KEY_REFUSED, KeyedSubtitles.keyCheckOf("subdl", response(403, SubtitleFixtures.SUBDL_V1_WRONG_KEY)))
        assertEquals(Check.KEY_REFUSED, KeyedSubtitles.keyCheckOf("opensubtitlescom", response(403, SubtitleFixtures.OSCOM_WRONG_KEY)))
        assertEquals(Check.KEY_WORKS, KeyedSubtitles.keyCheckOf("opensubtitlescom", response(200, """{"data":[]}""")))
    }

    @Test
    fun openSubtitlesComSignIn() {
        val wrong = OnlineSubtitles.oscomLoginOf(401, """{"message":"Error, invalid username/password","status":401}""")
        assertTrue(wrong.problem is Problem.LoginRefused)
        assertNull(wrong.token)
        assertTrue(OnlineSubtitles.oscomLoginOf(403, SubtitleFixtures.OSCOM_WRONG_KEY).problem is Problem.KeyRefused)
        assertTrue(OnlineSubtitles.oscomLoginOf(0, "").problem is Problem.NoAnswer)
        val vip = OnlineSubtitles.oscomLoginOf(200, """{"user":{"allowed_downloads":1000},"base_url":"vip-api.opensubtitles.com","token":"abc.def","status":200}""")
        assertEquals("abc.def", vip.token)
        assertEquals("https://vip-api.opensubtitles.com/api/v1/", vip.base)
        assertNull(vip.problem)
        val plain = OnlineSubtitles.oscomLoginOf(200, """{"base_url":"api.opensubtitles.com","token":"t1","status":200}""")
        assertEquals("https://api.opensubtitles.com/api/v1/", plain.base)
        assertEquals(Problem.LoginFailed, OnlineSubtitles.oscomLoginOf(200, """{"status":200}""").problem)
    }
}
