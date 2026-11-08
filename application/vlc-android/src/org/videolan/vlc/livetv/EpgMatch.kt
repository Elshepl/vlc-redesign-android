/*
 * VLC Redesign, Live TV: the automatic guide. The channels of a source without a guide (or those its guide doesn't
 * cover) matched to iptv-org's database of channels, then the free guides of the catalog that carry them chosen for
 * its countries (the same rules as the desktop's and the Samsung TV app's epgmatch.js). The database is read from
 * small tables, one a country (assets/livetv/epgmatch/<country>.txt: id, name, other names, by tabs; made from
 * iptv-org's channels.json, refreshed once a week by LiveTv), only those of the countries a source needs.
 *
 * A channel is matched by its tvg-id exactly (iptv-org's "BBCOne.uk", a feed's "@HD" left out), else by its name made
 * plain (case, accents, "HD", "FHD", "4K"..., a country before or after it: "UK: ", "|US|", "(DE)", and a "+1" that
 * shifts the guide by an hour) in the countries it hints at, the source's, the user's.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

object EpgMatch {

    // iptv-org's codes, and the ones playlists use, for the catalog's (ISO 3166)
    private val ALIAS = mapOf("uk" to "gb", "en" to "", "el" to "gr")
    // what a name says of its picture or its feed, never of the channel (every bracket escaped: Android's ICU)
    private val QUALITY = Regex("""(^|\s)(uhd|fhd|qhd|hd|sd|hq|lq|4k|8k|hdr|hevc|h ?26[45]|x26[45]|1080[pi]?|720p|576[pi]?|480p|360p|[0-9]{2}fps|backup|alt|multi|vip|raw|orig|original|geo ?blocked|not 24 7)(?=\s|$)""")
    private val MARKS = Regex("""[̀-ͯ]""")
    private val NOT_WORD = Regex("""[^a-z0-9À-￿+]+""")
    private val PREFIX = Regex("""^\s*[\[|(]?\s*([a-z]{2})\s*[\]|):\-]+\s*(.+)$""")
    private val BRACKETS = Regex("""[\[(]([^\])]*)[\])]""")
    private val PLUS = Regex("""^(.*\S)\s*\+\s*([1-9])$""")
    private val SUFFIX_COUNTRY = Regex("""^(.{2,}?)\s+([a-z]{2})$""")
    private val TWO_LETTERS = Regex("""^[a-z]{2}$""")
    private val ID_COUNTRY = Regex("""\.([a-z]{2})$""")

    fun country(code: String?): String {
        val c = (code ?: "").lowercase(Locale.ROOT)
        return ALIAS[c] ?: c
    }

    private fun plain(text: String) = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    private fun folded(s: String) = NOT_WORD.replace(s.replace("&", " and "), " ").replace(Regex("""\s+"""), " ").trim()

    /** A name made plain: its key ("bbc one"), compact ("bbcone"), the country it names, its "+1". */
    class Name(val key: String, val compact: String, val country: String, val shift: Int)

    /** known: the countries that can be named (null: any). */
    fun normalize(name: String, known: Set<String>? = null): Name {
        var s = plain(name)
        var cc = ""
        var shift = 0
        fun knownCountry(c: String): String {
            val k = country(c)
            return if (k.isNotEmpty() && (known == null || k in known)) k else ""
        }
        // "UK: BBC One", "|UK| BBC One", "[UK] BBC One", "UK - BBC One"
        PREFIX.find(s)?.let { m -> if (knownCountry(m.groupValues[1]).isNotEmpty()) { cc = knownCountry(m.groupValues[1]); s = m.groupValues[2] } }
        // what the brackets say of the picture or the country: away ("(HD)", "[DE]", "(Backup)")
        s = BRACKETS.replace(s) { m ->
            val t = folded(m.groupValues[1])
            if (TWO_LETTERS.matches(t) && knownCountry(t).isNotEmpty()) {
                if (cc.isEmpty()) cc = knownCountry(t)
                " "
            } else if (folded(QUALITY.replace(t, " ")).isNotEmpty()) " " + m.groupValues[1] + " " else " "
        }
        s = folded(s)
        repeat(3) {
            s = folded(QUALITY.replace(s, " "))
            // "BBC One +1", "Sky Cinema+2": an hour or two later
            PLUS.find(s)?.let { m -> shift = m.groupValues[2].toInt(); s = folded(m.groupValues[1]) }
            // "BBC One UK", "CNN US": a country after the name
            SUFFIX_COUNTRY.find(s)?.let { m -> if (knownCountry(m.groupValues[2]).isNotEmpty()) { if (cc.isEmpty()) cc = knownCountry(m.groupValues[2]); s = m.groupValues[1] } }
        }
        s = s.replace("+", " plus ").replace(Regex("""\s+"""), " ").trim()
        return Name(s, s.replace(" ", ""), cc, shift)
    }

    /** a tvg-id as iptv-org's: "BBCOne.uk@HD" -> "bbcone.uk" */
    fun idKey(id: String) = id.substringBefore('@').trim().lowercase(Locale.ROOT)

    fun countryOfId(id: String) = ID_COUNTRY.find(idKey(id))?.let { country(it.groupValues[1]) } ?: ""

    class Entry(val id: String, val names: List<String>, val country: String)

    /** A country's table: its entries by id and by compact name. */
    class Table(val country: String) {
        val byId = HashMap<String, Entry>()
        val byName = HashMap<String, Entry>()
        var size = 0
    }

    fun table(text: String, cc: String): Table {
        val t = Table(country(cc))
        for (line in text.split('\n')) {
            val parts = line.trimEnd('\r').split('\t')
            if (parts.size < 2 || parts[0].isEmpty()) continue
            val e = Entry(parts[0], parts.drop(1), t.country)
            t.byId[idKey(e.id)] = e
            for (n in e.names) {
                val k = normalize(n).compact
                // (the first channel of a name keeps it)
                if (k.isNotEmpty() && !t.byName.containsKey(k)) t.byName[k] = e
            }
            t.size++
        }
        return t
    }

    /**
     * The tables made from iptv-org's API (LiveTv reads its channels.json and guides.json a record at a time; the same
     * rules as the Samsung TV app's tools/tvdata.py): the channels on the air that some guide carries, a line each
     * (its id, its name, its other names), by country (ISO 3166: GB for iptv-org's UK). carried: null for every channel.
     */
    class TableBuilder(private val carried: Set<String>?) {
        private val byCountry = HashMap<String, MutableList<String>>()

        fun add(id: String, name: String, alt: List<String>, country: String, closed: Boolean) {
            val i = clean(id)
            if (closed || i.isEmpty() || (carried != null && i !in carried)) return
            val c = country(country)
            if (!TWO_LETTERS.matches(c)) return
            val names = (listOf(name) + alt).map { clean(it) }.filter { it.isNotEmpty() }.distinct()
            if (names.isEmpty()) return
            byCountry.getOrPut(c) { ArrayList() }.add((listOf(i) + names).joinToString("\t"))
        }

        val countries get() = byCountry.size

        /** each country's file, its lines sorted, and how many channels it has */
        fun files(): Map<String, Pair<String, Int>> = byCountry.mapValues { (_, lines) ->
            lines.sortedBy { it.lowercase(Locale.ROOT) }.joinToString("\n") + "\n" to lines.size
        }

        private fun clean(text: String) = text.replace('\t', ' ').trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * The tables a source's channels need, in the order they are tried: the countries their tvg-ids and names hint
     * at (the most named first), the source's own, the user's. known: the countries there are tables of.
     */
    fun countriesOf(channels: List<Channel>, countries: List<String>, user: String, known: Set<String>?): List<String> {
        val counts = HashMap<String, Int>()
        fun add(c: String) {
            val k = country(c)
            if (k.isNotEmpty() && (known == null || k in known)) counts[k] = (counts[k] ?: 0) + 1
        }
        for (c in channels) {
            add(countryOfId(c.tvgId))
            add(normalize(c.name, known).country)
        }
        val out = ArrayList<String>()
        fun push(c: String) {
            val k = country(c)
            if (k.isNotEmpty() && (known == null || k in known) && k !in out) out.add(k)
        }
        counts.keys.sortedWith(compareBy<String>({ -(counts[it] ?: 0) }, { it })).forEach { push(it) }
        countries.forEach { push(it) }
        push(user)
        return out
    }

    /** A channel matched: its iptv-org id, names, country, its "+1", by its id or its name. */
    class Match(val id: String, val name: String, val names: List<String>, val country: String, val shift: Int, val byId: Boolean)

    class Result(val matches: List<Match?>, val found: Int, val total: Int, val countries: Map<String, Int>)

    fun match(channels: List<Channel>, tables: Map<String, Table>, order: List<String>): Result {
        val all = ArrayList(order).apply { for (k in tables.keys) if (k !in this) add(k) }
        val known = tables.keys
        val out = ArrayList<Match?>()
        val countries = HashMap<String, Int>()
        var found = 0
        for (c in channels) {
            var e: Entry? = null
            var byId = false
            val id = idKey(c.tvgId)
            if (id.isNotEmpty()) for (k in all) { tables[k]?.byId?.get(id)?.let { e = it; byId = true }; if (e != null) break }
            val n = normalize(c.name, known)
            if (e == null && n.compact.isNotEmpty()) {
                for (k in listOf(n.country, countryOfId(c.tvgId)) + all) { e = tables[k]?.byName?.get(n.compact); if (e != null) break }
            }
            val m = e
            if (m == null) { out.add(null); continue }
            found++
            countries[m.country] = (countries[m.country] ?: 0) + 1
            out.add(Match(m.id, m.names.firstOrNull() ?: "", m.names, m.country, n.shift, byId))
        }
        return Result(out, found, channels.size, countries)
    }

    /** The keys a matched channel may have in a guide: its id, its names, and its names after its country ("uk bbc one"). */
    fun keys(m: Match?): List<String> {
        if (m == null) return emptyList()
        val out = LinkedHashSet<String>()
        fun add(t: String) = Xmltv.guideKey(t).takeIf { it.isNotEmpty() }?.let { out.add(it) }
        add(m.id)
        val cc = if (m.country == "gb") "uk" else m.country
        for (n in m.names) {
            add(n)
            if (cc.isNotEmpty()) add("$cc $n")
        }
        return out.toList()
    }

    // the free guides, in the order they are chosen for a country: iptv-epg.org has iptv-org's ids
    private val GUIDE_ORDER = listOf(Regex("iptv-epg\\.org", RegexOption.IGNORE_CASE), Regex("epgshare01", RegexOption.IGNORE_CASE),
        Regex("open-epg", RegexOption.IGNORE_CASE), Regex("epg\\.pw", RegexOption.IGNORE_CASE))

    class Guide(val url: String, val name: String, val country: String, val channels: Int)

    /**
     * The guides of the catalog to add for the countries matched ({gb: 12, us: 3}), one a country, the most matched
     * first. have: the guide links the source has already; dead: those not answering.
     */
    fun guides(countries: Map<String, Int>, catalog: List<JSONObject>, have: Collection<String>, dead: (String) -> Boolean = { false }): List<Guide> {
        val wanted = countries.filter { it.value > 0 }.keys.sortedWith(compareBy<String>({ -(countries[it] ?: 0) }, { it }))
        val out = ArrayList<Guide>()
        for (c in wanted) {
            val cc = c.uppercase(Locale.ROOT)
            var best: JSONObject? = null
            var rank = 99
            for (s in catalog) {
                if (s.optString("kind") != "epg" || s.optString("url").isEmpty() || dead(s.optString("id"))) continue
                val list = s.optJSONArray("countries") ?: continue
                if ((0 until list.length()).none { list.optString(it) == cc }) continue
                val r = GUIDE_ORDER.indexOfFirst { it.containsMatchIn(s.optString("url")) }.let { if (it < 0) GUIDE_ORDER.size else it }
                // (of two guides the same rank, the one of fewer channels: a lighter download)
                if (r < rank || (r == rank && best != null && s.optInt("channels") < best.optInt("channels"))) { rank = r; best = s }
            }
            val b = best ?: continue
            val url = b.optString("url")
            if (url !in have && out.none { it.url == url }) out.add(Guide(url, b.optString("name"), c, b.optInt("channels")))
        }
        return out
    }
}
