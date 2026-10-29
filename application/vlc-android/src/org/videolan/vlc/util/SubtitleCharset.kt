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
import java.util.Locale

object SubtitleCharset {
    private const val TAG = "VLC/SubtitleCharset"
    private const val MAX_READ = 512 * 1024
    val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "sub", "txt", "vtt", "smi", "sami", "mpl", "jss", "psb", "rt", "pjs", "aqt", "usf", "idx")

    /**
     * The character set of [bytes] as libvlc names it, or null for UTF-8 (libvlc's own default). [hints] are
     * language codes or names, the first the strongest. The desktop's detector (SubtitleCharsetDetector).
     */
    fun detect(bytes: ByteArray, hints: List<String>, fallback: String? = null): String? {
        val r = SubtitleCharsetDetector.detect(bytes, hints, fallback)
        return if (r.charset == "UTF-8") null else r.charset
    }

    private fun decode(bytes: ByteArray, charset: String): String? = try {
        val cs = SubtitleCharsetDetector.javaCharset(charset) ?: Charset.forName(charset)
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(bytes)).toString()
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

    private fun isAss(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT).let { it == "ass" || it == "ssa" }

    /**
     * ASS/SSA: every style's Encoding field set to -1, as the desktop's libass module does (with the field of most
     * files, 0 or 1, libass lays every line out left to right, so the punctuation of a Hebrew or Arabic line ends
     * on the wrong side; -1 asks it to find each line's direction). libvlc 3 does not ask libass for the whole
     * event layout of the desktop and libass still lays the lines of its events out left to right, so each line of
     * a dialogue that starts in a right-to-left script is also put in a right-to-left embedding (U+202B ... U+202C).
     * Null when there is nothing to change.
     */
    fun assAutoDirection(text: String): String? {
        var changed = false
        var encodingIndex = -1
        var textIndex = -1
        var section = ""
        val lines = text.split('\n').toMutableList()
        for (i in lines.indices) {
            val line = lines[i]
            val cr = line.endsWith("\r")
            val t = line.trimEnd('\r')
            val trimmed = t.trim()
            if (trimmed.startsWith("[")) { section = trimmed.lowercase(Locale.ROOT); encodingIndex = -1; textIndex = -1 }
            val head = t.substringBefore(':', "") + ":"
            val body = t.substringAfter(':', "")
            when {
                trimmed.startsWith("Format:", ignoreCase = true) && section.startsWith("[v4") ->
                    encodingIndex = body.split(',').map { it.trim().lowercase(Locale.ROOT) }.indexOf("encoding")
                trimmed.startsWith("Format:", ignoreCase = true) && section == "[events]" ->
                    textIndex = body.split(',').map { it.trim().lowercase(Locale.ROOT) }.indexOf("text")
                trimmed.startsWith("Style:", ignoreCase = true) && encodingIndex >= 0 -> {
                    val fields = body.split(',').toMutableList()
                    if (encodingIndex < fields.size && fields[encodingIndex].trim() != "-1") {
                        fields[encodingIndex] = "-1"
                        lines[i] = head + fields.joinToString(",") + if (cr) "\r" else ""
                        changed = true
                    }
                }
                trimmed.startsWith("Dialogue:", ignoreCase = true) && textIndex >= 0 -> {
                    // the text is the last field: it keeps its commas
                    val fields = body.split(',', limit = textIndex + 1)
                    if (fields.size == textIndex + 1) {
                        val parts = fields[textIndex].split("\\N")
                        val wrapped = parts.map { p -> markBrackets(p, startsRtl(p)).let { if (startsRtl(p)) "\u202B" + it + "\u202C" else it } }
                        if (wrapped != parts) {
                            lines[i] = head + fields.dropLast(1).joinToString(",") + "," + wrapped.joinToString("\\N") + if (cr) "\r" else ""
                            changed = true
                        }
                    }
                }
            }
        }
        return if (changed) lines.joinToString("\n") else null
    }

    /**
     * The desktop asks libass for ASS_FEATURE_BIDI_BRACKETS (the paired brackets rule N0 of the Unicode bidi
     * algorithm); libvlc 3's libass is not asked, so a bracket pair around words of both directions can face the
     * wrong way. Here the line is resolved with Android's own bidi (ICU, with N0) and each bracket of a line that
     * mixes directions gets a mark of its resolved direction on both sides (RLM or LRM, zero width): between two
     * marks of one direction a bracket resolves to it without N0 too. Override tags {...} are kept as they are.
     */
    internal fun markBrackets(line: String, rtl: Boolean): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return line
        if (line.none { it in "()[]{}<>" || android.icu.lang.UCharacter.getIntPropertyValue(it.code, android.icu.lang.UProperty.BIDI_PAIRED_BRACKET_TYPE) != 0 }) return line
        // the visible text and where each of its characters is in the line
        val visible = StringBuilder()
        val at = ArrayList<Int>()
        var tag = false
        line.forEachIndexed { i, c ->
            if (c == '{') tag = true
            else if (c == '}' && tag) tag = false
            else if (!tag) { visible.append(c); at.add(i) }
        }
        val text = visible.toString()
        var hasR = false
        var hasL = false
        for (c in text) when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> hasR = true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> hasL = true
        }
        if (!hasR || !hasL) return line
        val bidi = android.icu.text.Bidi(text, if (rtl) android.icu.text.Bidi.DIRECTION_RIGHT_TO_LEFT else android.icu.text.Bidi.DIRECTION_LEFT_TO_RIGHT)
        val marks = HashMap<Int, Char>()
        text.forEachIndexed { i, c ->
            if (android.icu.lang.UCharacter.getIntPropertyValue(c.code, android.icu.lang.UProperty.BIDI_PAIRED_BRACKET_TYPE) != 0)
                marks[at[i]] = if (bidi.getLevelAt(i) % 2 == 1) '‏' else '‎'
        }
        if (marks.isEmpty()) return line
        val out = StringBuilder(line.length + marks.size * 2)
        line.forEachIndexed { i, c ->
            val m = marks[i]
            if (m != null) out.append(m).append(c).append(m) else out.append(c)
        }
        return out.toString()
    }

    /** Whether the first letter of a line of ASS text (its override tags skipped) is of a right-to-left script */
    private fun startsRtl(s: String): Boolean {
        var tag = false
        for (c in s) {
            if (c == '{') tag = true else if (c == '}') tag = false
            else if (!tag && Character.isLetter(c)) {
                val d = Character.getDirectionality(c)
                return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            }
        }
        return false
    }

    /**
     * A UTF-8 copy of a subtitle file that libvlc 3 would not read right, kept under the app's files (a hand-added
     * subtitle is remembered with the media and must survive a cache clear), named as the original so that its
     * language tag stays: a file that is not UTF-8 (libvlc reads it as Windows-1252, and libass takes the bytes as
     * they are), an ASS/SSA file whose styles fix the text direction. Null when the file can be read as it is.
     */
    fun utf8Copy(context: Context, uri: Uri, displayName: String, hints: List<String>): Uri? {
        val bytes = read(context, uri) ?: return null
        val charset = detect(bytes, fileNameHints(displayName) + hints)
        var text = (if (charset != null) decode(bytes, charset) else String(bytes, Charsets.UTF_8))?.removePrefix("\uFEFF") ?: return null
        val ass = if (isAss(displayName)) assAutoDirection(text) else null
        if (charset == null && ass == null) return null
        if (ass != null) text = ass
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val dir = File(context.filesDir, "subtitles-utf8/v3-$hash").apply { mkdirs() }
        val out = File(dir, displayName.ifEmpty { "subtitle.srt" })
        if (!out.exists()) out.writeText(text, Charsets.UTF_8)
        Log.i(TAG, "$displayName: ${charset ?: "UTF-8"}${if (ass != null) ", ASS styles in auto direction" else ""}, read from ${out.path}")
        return Uri.fromFile(out)
    }

    /** Whether one of the subtitle files beside [video] has to be read from a copy (see [utf8Copy]) */
    fun needsCopies(video: File, hints: List<String>): Boolean = siblings(video).any { f ->
        val bytes = read(f) ?: return@any false
        val charset = detect(bytes, fileNameHints(f.name) + hints)
        charset != null || isAss(f.name) && assAutoDirection(String(bytes, Charsets.UTF_8)) != null
    }
}
