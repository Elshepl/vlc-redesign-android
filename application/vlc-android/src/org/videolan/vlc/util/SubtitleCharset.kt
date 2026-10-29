/*
 * VLC Redesign: the character set of a subtitle file.
 *
 * libvlc 3 on Android only knows UTF-8 (and UTF-16 with a byte order mark): any other file was read as
 * Windows-1252, so a Hebrew, Arabic, Russian, Greek, Thai, Japanese, Chinese or Korean SRT came out as
 * Latin letters. The app reads the subtitle files of a media itself, guesses their character set from their
 * bytes (a unigram model of each script's letters) and from the language hints it has (the language in the
 * file name, the preferred subtitle language, the app's and the system's languages), and tells libvlc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

object SubtitleCharset {
    private const val TAG = "VLC/SubtitleCharset"
    private const val MAX_READ = 512 * 1024
    val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "sub", "txt", "vtt", "smi", "sami", "mpl", "jss", "psb", "rt", "pjs", "aqt", "usf", "idx")

    /** A candidate: the Java charset, the name libvlc's iconv knows, the language codes it is the usual one for. */
    private class Candidate(val java: String, val vlc: String, val langs: Set<String>, val score: (String, ByteArray) -> Double)

    // letter frequencies (%) of the languages, the most used letters; the others of the script get a small one
    private val HEBREW = freq("י11.1 ו10.4 ה9.0 ל7.4 א6.3 מ6.0 ר5.6 ת5.2 ב4.7 נ4.6 ש4.3 ע3.3 ם2.6 ד2.6 כ2.5 ח2.3 ק2.0 פ1.5 ן1.2 ט1.1 ג1.0 ס1.0 צ0.9 ז0.8 ך0.6 ף0.3 ץ0.2")
    private val RUSSIAN = freq("о11.0 е8.5 а8.0 и7.4 н6.7 т6.3 с5.5 р4.7 в4.5 л4.4 к3.5 м3.2 д3.0 п2.8 у2.6 я2.0 ы1.9 ь1.7 г1.7 з1.6 б1.6 ч1.4 й1.2 х1.0 ж0.9 і1.0 ї0.4 є0.4 ш0.7 ю0.6 ц0.5 щ0.4 э0.3 ф0.3 ґ0.1 ў0.3 ё0.1 ъ0.04")
    private val ARABIC = freq("ا12.5 ل11.0 ي6.5 م6.0 و5.8 ن5.6 ر4.4 ه4.0 ت4.0 ب3.6 ع3.0 ف2.7 د2.7 ة2.5 ك2.5 س2.3 ق2.2 أ2.0 ح1.8 ج1.2 ى1.0 ش1.0 ی1.0 ک1.0 گ0.6 پ0.5 چ0.4 ژ0.1 ص0.9 إ0.8 ط0.8 ذ0.6 ض0.6 خ0.7 ز0.5 ث0.5 غ0.4 ء0.4 ئ0.3 ظ0.2 ؤ0.2 آ0.2 ،0.8 ؟0.3")
    private val GREEK = freq("α11.0 ο9.8 ι8.7 ε8.0 τ8.0 ν6.6 σ5.5 ς2.0 ρ4.8 κ4.0 π4.0 η3.6 υ3.6 μ3.2 λ2.6 γ1.8 ω1.7 δ1.7 θ1.3 χ1.2 φ0.8 β0.7 ξ0.5 ζ0.4 ψ0.15")
    private val TURKISH = freq("ı5.1 ü2.0 ş1.8 ç1.2 ğ1.1 ö0.8 â0.1 î0.1 û0.1")
    private val CENTRAL = freq("á1.5 é1.5 í1.2 ó1.2 ú0.6 ý0.8 č1.0 ř1.0 š1.0 ž0.9 ě1.2 ů0.5 ą1.0 ę1.0 ć0.5 ł1.5 ń0.5 ś0.6 ź0.1 ż0.8 ő0.5 ű0.2 ö0.6 ü0.6 ă1.0 â0.6 î0.6 ș1.0 ş1.0 ț0.8 ţ0.8 ä0.3 ô0.2 ľ0.3 ĺ0.1 ŕ0.1 đ0.3 ë0.3")
    private val BALTIC = freq("ą1.0 č1.0 ę0.6 ė1.0 į0.8 š1.2 ų0.8 ū0.8 ž0.8 ā2.0 ē1.5 ģ0.3 ī1.5 ķ0.3 ļ0.5 ņ0.5 ō0.1 ŗ0.1 õ0.6 ä1.2 ö0.3 ü0.3")
    private val WESTERN = freq("é2.0 è0.6 à0.8 ç0.4 ê0.4 â0.2 ô0.2 î0.2 û0.1 ë0.1 ï0.1 ü0.6 ä0.6 ö0.5 ß0.3 ñ0.4 á0.6 í0.5 ó0.6 ú0.3 ã0.5 õ0.2 å0.6 ø0.4 æ0.3 ì0.1 ò0.1 ù0.1 ¿0.1 ¡0.1 º0.1 ª0.1 «0.1 »0.1 ’0.3 “0.2 ”0.2 –0.2 …0.1")

    private fun freq(s: String): Map<Char, Double> = s.split(' ').associate { it[0] to it.substring(1).toDouble() / 100.0 }

    private fun unigram(table: Map<Char, Double>, script: (Char) -> Boolean, strip: Boolean = false): (String, ByteArray) -> Double = { text, _ ->
        var sum = 0.0
        var n = 0
        for (raw in text) {
            if (raw.code < 0x80) continue
            n++
            var c = raw.lowercaseChar()
            if (strip) Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull()?.let { if (it.code >= 0x80) c = it }
            sum += ln(table[c] ?: if (script(c)) 0.0005 else if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) 0.0002 else 0.00001)
        }
        if (n == 0) 0.0 else sum / n
    }

    private fun inRange(c: Char, a: Int, b: Int) = c.code in a..b
    private val HEBREW_SCRIPT = { c: Char -> inRange(c, 0x5D0, 0x5EA) }
    private val CYRILLIC_SCRIPT = { c: Char -> inRange(c, 0x400, 0x4FF) }
    private val ARABIC_SCRIPT = { c: Char -> inRange(c, 0x600, 0x6FF) }
    private val GREEK_SCRIPT = { c: Char -> inRange(c, 0x370, 0x3FF) }
    private val LATIN_SCRIPT = { c: Char -> Character.isLetter(c) && c.code < 0x250 }

    /** CJK and Thai: decoded only when the bytes are valid; scripts weighted, common ranges preferred */
    private fun cjk(kana: Double, hangul: Double, han: Double, common: ((Char) -> Boolean)? = null): (String, ByteArray) -> Double = { text, _ ->
        var sum = 0.0
        var n = 0
        for (c in text) {
            if (c.code < 0x80) continue
            n++
            val p = when {
                inRange(c, 0x3040, 0x30FF) -> kana
                inRange(c, 0xAC00, 0xD7A3) -> hangul
                inRange(c, 0x4E00, 0x9FFF) -> if (common == null || common(c)) han else han / 10
                inRange(c, 0x3000, 0x303F) || inRange(c, 0xFF00, 0xFFEF) -> 0.01
                else -> 0.00001
            }
            sum += ln(p)
        }
        if (n == 0) 0.0 else sum / n
    }

    private val CANDIDATES = listOf(
            Candidate("windows-1255", "CP1255", setOf("he", "iw", "yi", "heb", "hebrew"), unigram(HEBREW, HEBREW_SCRIPT)),
            Candidate("windows-1256", "CP1256", setOf("ar", "fa", "ur", "ps", "ckb", "ku", "ara", "arabic", "per", "fas", "persian", "farsi", "urd", "urdu"), unigram(ARABIC, ARABIC_SCRIPT)),
            Candidate("windows-1251", "CP1251", setOf("ru", "uk", "be", "bg", "mk", "sr", "kk", "rus", "russian", "ukr", "bul", "srp", "mkd", "bel"), unigram(RUSSIAN, CYRILLIC_SCRIPT)),
            Candidate("windows-1253", "CP1253", setOf("el", "gre", "ell", "greek"), unigram(GREEK, GREEK_SCRIPT, strip = true)),
            Candidate("windows-1254", "CP1254", setOf("tr", "az", "tur", "turkish"), unigram(TURKISH, LATIN_SCRIPT)),
            Candidate("windows-1250", "CP1250", setOf("pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs", "sq", "pol", "cze", "ces", "slo", "slk", "hun", "rum", "ron", "hrv", "slv", "polish", "czech", "hungarian", "romanian", "croatian"), unigram(CENTRAL, LATIN_SCRIPT)),
            Candidate("windows-1257", "CP1257", setOf("lt", "lv", "et", "lit", "lav", "est"), unigram(BALTIC, LATIN_SCRIPT)),
            Candidate("windows-1252", "CP1252", setOf("en", "fr", "de", "es", "it", "pt", "nl", "sv", "da", "no", "nb", "fi", "ca", "eng", "fre", "fra", "ger", "deu", "spa", "ita", "por", "dut", "nld", "swe", "dan", "nor", "fin"), unigram(WESTERN, LATIN_SCRIPT)),
            Candidate("TIS-620", "TIS-620", setOf("th", "tha", "thai"), { text, _ -> cjkLike(text) { inRange(it, 0xE00, 0xE7F) } }),
            Candidate("Shift_JIS", "SHIFT_JIS", setOf("ja", "jpn", "japanese"), cjk(0.03, 0.00001, 0.005)),
            Candidate("EUC-KR", "EUC-KR", setOf("ko", "kor", "korean"), cjk(0.00001, 0.01, 0.0005)),
            // the common characters: level 1 of GB2312 (lead byte B0-D7), of Big5 (A4-C6)
            Candidate("GBK", "GB18030", setOf("zh", "chi", "zho", "chs", "sc", "gb", "chinese", "zh_cn", "zh_sg"), cjk(0.0001, 0.00001, 0.008, leadIn("GBK", 0xB0, 0xD7))),
            Candidate("Big5", "BIG5", setOf("zh_tw", "zh_hk", "cht", "tc", "big5"), cjk(0.0001, 0.00001, 0.008, leadIn("Big5", 0xA4, 0xC6))),
    )

    private fun leadIn(charset: String, from: Int, to: Int): (Char) -> Boolean = { c ->
        val b = try { c.toString().toByteArray(Charset.forName(charset)) } catch (e: Exception) { ByteArray(0) }
        b.size == 2 && (b[0].toInt() and 0xFF) in from..to
    }

    private fun cjkLike(text: String, script: (Char) -> Boolean): Double {
        var sum = 0.0
        var n = 0
        for (c in text) {
            if (c.code < 0x80) continue
            n++
            sum += ln(if (script(c)) 0.02 else 0.00001)
        }
        return if (n == 0) 0.0 else sum / n
    }

    /**
     * The character set of [bytes] as libvlc names it, or null for UTF-8 (libvlc's own default).
     * [hints] are language codes or names, the first the strongest.
     */
    fun detect(bytes: ByteArray, hints: List<String>): String? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return null
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
        // UTF-16 without a byte order mark: a zero in every other byte of Latin text
        val probe = minOf(bytes.size, 4096) and 1.inv()
        if (probe >= 16) {
            var even = 0
            var odd = 0
            for (i in 0 until probe step 2) { if (bytes[i].toInt() == 0) even++; if (bytes[i + 1].toInt() == 0) odd++ }
            val half = probe / 2
            if (odd > half * 0.3 && even < half * 0.05) return "UTF-16LE"
            if (even > half * 0.3 && odd < half * 0.05) return "UTF-16BE"
        }
        if (isUtf8(bytes)) return null
        val hinted = hints.map { it.lowercase(Locale.ROOT).replace('-', '_') }
        val nonAscii = bytes.count { it < 0 }
        var best: Candidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in CANDIDATES) {
            val text = decode(bytes, c.java, strict = c.java in MULTIBYTE) ?: continue
            // the model's mean per character, made a mean per byte: a two-byte character is one choice, not two
            val chars = text.count { it.code >= 0x80 }
            var score = c.score(text, bytes) * chars / nonAscii.coerceAtLeast(1)
            // a language hint settles a close call, it never outweighs a clearly better model
            val rank = hinted.indexOfFirst { h -> h in c.langs || h.substringBefore('_') in c.langs && h.substringBefore('_') != "zh" }
            if (rank >= 0) score += 0.5 - 0.05 * rank.coerceAtMost(5)
            if (score > bestScore) { bestScore = score; best = c }
        }
        return (best ?: CANDIDATES.last { it.java == "windows-1252" }).vlc
    }

    private val MULTIBYTE = setOf("Shift_JIS", "EUC-KR", "GBK", "Big5")

    private fun isUtf8(bytes: ByteArray): Boolean {
        // a file cut in the middle of a character at MAX_READ still counts: ignore an incomplete end
        var end = bytes.size
        var back = 0
        while (back < 3 && end - back - 1 >= 0 && (bytes[end - back - 1].toInt() and 0xC0) == 0x80) back++
        if (end - back - 1 >= 0 && (bytes[end - back - 1].toInt() and 0xC0) == 0xC0) end = end - back - 1
        return decode(bytes.copyOf(end), "UTF-8", strict = true) != null
    }

    private fun decode(bytes: ByteArray, charset: String, strict: Boolean): String? = try {
        val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
        Charset.forName(charset).newDecoder().onMalformedInput(action).onUnmappableCharacter(action).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        null
    }

    /** The language codes and names in a subtitle file name ("film.he.srt", "film_heb.srt", "film [Arabic].srt"). */
    fun fileNameHints(name: String): List<String> = name.substringBeforeLast('.').split('.', '_', '-', ' ', '[', ']', '(', ')')
            .map { it.lowercase(Locale.ROOT) }.filter { it.length in 2..10 }.reversed()

    /** The hints of the app: the preferred subtitle language, the app's language, the system's languages. */
    fun appHints(preferred: String?, appLocale: String?): List<String> {
        val out = ArrayList<String>()
        preferred?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        appLocale?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        val locales = android.content.res.Resources.getSystem().configuration.locales
        for (i in 0 until locales.size()) locales[i]?.let { out.add(it.language); if (it.country.isNotEmpty()) out.add(it.language + "_" + it.country) }
        return out
    }

    fun read(file: File): ByteArray? = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(minOf(file.length(), MAX_READ.toLong()).toInt())
            var off = 0
            while (off < buffer.size) { val r = input.read(buffer, off, buffer.size - off); if (r < 0) break; off += r }
            buffer.copyOf(off)
        }
    } catch (e: Exception) {
        null
    }

    fun read(context: Context, uri: Uri): ByteArray? = try {
        if (uri.scheme == "file" || uri.scheme == null) uri.path?.let { read(File(it)) }
        else context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (out.size() < MAX_READ * 8) { val r = input.read(buf); if (r < 0) break; out.write(buf, 0, r) }
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    /** The subtitle files libvlc would load beside a local video ("film.mkv": "film.srt", "film.he.srt"...). */
    fun siblings(video: File): List<File> {
        val dir = video.parentFile ?: return emptyList()
        val base = video.nameWithoutExtension.lowercase(Locale.ROOT)
        val dirs = listOf(dir) + (dir.listFiles { f -> f.isDirectory && f.name.lowercase(Locale.ROOT) in setOf("subs", "sub", "subtitles", "subtitle") }?.toList() ?: emptyList())
        return dirs.flatMap { d ->
            d.listFiles { f -> f.isFile && f.extension.lowercase(Locale.ROOT) in SUBTITLE_EXTENSIONS && f.extension.lowercase(Locale.ROOT) != "idx" &&
                    f.name.lowercase(Locale.ROOT).startsWith(base) }?.toList() ?: emptyList()
        }.take(32)
    }

    /**
     * A UTF-8 copy of a subtitle file that is not Unicode, kept under the app's files (a hand-added subtitle is
     * remembered with the media and must survive a cache clear), named as the original so that its language tag
     * stays; null when the file is UTF-8 already or cannot be read.
     */
    fun utf8Copy(context: Context, uri: Uri, displayName: String, hints: List<String>): Uri? {
        val bytes = read(context, uri) ?: return null
        val charset = detect(bytes, fileNameHints(displayName) + hints) ?: return null
        val java = CANDIDATES.firstOrNull { it.vlc == charset }?.java ?: charset
        val text = decode(bytes, java, strict = false) ?: return null
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val dir = File(context.filesDir, "subtitles-utf8/$hash").apply { mkdirs() }
        val out = File(dir, displayName.ifEmpty { "subtitle.srt" })
        if (!out.exists()) out.writeText(text.removePrefix("﻿"), Charsets.UTF_8)
        Log.i(TAG, "$displayName: $charset, read as UTF-8 from ${out.path}")
        return Uri.fromFile(out)
    }

    /**
     * The character set to give libvlc for the subtitle files beside [video] (the file of the preferred language
     * first), or null when they are all UTF-8. Several files in different character sets: the ones that differ
     * from the chosen one are returned in [others] (to add as UTF-8 copies).
     */
    fun forVideo(video: File, hints: List<String>, others: MutableList<Pair<File, String>>? = null): String? {
        var chosen: String? = null
        val found = siblings(video).mapNotNull { f -> read(f)?.let { f to detect(it, fileNameHints(f.name) + hints) } }
        val nonUtf8 = found.filter { it.second != null }
        if (nonUtf8.isEmpty()) return null
        // the file that matches the strongest hint decides
        val pick = nonUtf8.minByOrNull { (f, _) ->
            val names = fileNameHints(f.name)
            hints.indexOfFirst { h -> names.any { n -> n == h.lowercase(Locale.ROOT) || n.startsWith(h.lowercase(Locale.ROOT).substringBefore('_')) } }.let { if (it < 0) Int.MAX_VALUE else it }
        } ?: nonUtf8.first()
        chosen = pick.second
        others?.addAll(nonUtf8.filter { it.second != chosen }.map { it.first to it.second!! })
        return chosen
    }
}
