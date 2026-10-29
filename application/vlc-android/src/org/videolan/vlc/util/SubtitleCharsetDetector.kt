/*
 * VLC Redesign: the character encoding of a text subtitle file.
 *
 * A Kotlin port of the desktop's detector (modules/codec/subs_charset.c of the VLC Redesign), so that the phone
 * and the desktop name the same encoding for the same file: a byte order mark; UTF-16 without one; valid UTF-8;
 * else the legacy encoding whose decoding reads most like real text (the letters of the script of the encoding,
 * words of a single script, the case of the letters, marks after letters, the frequent rows of the CJK encodings,
 * no control characters, no undefined bytes); else the code page of the first language hint, else the fallback.
 * The scores are the desktop's, point for point; only the conversion is Java's instead of iconv.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

object SubtitleCharsetDetector {
    /** Enough text to tell the encodings apart, quickly */
    private const val ANALYSED_MAX = 128 * 1024

    /** The result: an iconv name (as libvlc knows it), and the size of the byte order mark */
    class Result(val charset: String, val bomSize: Int)

    // ---------------------------------------------------------------- languages

    private val LANG_CHARSETS = listOf(
            "CP1255" to "he iw heb hebrew yi yid yiddish",
            "CP1256" to "ar ara arabic fa per fas persian farsi ur urd urdu ps pus pashto ku kur kurdish ckb sd snd sindhi ug uig uyghur",
            "CP1251" to "ru rus russian uk ukr ukrainian be bel belarusian bg bul bulgarian mk mac mkd macedonian sr srp serbian kk kaz kazakh ky kir kyrgyz tg tgk tajik mn mon mongolian tt tat tatar ba bak bashkir",
            "CP1250" to "pl pol polish cs cze ces czech sk slo slk slovak hu hun hungarian ro rum ron romanian hr hrv croatian sl slv slovenian bs bos bosnian sq alb sqi albanian",
            "CP1253" to "el gre ell greek",
            "CP1254" to "tr tur turkish az aze azerbaijani",
            "CP1257" to "lt lit lithuanian lv lav latvian et est estonian",
            "CP1258" to "vi vie vietnamese",
            "CP874" to "th tha thai",
            "CP932" to "ja jpn japanese",
            "CP950" to "zh-tw zh-hk zh-mo zh-hant cht tc traditional",
            "GB18030" to "zh chi zho chinese zh-cn zh-sg zh-hans chs sc simplified",
            "CP949" to "ko kor korean",
            "CP1252" to "en eng english fr fre fra french de ger deu german es spa spanish it ita italian pt por portuguese nl dut nld dutch sv swe swedish da dan danish no nor norwegian nb nob nn nno fi fin finnish is ice isl icelandic ca cat catalan eu baq eus basque gl glg galician ga gle irish af afr afrikaans id ind indonesian ms may msa malay tl tgl tagalog fil sw swa swahili",
    ).map { it.first to it.second.split(' ').toSet() }

    /** "pt_BR", "zh-TW.srt": lower case, '-' for '_', up to the first other character */
    private fun langNormalize(lang: String): String {
        val sb = StringBuilder()
        for (ch in lang) {
            if (sb.length >= 31) break
            val c = when (ch) {
                in 'A'..'Z' -> ch + ('a' - 'A')
                '_' -> '-'
                else -> ch
            }
            if (!(c in 'a'..'z' || c == '-')) break
            sb.append(c)
        }
        return sb.toString()
    }

    /** The legacy code page of a language ("he", "heb", "Hebrew": "CP1255"), null if not known */
    fun forLanguage(lang: String?): String? {
        if (lang == null) return null
        var norm = langNormalize(lang)
        for (pass in 0 until 2) {
            if (norm.isNotEmpty()) LANG_CHARSETS.firstOrNull { norm in it.second }?.let { return it.first }
            val dash = norm.indexOf('-')
            if (dash < 0) break
            norm = norm.substring(0, dash)
        }
        return null
    }

    // ---------------------------------------------------------------- conversion

    /** The Java names of an iconv name, the first one the runtime has wins */
    private fun javaNames(iconv: String): List<String> = when (iconv.uppercase(Locale.ROOT)) {
        "CP874" -> listOf("x-windows-874", "windows-874", "TIS-620")
        "CP932" -> listOf("windows-31j", "windows-932", "Shift_JIS")
        "SHIFT_JIS" -> listOf("Shift_JIS")
        "CP950" -> listOf("x-windows-950", "windows-950", "Big5")
        "BIG5" -> listOf("Big5")
        "CP949" -> listOf("x-windows-949", "windows-949", "EUC-KR")
        "GB18030" -> listOf("GB18030", "GBK")
        else -> if (iconv.uppercase(Locale.ROOT).startsWith("CP125")) listOf("windows-" + iconv.substring(2)) else listOf(iconv)
    }

    fun javaCharset(iconv: String): Charset? {
        for (n in javaNames(iconv)) try {
            if (Charset.isSupported(n)) return Charset.forName(n)
        } catch (_: Exception) {
        }
        return null
    }

    /** Decodes, U+FFFD for each byte that cannot be converted (one byte lost, as iconv); the count of them */
    private fun convert(bytes: ByteArray, size: Int, cs: Charset): Pair<String, Int> {
        val dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, 0, size)
        val out = CharBuffer.allocate(size * 2 + 16)
        var errors = 0
        while (true) {
            val r = dec.decode(input, out, true)
            if (r.isError) {
                input.position(input.position() + 1)
                out.put('�')
                errors++
                dec.reset()
                continue
            }
            if (r.isUnderflow) break
            if (r.isOverflow) break
        }
        dec.flush(out)
        out.flip()
        return out.toString() to errors
    }

    // ---------------------------------------------------------------- UTF-8

    private fun isUtf8Valid(p: ByteArray, size: Int): Boolean {
        var i = 0
        while (i < size) {
            val c = p[i++].toInt() and 0xFF
            if (c < 0x80) continue
            val n: Int
            val min: Int
            when (c) {
                in 0xC2..0xDF -> { n = 1; min = 0x80 }
                in 0xE0..0xEF -> { n = 2; min = 0x800 }
                in 0xF0..0xF4 -> { n = 3; min = 0x10000 }
                else -> return false
            }
            var cp = c and (0x3F shr n)
            for (k in 0 until n) {
                if (i >= size) return true // cut by the analysed size
                val b = p[i].toInt() and 0xFF
                if (b and 0xC0 != 0x80) return false
                cp = (cp shl 6) or (b and 0x3F)
                i++
            }
            if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return false
        }
        return true
    }

    // ---------------------------------------------------------------- scoring

    private const val SC_NONE = 0
    private const val SC_LATIN = 1
    private const val SC_GREEK = 2
    private const val SC_CYRILLIC = 3
    private const val SC_HEBREW = 4
    private const val SC_ARABIC = 5
    private const val SC_THAI = 6
    private const val SC_KANA = 7
    private const val SC_HAN = 8
    private const val SC_HANGUL = 9

    private const val F_LETTER = 1
    private const val F_MARK = 2
    private const val F_UPPER = 4
    private const val F_LOWER = 8
    private const val F_SYMBOL = 16
    private const val F_CONTROL = 32
    private const val F_SPACE = 64
    private const val F_CJKPUNCT = 128

    /** script shl 8 or flags */
    private fun classify(c: Int): Int {
        fun r(script: Int, flags: Int) = (script shl 8) or flags
        if (c < 0x80) {
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code) return r(SC_LATIN, F_LETTER or if (c >= 'a'.code) F_LOWER else F_UPPER)
            if (c == ' '.code || c == '\t'.code || c == '\r'.code || c == '\n'.code) return r(SC_NONE, F_SPACE)
            if (c < 0x20 || c == 0x7F) return r(SC_NONE, F_CONTROL)
            return r(SC_NONE, 0)
        }
        if (c < 0xA0) return r(SC_NONE, F_CONTROL)
        if (c == 0xFFFD) return r(SC_NONE, F_CONTROL)
        if (c in 0xC0..0x24F && c != 0xD7 && c != 0xF7) {
            val upper = when {
                c < 0x100 -> c < 0xDF
                c == 0x131 || c == 0x138 || c == 0x149 || c == 0x17F -> false
                c in 0x139..0x148 || c in 0x179..0x17E -> c and 1 != 0
                else -> c and 1 == 0
            }
            return r(SC_LATIN, F_LETTER or if (upper) F_UPPER else F_LOWER)
        }
        if (c in 0x1E00..0x1EFF) return r(SC_LATIN, F_LETTER or if (c and 1 != 0) F_LOWER else F_UPPER)
        if (c in 0x300..0x36F) return r(SC_LATIN, F_MARK)
        if (c in 0x386..0x3CE && c != 0x387) return r(SC_GREEK, F_LETTER or if (c >= 0x3AC || c == 0x390) F_LOWER else F_UPPER)
        if (c in 0x400..0x4FF) {
            var upper = c < 0x430 || (c >= 0x460 && c and 1 == 0)
            if (c in 0x450 until 0x460) upper = false
            return r(SC_CYRILLIC, F_LETTER or if (upper) F_UPPER else F_LOWER)
        }
        if (c in 0x591..0x5C7 && c != 0x5BE && c != 0x5C0 && c != 0x5C3 && c != 0x5C6) return r(SC_HEBREW, F_MARK)
        if (c in 0x5D0..0x5F2 || c in 0xFB1D..0xFB4F) return r(SC_HEBREW, F_LETTER)
        if (c in 0x64B..0x65F || c == 0x670) return r(SC_ARABIC, F_MARK)
        if (c in 0x620..0x64A || c in 0x660..0x669 || c in 0x66E..0x6D3 || c in 0x6FA..0x6FF || c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFC)
            return r(SC_ARABIC, F_LETTER)
        if (c == 0x60C || c == 0x61B || c == 0x61F || c == 0x640) return r(SC_ARABIC, F_CJKPUNCT)
        if (c in 0xE01..0xE5B) {
            return if (c == 0xE31 || c in 0xE34..0xE3A || c in 0xE47..0xE4E) r(SC_THAI, F_MARK) else r(SC_THAI, F_LETTER)
        }
        if (c in 0x3041..0x30FF || c in 0x31F0..0x31FF) return r(SC_KANA, F_LETTER)
        if (c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF) return r(SC_HAN, F_LETTER)
        if (c in 0xAC00..0xD7A3 || c in 0x3131..0x318E) return r(SC_HANGUL, F_LETTER)
        if (c in 0x3000..0x303F || c in 0xFF01..0xFF60 || c in 0xFFE0..0xFFE6) return r(SC_NONE, F_CJKPUNCT)
        if (c in 0xE000..0xF8FF) return r(SC_NONE, F_CONTROL) // private use: nothing real
        return if (c == 0xA0 || c == 0x3000) r(SC_NONE, F_SPACE) else r(SC_NONE, F_SYMBOL)
    }

    private enum class Kind { SBCS, SJIS, EUCJP, GB, BIG5, UHC }

    /**
     * [charsets]: iconv names, alternatives; [langs]: the code page of these languages; [letters]: Latin, the
     * letters with diacritics of each language of the code page ('|' between languages); [neutral]: Latin
     * letters it also holds
     */
    private class Candidate(val charsets: List<String>, val script: Int, val kind: Kind, val langs: String, val letters: String?, val neutral: String?)

    private const val WESTERN = "àâæçèéêëîïôœùûüÿ|äöüßéëèïóá|áéíóúñüàèçòï|áâãàçéêíóôõú|àèéìíîòóùú|åäöæøéüáóðþý"
    private const val CENTRAL = "ąćęłńóśźż|áčďéěíňóřšťúůýžäĺľôŕ|áéíóöőúüű|ăâîșțşţ|čćđšž|ëç"
    private const val BALTIC = "ąčęėįšųūž|āčēģīķļņšūž|äöõüšž"

    /** In the order that wins a tie */
    private val CANDIDATES = listOf(
            Candidate(listOf("CP1252"), SC_LATIN, Kind.SBCS, "CP1252", WESTERN, null),
            Candidate(listOf("CP1250"), SC_LATIN, Kind.SBCS, "CP1250", CENTRAL, null),
            Candidate(listOf("CP1251"), SC_CYRILLIC, Kind.SBCS, "CP1251", null, null),
            Candidate(listOf("CP1253"), SC_GREEK, Kind.SBCS, "CP1253", null, null),
            Candidate(listOf("CP1254"), SC_LATIN, Kind.SBCS, "CP1254", "$WESTERN|çğıöşüâîû", null),
            Candidate(listOf("CP1255"), SC_HEBREW, Kind.SBCS, "CP1255", null, null),
            Candidate(listOf("CP1256"), SC_ARABIC, Kind.SBCS, "CP1256", null, "àâçèéêëîïôùûüœ"),
            Candidate(listOf("CP1257"), SC_LATIN, Kind.SBCS, "CP1257", BALTIC, null),
            Candidate(listOf("CP1258"), SC_LATIN, Kind.SBCS, "CP1258", "vi", null),
            Candidate(listOf("CP874"), SC_THAI, Kind.SBCS, "CP874", null, null),
            Candidate(listOf("KOI8-R"), SC_CYRILLIC, Kind.SBCS, "CP1251", null, null),
            Candidate(listOf("KOI8-U"), SC_CYRILLIC, Kind.SBCS, "CP1251", null, null),
            Candidate(listOf("ISO-8859-2"), SC_LATIN, Kind.SBCS, "CP1250", CENTRAL, null),
            Candidate(listOf("ISO-8859-15"), SC_LATIN, Kind.SBCS, "CP1252", "$WESTERN|äöõüšž", null),
            Candidate(listOf("ISO-8859-6"), SC_ARABIC, Kind.SBCS, "CP1256", null, null),
            Candidate(listOf("ISO-8859-7"), SC_GREEK, Kind.SBCS, "CP1253", null, null),
            Candidate(listOf("ISO-8859-8"), SC_HEBREW, Kind.SBCS, "CP1255", null, null),
            Candidate(listOf("CP932", "SHIFT_JIS"), SC_KANA, Kind.SJIS, "CP932", null, null),
            Candidate(listOf("EUC-JP"), SC_KANA, Kind.EUCJP, "CP932", null, null),
            Candidate(listOf("GB18030", "GBK"), SC_HAN, Kind.GB, "GB18030", null, null),
            Candidate(listOf("CP950", "BIG5"), SC_HAN, Kind.BIG5, "CP950", null, null),
            Candidate(listOf("CP949", "EUC-KR"), SC_HANGUL, Kind.UHC, "CP949", null, null),
    )

    /** The lower case of a Latin letter with a diacritic */
    private fun latinLower(c: Int): Int = when {
        c in 0xC0..0xDE && c != 0xD7 -> c + 0x20
        c == 0x130 -> 'i'.code
        c == 0x178 -> 0xFF
        c in 0x139..0x148 || c in 0x179..0x17E -> if (c and 1 != 0) c + 1 else c
        c in 0x100..0x17F || c in 0x1E00..0x1EFF -> if (c and 1 != 0) c else c + 1
        c == 0x218 || c == 0x21A -> c + 1
        else -> c
    }

    /** Whether a code point is one of the letters of a list (up to its '|') */
    private fun inLetters(list: String?, c: Int): Boolean {
        if (list == null) return false
        var i = 0
        while (i < list.length && list[i] != '|') {
            val cp = list.codePointAt(i)
            if (cp == c) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private val CYRILLIC_W = intArrayOf(2, 0, 1, 0, 1, 2, 0, 0, 2, 0, 1, 1, 1, 2, 2, 1, 1, 2, 2, 1, -2, 0, -1, 0, -1, -2, -4, 0, 0, -2, -1, 0)

    /** Russian and Ukrainian letter frequencies: о е а и н т с (+2) ... ъ (-4) */
    private fun cyrillicWeight(c0: Int): Int {
        var c = c0
        if (c in 0x410..0x42F) c += 0x20 else if (c in 0x400..0x40F) c += 0x50
        if (c in 0x430..0x44F) return CYRILLIC_W[c - 0x430]
        if (c == 0x456 || c == 0x457 || c == 0x454 || c == 0x491 || c == 0x451) return 0
        return -2
    }

    /** Greek: α ο ε ι τ ν (+2) ... ψ ξ ζ (-1) */
    private fun greekWeight(c0: Int): Int {
        val c = if (c0 in 0x391..0x3A9) c0 + 0x20 else c0
        return when (c) {
            0x3B1, 0x3BF, 0x3B5, 0x3B9, 0x3C4, 0x3BD -> 2
            0x3C3, 0x3C1, 0x3B7, 0x3C0, 0x3BA, 0x3C5, 0x3BC, 0x3BB, 0x3AC, 0x3AD, 0x3AF, 0x3CC, 0x3CD, 0x3AE, 0x3CE, 0x3C2 -> 1
            0x3C8, 0x3BE, 0x3B6, 0x3CA, 0x3CB, 0x390, 0x3B0 -> -1
            else -> 0
        }
    }

    private fun thaiConsonant(c: Int) = c in 0xE01..0xE2E

    /** Rows of the double-byte encodings: the frequent characters are in a few rows */
    private fun rowsScore(cand: Candidate, p: ByteArray, size: Int): Int {
        var score = 0
        var i = 0
        while (i < size) {
            val b = p[i].toInt() and 0xFF
            if (b < 0x80) { i++; continue }
            if (cand.kind == Kind.SJIS && b in 0xA1..0xDF) { score -= 2; i++; continue } // half width katakana: rare
            if (cand.kind == Kind.EUCJP && b == 0x8E) { i += 2; score -= 2; continue }
            if (i + 1 >= size) break
            val t = p[++i].toInt() and 0xFF
            when (cand.kind) {
                Kind.SJIS -> if (b == 0x82 || b == 0x83 || b == 0x81) score += 2 else if (b in 0x88..0x9F) score += 2 else if (b in 0xE0..0xEA) score += 1
                Kind.EUCJP -> if (b == 0xA4 || b == 0xA5 || b == 0xA1) score += 2 else if (b in 0xB0..0xCF) score += 2 else if (b in 0xD0..0xF4) score += 1
                Kind.GB -> if (b in 0xB0..0xD7 && t >= 0xA1) score += 2 else if ((b == 0xA1 || b == 0xA3) && t >= 0xA1) score += 1 else if (b in 0xD8..0xF7 && t >= 0xA1) score += 1
                Kind.BIG5 -> if (b in 0xA4..0xC6) score += 2 else if (b in 0xA1..0xA3) score += 1 else if (b in 0xC9..0xF9) score += 1
                Kind.UHC -> if (b in 0xB0..0xC8 && t >= 0xA1) score += 2 else if (b in 0xA1..0xAC && t >= 0xA1) score += 1
                else -> {}
            }
            i++
        }
        return score
    }

    /** Symbols that real text keeps away from letters (¤ ¦ ¨ ´ ¸ ¹ ½...) */
    private fun oddSymbol(c: Int) = (c in 0xA2..0xBE && c != 0xA3 && c != 0xA5 && c != 0xA7 && c != 0xA9 && c != 0xAB && c != 0xAE &&
            c != 0xB0 && c != 0xB7 && c != 0xBB) || c in 0x2500..0x25FF || c == 0x2219 || c == 0x221A || c == 0x2248 ||
            c == 0x2264 || c == 0x2265 || c == 0x2320 || c == 0x2321 || c == 0x2552

    private fun isHebrewFinal(c: Int) = c == 0x5DA || c == 0x5DD || c == 0x5DF || c == 0x5E3 || c == 0x5E5

    /** The score of a text as the text of a language of a candidate: [letters] = one language of its list (Latin) */
    private fun scoreText(cand: Candidate, letters: String?, text: String, errors: Int): Long {
        var score = -25L * errors
        var wordScripts = 0
        var wordLetters = 0
        var wordAscii = 0
        var prev = ' '.code
        var prevScript = SC_NONE
        var prevFlags = F_SPACE
        var lastScript = SC_NONE
        var spaces = false
        var symbolInWord = false
        var thaiConsonants = 0
        val cjk = cand.kind != Kind.SBCS
        val latin = cand.script == SC_LATIN
        val vietnamese = latin && letters == "vi"
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            val k = classify(c)
            val s = k shr 8
            val flags = k and 0xFF
            if (flags and F_LETTER == 0) thaiConsonants = 0

            // Hebrew final letters (ך ם ן ף ץ) end words
            if (prevScript == SC_HEBREW && isHebrewFinal(prev) && flags and (F_LETTER or F_MARK) != 0 && s == SC_HEBREW) score -= 4
            else if (prevScript == SC_HEBREW && cand.script == SC_HEBREW && isHebrewFinal(prev) && flags and (F_LETTER or F_MARK) == 0) score += 3
            // Greek: ς at the end of words only
            if (prev == 0x3C2 && flags and F_LETTER != 0 && s == SC_GREEK) score -= 6

            if (flags and F_LETTER != 0) {
                if (symbolInWord) score -= 6
                symbolInWord = false
                if (oddSymbol(prev)) score -= 4
                if (c < 0x80) wordAscii++
                else if (cjk && (s == SC_HAN || s == SC_KANA || s == SC_HANGUL)) {
                    score += when (s) {
                        SC_HANGUL -> if (cand.script == SC_HANGUL) 5 else -3
                        SC_KANA -> if (cand.script == SC_KANA) 5 else -2
                        else -> if (cand.script == SC_HANGUL) -3 else 2
                    }
                    if (spaces && s != SC_HANGUL && cand.script != SC_HANGUL && (lastScript == SC_HAN || lastScript == SC_KANA)) score -= 3
                } else if (s == cand.script) {
                    when (s) {
                        SC_LATIN -> score += if (vietnamese) {
                            if (c in 0x1EA0..0x1EF9 || inLetters("ăâđêôơưàáãèéìíòóõùúý", latinLower(c))) 4 else 1
                        } else if (inLetters(letters, latinLower(c))) 4 else 1
                        SC_CYRILLIC -> score += (if (flags and F_LOWER != 0) 2 else 1) + cyrillicWeight(c)
                        SC_GREEK -> score += (if (flags and F_LOWER != 0) 2 else 1) + greekWeight(c)
                        SC_THAI -> {
                            score += 3
                            if (thaiConsonant(c) && ++thaiConsonants > 3) score -= 3
                            else if (!thaiConsonant(c)) thaiConsonants = 0
                            if (c == 0xE03 || c == 0xE05 || c == 0xE26 || c == 0xE4F || c == 0xE5A || c == 0xE5B) score -= 8
                            else if (c in 0xE50..0xE59 && (prevScript == SC_THAI || i < n)) score -= 2
                            else if ((c == 0xE30 || c == 0xE32 || c == 0xE33 || c == 0xE45) && !(thaiConsonant(prev) || prevFlags and F_MARK != 0)) score -= 5
                            if (prev in 0xE40..0xE44 && !thaiConsonant(c)) score -= 6
                        }
                        else -> score += 3
                    }
                } else if (s == SC_LATIN && inLetters(cand.neutral, latinLower(c))) {
                    // a Latin letter the code page also holds
                } else score -= 3 // a letter of another script

                if (flags and F_UPPER != 0 && prevFlags and F_LOWER != 0 && prevScript == s && (c >= 0x80 || prev >= 0x80)) score -= 8
                if (s != SC_HAN && s != SC_KANA && s != SC_HANGUL) {
                    wordScripts = wordScripts or (1 shl s)
                    wordLetters++
                }
            } else if (flags and F_MARK != 0) {
                if (s == SC_THAI) {
                    score += if (thaiConsonant(prev) || (prevFlags and F_MARK != 0 && prevScript == SC_THAI && c >= 0xE47)) {
                        if (cand.script == SC_THAI) 1 else -3
                    } else -6
                } else if (prevFlags and (F_LETTER or F_MARK) == 0 || (prevScript != s && s != SC_LATIN)) score -= 5
                else if (s == cand.script) score += 1
                else if (s != SC_LATIN) score -= 3
            } else {
                // end of a word
                if (wordLetters > 0) {
                    if (wordScripts and (wordScripts - 1) != 0) score -= 6
                    else if (latin && wordAscii == 0 && wordLetters >= 3) score -= 4
                }
                wordScripts = 0; wordLetters = 0; wordAscii = 0
                if (flags and F_CONTROL != 0) score -= 25
                else if (flags and F_SYMBOL != 0) {
                    if (prevFlags and F_LETTER != 0 && c != 0x2019 && c != 0xB7 && c != 0x2010 && c != 0x2011 && c != 0xAD) symbolInWord = true
                    if (c == 0xA4 || c == 0xA6 || c == 0xA8 || c == 0xB4 || c == 0xB8) score -= 2
                } else if (flags and F_CJKPUNCT != 0) {
                    score += if (cjk || s == cand.script) 1 else -3
                }
                if (flags and F_SYMBOL == 0) symbolInWord = false
            }
            if (flags and F_SPACE != 0) spaces = true
            else {
                spaces = false
                lastScript = s
            }
            prev = c
            prevFlags = flags
            prevScript = s
        }
        return score
    }

    /** The score of a text for a candidate: as the language that fits best */
    private fun scoreCandidate(cand: Candidate, text: String, errors: Int): Long {
        val letters = cand.letters ?: return scoreText(cand, null, text, errors)
        return letters.split('|').maxOf { scoreText(cand, it, text, errors) }
    }

    /**
     * The encoding of [buf]: [langs] are language codes or names (the language in the file name, the track's, the
     * preferred subtitle language, the interface's), the first the strongest; [fallback] the code page of the
     * interface language.
     */
    fun detect(buf: ByteArray, langs: List<String>, fallback: String?): Result {
        val total = buf.size
        fun starts(vararg b: Int) = total >= b.size && b.indices.all { buf[it].toInt() and 0xFF == b[it] }
        // 1. byte order marks (UTF-32 first: FF FE 00 00 starts as UTF-16LE)
        if (starts(0xFF, 0xFE, 0, 0)) return Result("UTF-32LE", 4)
        if (starts(0, 0, 0xFE, 0xFF)) return Result("UTF-32BE", 4)
        if (starts(0xEF, 0xBB, 0xBF)) return Result("UTF-8", 3)
        if (starts(0xFF, 0xFE)) return Result("UTF-16LE", 2)
        if (starts(0xFE, 0xFF)) return Result("UTF-16BE", 2)
        val size = minOf(total, ANALYSED_MAX)

        // 2. UTF-16 without a mark: the zero high bytes of ASCII
        if (size >= 16) {
            var zeroEven = 0
            var zeroOdd = 0
            var i = 0
            while (i + 1 < size) {
                if (buf[i].toInt() == 0) zeroEven++
                if (buf[i + 1].toInt() == 0) zeroOdd++
                i += 2
            }
            val units = size / 2
            if (zeroOdd > units * 3 / 10 && zeroEven < units / 20) return Result("UTF-16LE", 0)
            if (zeroEven > units * 3 / 10 && zeroOdd < units / 20) return Result("UTF-16BE", 0)
        }

        // 3. UTF-8
        if (isUtf8Valid(buf, size)) return Result("UTF-8", 0)

        // 4. the legacy encoding whose text reads best
        val hinted = langs.asSequence().mapNotNull { forLanguage(it) }.take(8).toList()
        var best = 0L
        var bestName: String? = null
        for (cand in CANDIDATES) {
            var chosen: String? = null
            var cs: Charset? = null
            for (name in cand.charsets) { cs = javaCharset(name); if (cs != null) { chosen = name; break } }
            if (cs == null || chosen == null) continue
            val (text, errors) = convert(buf, size, cs)
            var score = scoreCandidate(cand, text, errors)
            if (cand.kind != Kind.SBCS) score += rowsScore(cand, buf, size)
            // the hints: the encoding of the languages of the text, of the user, of the interface
            for (h in hinted.indices) if (hinted[h] == cand.langs) {
                val weight = (12L - 2 * h).coerceAtLeast(4)
                score += (if (score > 0) score * weight / 100 else 0) + 2
                break
            }
            if (fallback != null && fallback == cand.langs) score += 1
            if (bestName == null || score > best) {
                best = score
                bestName = chosen
            }
        }
        // 5. nothing reads like text: the encoding of the language
        if (bestName == null || best <= 0) bestName = hinted.firstOrNull() ?: fallback?.takeIf { it.isNotEmpty() } ?: "CP1252"
        return Result(bestName, 0)
    }
}
