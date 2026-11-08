/*
 * VLC Redesign, Live TV: the catalog for the user first (the same rules as the desktop's and the Samsung TV app's):
 * an entry scored by the user's country (4) and each of their languages (2); Recommended: the country's free lists
 * and guide, the free services there, iptv-org in the user's languages, never one not answering; not answering (it
 * failed twice when it was added or updated) always last. Pure functions of the catalog's JSON (unit tests).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import org.json.JSONObject
import java.util.Locale

/** What the app saw of a catalog entry: worked (and its channels), its failures in a row, when. */
data class CatalogHealth(val ok: Boolean, val channels: Int, val failures: Int, val at: Long) {
    val notAnswering get() = failures >= 2

    fun toJson(): JSONObject = JSONObject().put("ok", ok).put("channels", channels).put("failures", failures).put("at", at)

    companion object {
        fun of(o: JSONObject?) = o?.let { CatalogHealth(it.optBoolean("ok"), it.optInt("channels"), it.optInt("failures"), it.optLong("at")) }

        /** after an update: works (its channels), or one more failure */
        fun next(old: CatalogHealth?, ok: Boolean, channels: Int, now: Long) =
            if (ok) CatalogHealth(true, channels, 0, now) else CatalogHealth(false, old?.channels ?: 0, (old?.failures ?: 0) + 1, now)
    }
}

object TvCatalogRules {

    private fun strings(e: JSONObject, key: String) = e.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

    /** How much an entry is the user's: their country (4), each of their languages (2). */
    fun score(e: JSONObject, cc: String, langs: Collection<String>): Int {
        var s = if (cc.isNotEmpty() && strings(e, "countries").any { it.equals(cc, true) }) 4 else 0
        for (l in strings(e, "languages")) if (l.lowercase(Locale.ROOT) in langs) s += 2
        return s
    }

    fun recommended(e: JSONObject, cc: String, langs: Collection<String>, dead: Boolean): Boolean {
        if (dead) return false
        if (cc.isNotEmpty() && strings(e, "countries").any { it.equals(cc, true) }) return true
        return e.optString("id").startsWith("iptv-org-lang-") && strings(e, "languages").any { it.lowercase(Locale.ROOT) in langs }
    }

    /**
     * The entries in their order: not answering last; Recommended: the channel lists before the guides; for you: the
     * most the user's first; then by name (or the most channels).
     */
    fun order(list: List<JSONObject>, sort: String, recommended: Boolean, cc: String, langs: Collection<String>, dead: Set<String>): List<JSONObject> =
        list.sortedWith { a, z ->
            val da = a.optString("id") in dead
            val dz = z.optString("id") in dead
            if (da != dz) return@sortedWith if (da) 1 else -1
            val ga = a.optString("kind") == "epg"
            val gz = z.optString("kind") == "epg"
            if (recommended && ga != gz) return@sortedWith if (ga) 1 else -1
            if (sort == "you") {
                val d = score(z, cc, langs) - score(a, cc, langs)
                if (d != 0) return@sortedWith d
            }
            if (sort == "channels") z.optInt("channels") - a.optInt("channels")
            else a.optString("name").lowercase(Locale.ROOT).compareTo(z.optString("name").lowercase(Locale.ROOT))
        }

    /** The user's country: the device's region ("he-IL" -> IL). */
    fun userCountry(locale: Locale = Locale.getDefault()) = locale.country.uppercase(Locale.ROOT)
}
