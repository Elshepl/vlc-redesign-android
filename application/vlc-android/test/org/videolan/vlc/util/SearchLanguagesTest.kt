package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.vlc.util.OnlineSubtitles.Query
import org.videolan.vlc.util.OnlineSubtitles.SearchLanguage

/** VLC Redesign, Settings › Subtitles › Languages to search (OnlineSubtitles): the list kept, completed with the
 *  preferred and the second language, the results kept to its languages, and fewer requests without "Every other
 *  language too" (no network) */
class SearchLanguagesTest {

    @Test
    fun theListKept() {
        val list = OnlineSubtitles.parseLanguages("he:1,en:1,fr:0")
        assertEquals(listOf(SearchLanguage("he", true), SearchLanguage("en", true), SearchLanguage("fr", false)), list)
        assertEquals("he:1,en:1,fr:0", OnlineSubtitles.formatLanguages(list))
        // the old codes, twice the same, nothing: read as the app's codes, once
        assertEquals(listOf(SearchLanguage("he", true), SearchLanguage("pt-BR", false)), OnlineSubtitles.parseLanguages("iw:1,heb:0,pob:0,,:1"))
        assertTrue(OnlineSubtitles.parseLanguages(null).isEmpty())
    }

    @Test
    fun theListCompleted() {
        // never set: the preferred and the second language on, the languages chosen in the player off
        assertEquals(listOf(SearchLanguage("he", true), SearchLanguage("en", true), SearchLanguage("ar", false)),
            OnlineSubtitles.completeLanguages(null, "he", "en", listOf("ar")))
        // the preferred language is always in it, at its top when it was missing; the second one after it
        assertEquals(listOf(SearchLanguage("he", true), SearchLanguage("fr", false)),
            OnlineSubtitles.completeLanguages(listOf(SearchLanguage("fr", false)), "he", "", emptyList()))
        assertEquals(listOf(SearchLanguage("he", true), SearchLanguage("en", true), SearchLanguage("fr", false)),
            OnlineSubtitles.completeLanguages(listOf(SearchLanguage("he", true), SearchLanguage("fr", false)), "he", "en", emptyList()))
        // the user's order and switches are kept
        val mine = listOf(SearchLanguage("en", true), SearchLanguage("he", false))
        assertEquals(mine, OnlineSubtitles.completeLanguages(mine, "he", "en", listOf("ar")))
    }

    @Test
    fun resultsOfTheLanguagesOn() {
        assertTrue(OnlineSubtitles.inLanguages("he", listOf("he", "en")))
        assertTrue(OnlineSubtitles.inLanguages("pt-BR", listOf("pt")))
        assertTrue(OnlineSubtitles.inLanguages("pt", listOf("pt-BR")))
        assertFalse(OnlineSubtitles.inLanguages("fr", listOf("he", "en")))
        // a service of one language is asked only when it is on
        val wizdom = OnlineSubtitles.provider("wizdom")!!
        val on = OnlineSubtitles.completeLanguages(listOf(SearchLanguage("en", true), SearchLanguage("he", false)), "en", "", emptyList())
            .filter { it.on }.map { it.code }
        assertFalse(OnlineSubtitles.languageAllows(wizdom, on))
    }

    @Test
    fun fewerRequests() {
        val q = Query(imdb = "tt1727587", title = "Sintel", year = 2010, hash = "0123456789abcdef", size = 21697080L, languages = listOf("he", "en"))
        // by the hash, then by the IMDb id in each language; "every other language too": in every language as well
        val only = OnlineSubtitles.openSubtitlesUrls(q.copy(everyOther = false))
        val every = OnlineSubtitles.openSubtitlesUrls(q.copy(everyOther = true))
        assertEquals(3, only.size)
        assertEquals(4, every.size)
        assertTrue(only.any { "sublanguageid-heb" in it } && only.any { "sublanguageid-eng" in it })
        assertTrue(every.any { "imdbid-1727587" in it && "sublanguageid" !in it })
        // one language less: a request less
        assertEquals(2, OnlineSubtitles.openSubtitlesUrls(q.copy(languages = listOf("he"), everyOther = false)).size)
        // no language at all: every language (nothing else to ask)
        assertEquals(2, OnlineSubtitles.openSubtitlesUrls(q.copy(languages = emptyList(), everyOther = false)).size)
    }
}
