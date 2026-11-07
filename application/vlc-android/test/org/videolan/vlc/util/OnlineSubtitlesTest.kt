package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** VLC Redesign, subtitles from the internet (OnlineSubtitles): the desktop's reading of names, the services'
 *  answers, the ranking and what a download unpacks to (no network) */
class OnlineSubtitlesTest {

    @Test
    fun namesOfFilms() {
        OnlineSubtitles.parseName("Home.Sweet.Home.Alone.2021.2160p.4K.WEB.x265-[YTS.MX].mkv").let {
            assertEquals("Home Sweet Home Alone", it.title)
            assertEquals(2021, it.year)
            assertEquals(-1, it.season)
        }
        // the year is not the first word: the film "2012"
        OnlineSubtitles.parseName("2012.2009.1080p.BluRay.mkv").let {
            assertEquals("2012", it.title)
            assertEquals(2009, it.year)
        }
        assertEquals("Sintel", OnlineSubtitles.parseName("Sintel.mp4").title)
        assertEquals("Big Buck Bunny", OnlineSubtitles.parseName("Big Buck Bunny (trailer).mov").title)
    }

    @Test
    fun namesOfEpisodes() {
        OnlineSubtitles.parseName("Breaking.Bad.S01E02.720p.WEB-DL.mkv").let {
            assertEquals("Breaking Bad", it.title)
            assertEquals(1, it.season)
            assertEquals(2, it.episode)
        }
        OnlineSubtitles.parseName("The Wand 1x07.mp4").let {
            assertEquals("The Wand", it.title)
            assertEquals(7, it.episode)
        }
        // a name with nothing of a title: the folder's
        assertEquals("Show Name", OnlineSubtitles.parseName("02.mkv", "Show Name").title)
    }

    @Test
    fun languages() {
        assertEquals("he", OnlineSubtitles.normalize("iw"))
        assertEquals("he", OnlineSubtitles.normalize("heb"))
        assertEquals("he", OnlineSubtitles.normalize("Hebrew"))
        assertEquals("pt-BR", OnlineSubtitles.normalize("pt_BR"))
        assertEquals("pt-BR", OnlineSubtitles.normalize("pt-rBR"))
        assertEquals("pt-BR", OnlineSubtitles.normalize("pob"))
        assertEquals("fr", OnlineSubtitles.normalize("fre"))
        assertEquals("de", OnlineSubtitles.normalize("ger"))
        assertEquals("", OnlineSubtitles.normalize(""))
        assertEquals("pt-BR", OnlineSubtitles.fromEnglishName("Portuguese (Brazilian)"))
        assertEquals("zh-TW", OnlineSubtitles.fromEnglishName("Chinese (Traditional)"))
        assertEquals("ar", OnlineSubtitles.fromEnglishName("Arabic"))
        assertEquals("heb", OnlineSubtitles.openSubtitlesCode("he"))
        assertEquals("fre", OnlineSubtitles.openSubtitlesCode("fr"))
        assertEquals("ger", OnlineSubtitles.openSubtitlesCode("de"))
        assertEquals("eng", OnlineSubtitles.openSubtitlesCode("en"))
        assertEquals("pob", OnlineSubtitles.openSubtitlesCode("pt-BR"))
        assertEquals("pt-br", OnlineSubtitles.oscomCode("pt-BR"))
        assertEquals("zh-cn", OnlineSubtitles.oscomCode("zh"))
        assertEquals("heb", OnlineSubtitles.iso639_2("he"))
    }

    @Test
    fun hashOfAFile() {
        // all zeros: the hash is the size alone
        val f = File.createTempFile("ostest", ".bin")
        try {
            f.writeBytes(ByteArray(131072))
            assertEquals("0000000000020000", OnlineSubtitles.openSubtitlesHash(f)?.first)
            // a 64-bit word of 1 at the start and at the end
            val b = ByteArray(131072)
            b[0] = 1
            b[131072 - 8] = 1
            f.writeBytes(b)
            assertEquals("0000000000020002", OnlineSubtitles.openSubtitlesHash(f)?.first)
            f.writeBytes(ByteArray(100))
            assertNull(OnlineSubtitles.openSubtitlesHash(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun openSubtitlesAnswer() {
        val json = """[
          {"IDSubtitleFile":"1","SubLanguageID":"heb","SubDownloadLink":"https://dl.opensubtitles.org/en/download/src-api/vrf-1/filead/1.gz",
           "SubFileName":"Sintel.2010.srt","MovieReleaseName":"* Sintel 2010 *","MatchedBy":"moviehash","SubHearingImpaired":"0",
           "SubDownloadsCnt":"1200","SubEncoding":"CP1255","MovieFPS":"24.000","SubFromTrusted":"1"},
          {"IDSubtitleFile":"2","SubLanguageID":"eng","SubDownloadLink":"https://dl.opensubtitles.org/x.gz","SubAutoTranslation":"1"},
          {"IDSubtitleFile":"3","SubLanguageID":"eng","SubDownloadLink":"http://evil.example/x.gz"},
          {"IDSubtitleFile":"4","SubLanguageID":"eng","SubDownloadLink":"https://dl.opensubtitles.org/4.gz","SubFileName":"Sintel.HI.srt","SubHearingImpaired":"1"},
          {"IDSubtitleFile":"1","SubLanguageID":"heb","SubDownloadLink":"https://dl.opensubtitles.org/1.gz"}
        ]"""
        val results = ArrayList<OnlineSubtitles.Result>()
        OnlineSubtitles.parseOpenSubtitles(json, results, HashSet())
        assertEquals(2, results.size)
        val he = results[0]
        assertEquals("he", he.language)
        assertEquals("Sintel 2010", he.release)
        assertTrue(he.exact)
        assertEquals("CP1255", he.encoding)
        assertEquals(1200, he.downloads)
        assertTrue(results[1].hearingImpaired)
    }

    @Test
    fun otherServicesAnswers() {
        val wizdom = OnlineSubtitles.parseWizdom("""[{"id":123,"versioname":"Sintel.2010.720p","score":2}]""")
        assertEquals(1, wizdom.size)
        assertEquals("he", wizdom[0].language)
        assertEquals("https://wizdom.xyz/api/files/sub/123", wizdom[0].url)
        val yify = OnlineSubtitles.parseYify("""<table><tr data-id="77"><td><span class="label label-success">5</span></td>
            <td><span class="sub-lang">Hebrew</span></td><td><a href="/subtitles/sintel-2010-hebrew-yify-77"><span class="text-muted">subtitle</span> Sintel.2010.BluRay</a></td>
            <td class="other-cell hi-subtitle"></td></tr></table>""")
        assertEquals(1, yify.size)
        assertEquals("he", yify[0].language)
        assertEquals("https://yifysubtitles.ch/subtitle/sintel-2010-hebrew-yify-77.zip", yify[0].url)
        assertTrue(yify[0].hearingImpaired)
        val oscom = OnlineSubtitles.parseOscom("""{"data":[
            {"attributes":{"language":"pt-BR","release":"Sintel 2010","download_count":50,"moviehash_match":true,"files":[{"file_id":9,"file_name":"a.srt"}]}},
            {"attributes":{"language":"en","ai_translated":true,"files":[{"file_id":10}]}}]}""")
        assertEquals(1, oscom.size)
        assertEquals("pt-BR", oscom[0].language)
        assertTrue(oscom[0].exact)
        assertEquals("9", oscom[0].url)
    }

    @Test
    fun ranking() {
        val media = OnlineSubtitles.Media("Sintel.2010.720p.BluRay.x264-GRP.mkv", "Sintel", 2010, -1, -1,
            listOf("sintel", "2010", "720p", "bluray", "x264", "grp"))
        val found = listOf(
            OnlineSubtitles.Result("a", "yify", "en", "Sintel.2010.1080p", url = "u"),
            OnlineSubtitles.Result("b", "opensubtitles", "he", "Other", url = "u", downloads = 5),
            OnlineSubtitles.Result("c", "opensubtitles", "he", "Sintel.2010.720p.BluRay.x264-GRP", url = "u"),
            OnlineSubtitles.Result("d", "opensubtitles", "he", "Anything", url = "u", exact = true)
        )
        val ranked = OnlineSubtitles.rank(found, media, listOf("he", "en"))
        // the user's first language first; in it the one made for this file, then the same release
        assertEquals(listOf("d", "c", "b", "a"), ranked.map { it.result.id })
    }

    private fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(data) } }.toByteArray()

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> for ((n, d) in files) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
    }.toByteArray()

    private val hebrew = "1\n00:00:01,000 --> 00:00:03,000\nשלום עולם, מה שלומך היום?\n\n2\n00:00:04,000 --> 00:00:06,000\nכתוביות בכל שפה, מימין לשמאל.\n"

    @Test
    fun downloadsUnpackedAndConverted() {
        val result = OnlineSubtitles.Result("x", "opensubtitles", "he", "Sintel", url = "u", encoding = "CP1255")
        // gzip of a Windows-1255 file
        val (gz, e1) = OnlineSubtitles.subtitleOf(gzip(hebrew.toByteArray(Charset.forName("windows-1255"))), result, "Sintel.mp4")
        assertNull(e1)
        assertEquals(hebrew, gz!!.text)
        assertEquals("srt", gz.extension)
        // a zip of two: the one in UTF-8 and named like the video
        val (z, e2) = OnlineSubtitles.subtitleOf(zip("other.srt" to hebrew.toByteArray(Charset.forName("windows-1255")),
            "Sintel.srt" to hebrew.toByteArray(Charsets.UTF_8), "readme.nfo" to ByteArray(10)), result.copy(encoding = ""), "Sintel.mp4")
        assertNull(e2)
        assertEquals(hebrew, z!!.text)
        // no subtitle in it, a RAR
        assertTrue(OnlineSubtitles.subtitleOf(zip("readme.nfo" to ByteArray(10)), result, "x").second is OnlineSubtitles.Problem.NoSubtitleInArchive)
        assertTrue(OnlineSubtitles.subtitleOf("Rar!\u001a\u0007\u0000xxxx".toByteArray(Charsets.ISO_8859_1), result, "x").second is OnlineSubtitles.Problem.Rar)
    }

    @Test
    fun encodings() {
        // no encoding said: the code page of the language
        assertEquals(hebrew, OnlineSubtitles.decodeText(hebrew.toByteArray(Charset.forName("windows-1255")), "", "he"))
        val arabic = "مرحبا بالعالم"
        assertEquals(arabic, OnlineSubtitles.decodeText(arabic.toByteArray(Charset.forName("windows-1256")), "", "ar"))
        // a BOM
        assertEquals("abc", OnlineSubtitles.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()), "", "en"))
        assertEquals("windows-1255", OnlineSubtitles.charsetNamed("CP1255"))
        assertEquals("ISO-8859-8", OnlineSubtitles.charsetNamed("iso-8859-8"))
        // Hebrew saved as UTF-8 by way of Windows-1252 (Addic7ed's): repaired
        val mangled = String(hebrew.toByteArray(Charset.forName("windows-1255")), Charset.forName("windows-1252"))
        assertEquals(hebrew, OnlineSubtitles.repairDoubleEncoding(mangled, "windows-1255"))
    }

    @Test
    fun adsRemoved() {
        val srt = "1\n00:00:00,000 --> 00:00:02,000\nAdvertise your product or brand here\ncontact www.OpenSubtitles.org today\n\n" +
            "2\n00:00:03,000 --> 00:00:05,000\nHello.\n\n3\n00:00:06,000 --> 00:00:08,000\nGoodbye.\n"
        val clean = OnlineSubtitles.withoutAds(srt)
        assertTrue("Hello." in clean)
        assertTrue("Advertise" !in clean)
    }

    @Test
    fun similarity() {
        assertEquals(1.0, OnlineSubtitles.titleSimilarity("Sintel", "sintel"), 0.0)
        assertTrue(OnlineSubtitles.titleSimilarity("Big Buck Bunny", "Big Buck") > 0.5)
        assertNotNull(OnlineSubtitles.simplified("Amélie & Co."))
        assertEquals("amelie and co", OnlineSubtitles.simplified("Amélie & Co."))
    }
}
