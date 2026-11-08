package org.videolan.vlc.livetv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * VLC Redesign, Live TV's catalog for the user first (TvCatalogRules, CatalogHealth): Recommended, the user's country
 * then their languages first, not answering last (the Samsung TV app's livetv.test.js, the same entries).
 */
class TvCatalogRulesTest {

    private fun e(id: String, name: String, kind: String, countries: List<String>, languages: List<String>, channels: Int = 0) =
        JSONObject().put("id", id).put("name", name).put("kind", kind).put("countries", JSONArray(countries)).put("languages", JSONArray(languages)).put("channels", channels)

    private val list = listOf(
        e("world", "A World List", "m3u", emptyList(), listOf("en"), 900),
        e("il-fta", "Israel free-to-air", "m3u", listOf("IL"), listOf("he"), 12),
        e("epg-il", "Guide: Israel", "epg", listOf("IL"), listOf("he")),
        e("iptv-org-lang-he", "iptv-org: Hebrew", "m3u", emptyList(), listOf("he"), 40),
        e("iptv-org-lang-ja", "iptv-org: Japanese", "m3u", listOf("JP"), listOf("ja"), 14),
        e("fast-il", "Free channels in Israel", "m3u", listOf("IL", "US"), listOf("en"), 30),
        e("dead-il", "An Old List", "m3u", listOf("IL"), listOf("he"), 50))
    private val langs = listOf("he", "en")
    private val dead = setOf("dead-il")

    @Test
    fun forYouRecommendedNotAnsweringLast() {
        val rec = list.filter { TvCatalogRules.recommended(it, "IL", langs, it.optString("id") in dead) }
        // (the same score: by name)
        assertEquals(listOf("fast-il", "il-fta", "iptv-org-lang-he", "epg-il"), TvCatalogRules.order(rec, "you", true, "IL", langs, dead).map { it.optString("id") })
        assertEquals(listOf("fast-il", "epg-il", "il-fta", "world", "iptv-org-lang-he", "iptv-org-lang-ja", "dead-il"),
            TvCatalogRules.order(list, "you", false, "IL", langs, dead).map { it.optString("id") })
        assertEquals(listOf("world", "iptv-org-lang-he"), TvCatalogRules.order(list, "channels", false, "IL", langs, dead).map { it.optString("id") }.take(2))
        // A-Z: by name, the one not answering still last
        val az = TvCatalogRules.order(list, "name", false, "IL", langs, dead).map { it.optString("id") }
        assertEquals(listOf("world", "fast-il", "epg-il", "iptv-org-lang-he", "iptv-org-lang-ja", "il-fta", "dead-il"), az)
        assertEquals(6, TvCatalogRules.score(list[1], "IL", langs))
        assertEquals(0, TvCatalogRules.score(list[4], "IL", langs))
        // (no country known: the languages only)
        assertEquals(2, TvCatalogRules.score(list[1], "", langs))
        assertFalse(TvCatalogRules.recommended(list[0], "IL", langs, false))
        assertFalse(TvCatalogRules.recommended(list[1], "IL", langs, true))
    }

    @Test
    fun theUsersCountry() {
        assertEquals("IL", TvCatalogRules.userCountry(Locale("he", "IL")))
        assertEquals("GB", TvCatalogRules.userCountry(Locale.UK))
        assertEquals("", TvCatalogRules.userCountry(Locale("en")))
    }

    @Test
    fun anEntrysHealth() {
        val first = CatalogHealth.next(null, false, 0, 100)
        assertEquals(1, first.failures)
        assertFalse(first.notAnswering)
        val second = CatalogHealth.next(first, false, 0, 200)
        assertTrue(second.notAnswering)
        // working again: answering, its channels
        val again = CatalogHealth.next(second, true, 42, 300)
        assertEquals(CatalogHealth(true, 42, 0, 300), again)
        assertFalse(again.notAnswering)
        // a failure keeps the channels it had
        assertEquals(42, CatalogHealth.next(again, false, 0, 400).channels)
        assertEquals(second, CatalogHealth.of(second.toJson()))
        assertNull(CatalogHealth.of(null))
    }
}
