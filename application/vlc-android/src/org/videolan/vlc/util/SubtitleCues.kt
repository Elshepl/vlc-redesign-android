/*
 * VLC Redesign: the lines of a subtitle track and their times, for the subtitle sync timeline (the desktop's
 * player/subtitle_cues.cpp and subtitle_cues_parser).
 *
 * A text subtitle file (SRT, WebVTT, ASS / SSA) is read whole, in its own encoding (SubtitleCharsetDetector, the
 * language of its name first), on the caller's thread (never the main one); a track of the video itself is read
 * through the platform's extractor when it knows the track (Matroska / MP4 text), its lines without an end time
 * then last until the next one (at most 5 s). The lines read are kept for the next time (a few files).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

object SubtitleCues {
    private const val TAG = "VLC/SubtitleCues"
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val MAX_OPEN_LINE_MS = 5000L
    val EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    /** A line: from [start] to [end] (ms, the file's own times, before any delay) */
    class Cue(val start: Long, val end: Long, val text: String)

    private val cache = LruCache<String, List<Cue>>(8)

    /** The lines of a subtitle file, empty when it cannot be read; blocking */
    fun readFile(file: File): List<Cue> {
        if (!file.isFile || !file.canRead() || file.length() > MAX_BYTES) return emptyList()
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        cache.get(key)?.let { return it }
        val cues = try {
            val bytes = file.readBytes()
            // the language of the name ("movie.he.srt") is the strongest hint of the encoding
            val parts = file.name.split('.')
            val langs = if (parts.size > 2) listOf(parts[parts.size - 2]) else emptyList()
            val found = SubtitleCharsetDetector.detect(bytes, langs + Locale.getDefault().language, "CP1252")
            val charset = SubtitleCharsetDetector.javaCharset(found.charset) ?: Charsets.UTF_8
            parse(String(bytes, found.bomSize, bytes.size - found.bomSize, charset))
        } catch (e: Exception) {
            Log.w(TAG, "cannot read ${file.name}: $e")
            emptyList()
        }
        cache.put(key, cues)
        return cues
    }

    private val SRT_TIME = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val TAGS = Regex("""<[^>]*>|\{\\[^}]*\}""")

    /** The lines of a text subtitle file: SRT and WebVTT (a time line, then the text), ASS / SSA (Dialogue rows) */
    fun parse(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        if (text.contains("[Events]", ignoreCase = true) || text.contains("\nDialogue:")) {
            // ASS / SSA: "Dialogue: layer,start,end,style,name,ml,mr,mv,effect,text"; the text field may have commas
            var textField = 9
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("Format:", ignoreCase = true)) {
                    val fields = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                    fields.indexOf("text").takeIf { it >= 0 }?.let { textField = it }
                    continue
                }
                if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
                val fields = line.substringAfter(':').split(',', limit = textField + 1)
                if (fields.size <= textField) continue
                val start = assTime(fields[1]) ?: continue
                val end = assTime(fields[2]) ?: continue
                out.add(Cue(start, end, clean(fields[textField].replace("\\N", "\n").replace("\\n", "\n"))))
            }
        } else {
            val lines = text.lines()
            var i = 0
            while (i < lines.size) {
                val m = SRT_TIME.find(lines[i])
                if (m == null) { i++; continue }
                val g = m.groupValues
                val start = ms(g[1], g[2], g[3], g[4])
                val end = ms(g[5], g[6], g[7], g[8])
                val body = StringBuilder()
                i++
                while (i < lines.size && lines[i].isNotBlank() && SRT_TIME.find(lines[i]) == null) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i].trim())
                    i++
                }
                out.add(Cue(start, end, clean(body.toString())))
            }
        }
        out.sortBy { it.start }
        return out
    }

    private fun ms(h: String, m: String, s: String, f: String): Long {
        val frac = f.padEnd(3, '0').take(3).toLong()
        return ((h.toLongOrNull() ?: 0L) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + frac
    }

    /** "0:00:01.50" (hundredths) */
    private fun assTime(t: String): Long? {
        val p = t.trim().split(':')
        if (p.size != 3) return null
        val sec = p[2].split('.')
        return try {
            (p[0].toLong() * 3600 + p[1].toLong() * 60 + sec[0].toLong()) * 1000 + (sec.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L)
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun clean(s: String) = s.replace(TAGS, "").trim()

    private val TEXT_MIMES = setOf("application/x-subrip", "text/vtt", "text/3gpp-tt", "application/x-quicktime-tx3g", "text/x-ssa")

    /**
     * The lines of the [index]th text track of a local video (in the order of the file), when the platform's
     * extractor knows it; empty else. Blocking.
     */
    fun readEmbedded(context: Context, uri: Uri, index: Int): List<Cue> {
        val key = "$uri#$index"
        cache.get(key)?.let { return it }
        val extractor = MediaExtractor()
        val out = ArrayList<Cue>()
        try {
            if (uri.scheme == "file") extractor.setDataSource(uri.path!!) else extractor.setDataSource(context, uri, null)
            val tracks = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) in TEXT_MIMES }
            val track = tracks.getOrNull(index) ?: return emptyList()
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(64 * 1024)
            val starts = ArrayList<Pair<Long, String>>()
            while (true) {
                buffer.clear()
                val n = extractor.readSampleData(buffer, 0)
                if (n < 0) break
                val bytes = ByteArray(n)
                buffer.get(bytes, 0, n)
                starts.add(extractor.sampleTime / 1000 to clean(String(bytes, Charsets.UTF_8)))
                if (!extractor.advance() || starts.size > 20000 || Thread.currentThread().isInterrupted) break
            }
            starts.forEachIndexed { i, (start, text) ->
                val next = starts.getOrNull(i + 1)?.first ?: (start + MAX_OPEN_LINE_MS)
                out.add(Cue(start, minOf(next, start + MAX_OPEN_LINE_MS), text))
            }
        } catch (e: Exception) {
            Log.w(TAG, "no lines in the track $index of $uri: $e")
        } finally {
            extractor.release()
        }
        cache.put(key, out)
        return out
    }

    /** The text tracks of a local video the platform's extractor knows, without reading them */
    fun embeddedCount(context: Context, uri: Uri): Int {
        val extractor = MediaExtractor()
        return try {
            if (uri.scheme == "file") extractor.setDataSource(uri.path!!) else extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).count { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) in TEXT_MIMES }
        } catch (e: Exception) {
            0
        } finally {
            extractor.release()
        }
    }

    /** The subtitle files next to a local video that the player finds by itself ("movie.srt", "movie.en.srt") */
    fun siblings(video: File): List<File> {
        val base = video.name.substringBeforeLast('.')
        return video.parentFile?.listFiles { f ->
            f.isFile && f.extension.lowercase(Locale.ROOT) in EXTENSIONS && f.name.startsWith(base)
        }?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: emptyList()
    }
}
