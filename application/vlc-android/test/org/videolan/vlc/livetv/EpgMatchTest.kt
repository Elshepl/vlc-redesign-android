package org.videolan.vlc.livetv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * VLC Redesign, Live TV's automatic guide (EpgMatch): names made plain, the tables, the countries to read, the matches
 * by tvg-id and by name, the guide keys of a match, the guides chosen from the catalog (the Samsung TV app's
 * epgmatch.test.js, the same fixtures: lines of the shipped tables), the tables made from iptv-org's API, and the
 * shipped tables themselves.
 */
class EpgMatchTest {

    private fun file(path: String): File = listOf(path, "application/vlc-android/$path").map { File(it) }.first { it.exists() }

    private fun tables(vararg cc: String) = cc.associateWith { EpgMatch.table(file("test/fixtures/livetv/epgmatch/$it.txt").readText(), it) }

    private val known = setOf("gb", "us", "de", "il", "fr")

    private fun ch(name: String, id: String = "") = Channel().also { it.name = name; it.tvgId = id }

    @Test
    fun namesMadePlain() {
        fun n(s: String) = EpgMatch.normalize(s, known).let { "${it.key}|${it.country}|${it.shift}" }
        assertEquals("bbc one||0", n("BBC One HD"))
        assertEquals("bbc one|gb|0", n("UK: BBC One FHD"))
        assertEquals("bbc one|gb|0", n("|UK| BBC One"))
        assertEquals("das erste|de|0", n("[DE] Das Erste (Backup)"))
        assertEquals("das erste|de|0", n("Das Erste (DE) 1080p"))
        assertEquals("cnn|us|0", n("CNN US"))
        assertEquals("bbc one||1", n("BBC One +1"))
        assertEquals("channel 4||1", n("Channel 4+1 HD"))
        assertEquals("tele education||0", n("Télé Éducation"))
        assertEquals("ערוץ 13||0", n("ערוץ 13 HD"))
        assertEquals("at and t sportsnet||0", n("AT&T SportsNet"))
        // (a word that isn't a country, or a country there is no table of: kept)
        assertEquals("e4||0", n("E4"))
        assertEquals("sky news arabia ar||0", n("Sky News Arabia AR"))
        assertEquals("bbcone", EpgMatch.normalize("BBC One HD").compact)
    }

    @Test
    fun aTable() {
        val gb = tables("gb").getValue("gb")
        assertEquals(7, gb.size)
        assertEquals("BBCOne.uk", gb.byId["bbcone.uk"]!!.id)
        assertEquals("BBCOne.uk", gb.byName["bbc1"]!!.id)
        assertEquals("ITV1.uk", gb.byName["itv"]!!.id)
        assertEquals("bbcone.uk", EpgMatch.idKey("BBCOne.uk@HD"))
        assertEquals("de", EpgMatch.countryOfId("ZDF.de@SD"))
        assertEquals("gb", EpgMatch.countryOfId("BBCOne.uk"))
        assertEquals("", EpgMatch.countryOfId("no-country"))
    }

    @Test
    fun theTablesToRead() {
        val channels = listOf(ch("BBC One", "BBCOne.uk"), ch("UK: ITV 1"), ch("CNN US"), ch("ZDF", "zdf.de"), ch("Unknown"))
        assertEquals(listOf("gb", "de", "us", "fr", "il"), EpgMatch.countriesOf(channels, listOf("FR", "GB"), "IL", known))
        // (a country there is no table of: not asked)
        assertEquals(emptyList<String>(), EpgMatch.countriesOf(listOf(ch("XX: Something")), emptyList(), "zz", known))
    }

    @Test
    fun channelsMatchedByIdElseByName() {
        val channels = listOf(
            ch("whatever", "BBCOne.uk@HD"),   // its id, exactly
            ch("UK | BBC Two FHD"),           // its name, the country it names
            ch("ITV HD"),                     // ITV1's other name, in a table read
            ch("Das Erste"),
            ch("ARD (DE)"),
            ch("Channel 4 +1"),               // an hour later
            ch("CNN"),
            ch("ערוץ 13"),                    // in Hebrew
            ch("My Own Channel"))             // nothing
        val r = EpgMatch.match(channels, tables("gb", "de", "us", "il"), listOf("gb", "de", "us", "il"))
        assertEquals(9, r.total)
        assertEquals(8, r.found)
        assertEquals(listOf("BBCOne.uk", "BBCTwo.uk", "ITV1.uk", "DasErste.de", "DasErste.de", "Channel4.uk", "CNN.us", "Channel13.il", null), r.matches.map { it?.id })
        assertTrue(r.matches[0]!!.byId)
        assertFalse(r.matches[1]!!.byId)
        assertEquals(1, r.matches[5]!!.shift)
        assertEquals(mapOf("gb" to 4, "de" to 2, "us" to 1, "il" to 1), r.countries)
    }

    @Test
    fun aNameInTwoCountries() {
        val two = mapOf("gb" to EpgMatch.table("News1.uk\tNews One\n", "gb"), "us" to EpgMatch.table("News1.us\tNews One\n", "us"))
        assertEquals("News1.us", EpgMatch.match(listOf(ch("News One US")), two, listOf("gb", "us")).matches[0]!!.id)
        assertEquals("News1.us", EpgMatch.match(listOf(ch("News One")), two, listOf("us", "gb")).matches[0]!!.id)
        assertEquals("News1.uk", EpgMatch.match(listOf(ch("News One")), two, listOf("gb", "us")).matches[0]!!.id)
    }

    @Test
    fun theGuideKeysOfAMatch() {
        val r = EpgMatch.match(listOf(ch("BBC One HD"), ch("UK: ITV 1")), tables("gb"), listOf("gb"))
        val keys = EpgMatch.keys(r.matches[0])
        assertEquals(listOf("bbcone uk", "bbc one", "uk bbc one", "bbc1", "uk bbc1", "bbc television", "uk bbc television",
            "bbc television service", "uk bbc television service"), keys)
        assertEquals(emptyList<String>(), EpgMatch.keys(null))
        // a guide of iptv-org's ids ("BBCOne.uk"), one whose display names carry the country ("UK - ITV"): found
        assertTrue(Xmltv.guideKey("BBCOne.uk") in keys)
        assertTrue(Xmltv.guideKey("UK - ITV") in EpgMatch.keys(r.matches[1]))
    }

    @Test
    fun theGuidesChosen() {
        val catalog = listOf(
            JSONObject().put("id", "epgshare01-uk").put("kind", "epg").put("url", "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz").put("countries", JSONArray(listOf("GB"))).put("channels", 400).put("name", "epgshare01 UK"),
            JSONObject().put("id", "iptvepg-gb").put("kind", "epg").put("url", "https://iptv-epg.org/files/epg-gb.xml.gz").put("countries", JSONArray(listOf("GB"))).put("channels", 933).put("name", "iptv-epg.org: United Kingdom"),
            JSONObject().put("id", "openepg-de").put("kind", "epg").put("url", "https://www.open-epg.com/files/germany1.xml.gz").put("countries", JSONArray(listOf("DE"))).put("channels", 300).put("name", "Open-EPG: Germany"),
            JSONObject().put("id", "epgpw-de").put("kind", "epg").put("url", "https://epg.pw/xmltv/epg_DE.xml.gz").put("countries", JSONArray(listOf("DE"))).put("channels", 100).put("name", "EPG.PW: Germany"),
            JSONObject().put("id", "epgpw-us").put("kind", "epg").put("url", "https://epg.pw/xmltv/epg_US.xml.gz").put("countries", JSONArray(listOf("US"))).put("channels", 900).put("name", "EPG.PW: US"),
            JSONObject().put("id", "gb-m3u").put("kind", "m3u").put("url", "https://example.invalid/gb.m3u").put("countries", JSONArray(listOf("GB"))))
        // (EPG.PW's US guide not answering)
        val g = EpgMatch.guides(mapOf("de" to 2, "gb" to 5, "us" to 1, "il" to 1), catalog, emptyList()) { it == "epgpw-us" }
        assertEquals(listOf("https://iptv-epg.org/files/epg-gb.xml.gz", "https://www.open-epg.com/files/germany1.xml.gz"), g.map { it.url })
        assertEquals("gb", g[0].country)
        assertEquals(933, g[0].channels)
        // the source has the UK's already
        assertEquals(emptyList<String>(), EpgMatch.guides(mapOf("gb" to 5), catalog, listOf("https://iptv-epg.org/files/epg-gb.xml.gz")).map { it.url })
        // answering again: chosen
        assertEquals(listOf("https://epg.pw/xmltv/epg_US.xml.gz"), EpgMatch.guides(mapOf("us" to 1), catalog, emptyList()).map { it.url })
    }

    /** iptv-org's channels.json and guides.json read as LiveTv reads them (a record at a time) into the tables */
    @Test
    fun theTablesMadeFromIptvOrg() {
        val guides = JSONArray(file("test/fixtures/livetv/iptv-org/guides.json").readText())
        val carried = (0 until guides.length()).mapNotNull { guides.getJSONObject(it).optString("channel").takeIf { c -> c.isNotEmpty() && c != "null" } }.toSet()
        val b = EpgMatch.TableBuilder(carried)
        val channels = JSONArray(file("test/fixtures/livetv/iptv-org/channels.json").readText())
        for (i in 0 until channels.length()) {
            val c = channels.getJSONObject(i)
            val alt = c.optJSONArray("alt_names")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            b.add(c.optString("id"), c.optString("name"), alt, c.optString("country"), !c.isNull("closed") || !c.isNull("replaced_by"))
        }
        val files = b.files()
        // closed, replaced, not carried, no country, no name: left out; iptv-org's UK as GB
        assertEquals(setOf("gb", "de", "il"), files.keys)
        assertEquals("BBCOne.uk\tBBC One\tBBC1\tBBC Television Service\n" to 1, files["gb"])
        assertEquals("ARDalpha.de\tARD alpha\nZDF.de\tZDF\tZweites Deutsches Fernsehen\n" to 2, files["de"])
        assertEquals("Kan11.il\tKan 11\tכאן 11\n", files["il"]!!.first)
        val il = EpgMatch.table(files["il"]!!.first, "il")
        assertEquals("Kan11.il", EpgMatch.match(listOf(ch("כאן 11 HD")), mapOf("il" to il), listOf("il")).matches[0]!!.id)
        // every channel, when no guides were read
        assertEquals(2, EpgMatch.TableBuilder(null).apply { add("NotCarried.il", "Not Carried", emptyList(), "IL", false); add("Kan11.il", "Kan 11", emptyList(), "il", false) }.files()["il"]!!.second)
    }

    /** the app's own tables (made as tools/tvdata.py match makes the Samsung TV app's): every country file there and readable */
    @Test
    fun theShippedTables() {
        val dir = file("assets/livetv/epgmatch")
        val index = JSONObject(File(dir, "index.json").readText()).getJSONObject("countries")
        val countries = index.keys().asSequence().toList()
        assertTrue(countries.size.toString(), countries.size > 100)
        for (cc in countries) {
            assertTrue(cc, Regex("^[a-z]{2}$").matches(cc))
            assertTrue(cc, File(dir, "$cc.txt").exists())
        }
        assertEquals(index.getInt("gb"), EpgMatch.table(File(dir, "gb.txt").readText(), "gb").size)
        assertEquals(index.getInt("us"), EpgMatch.table(File(dir, "us.txt").readText(), "us").size)
        assertNull(dir.listFiles()!!.firstOrNull { it.name.endsWith(".txt") && it.nameWithoutExtension !in countries })
        // (the BBC's flagship there, by its name)
        val gb = EpgMatch.table(File(dir, "gb.txt").readText(), "gb")
        assertEquals("BBCOne.uk", EpgMatch.match(listOf(ch("UK: BBC One HD")), mapOf("gb" to gb), listOf("gb")).matches[0]?.id)
    }
}
