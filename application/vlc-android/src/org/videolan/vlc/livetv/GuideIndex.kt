/*
 * VLC Redesign, Live TV: the index of a guide, what was read of its XMLTV file for these channels, kept next to
 * it (as the desktop's service.cpp GuideIndex). A guide of 200 MB takes many seconds to read again at every
 * start; its index a fraction of a second. It holds every programme from the first day kept on, the texts once
 * each, and names the file of the descriptions it goes with.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

object GuideIndex {
    private const val MAGIC = 0x56544749   // "VTGI"
    private const val VERSION = 1

    class Index {
        val lists = HashMap<String, List<Entry>>()
        val idByName = HashMap<String, String>()
        val languages = HashSet<String>()
        var descName = ""
    }

    /** What the index was made for: the channels, the languages, the days back. */
    fun signatureOf(filter: GuideFilter, days: Int): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(days.toString().toByteArray())
        md.update("\n\n".toByteArray())
        md.update(filter.languages.sorted().joinToString("\n").toByteArray())
        md.update("\n\n".toByteArray())
        md.update(filter.keys.sorted().joinToString("\n").toByteArray())
        return md.digest()
    }

    fun write(path: File, xmltv: File, signature: ByteArray, index: Index): Boolean {
        val desc = File(xmltv.parentFile, index.descName)
        val ids = HashMap<String, Int>()
        val texts = ArrayList<String>()
        fun idOf(text: String) = ids.getOrPut(text) { texts.add(text); texts.size - 1 }
        // the texts first (their ids come from the entries)
        for (list in index.lists.values) for (e in list) { idOf(e.title); idOf(e.subtitle); idOf(e.category); idOf(e.episode) }
        val tmp = File(path.path + ".tmp")
        return try {
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(xmltv.length())
                out.writeLong(xmltv.lastModified())
                out.writeInt(signature.size); out.write(signature)
                writeString(out, index.descName)
                out.writeLong(if (desc.exists()) desc.length() else -1L)
                out.writeInt(index.languages.size)
                for (l in index.languages) writeString(out, l)
                out.writeInt(index.idByName.size)
                for ((k, v) in index.idByName) { writeString(out, k); writeString(out, v) }
                out.writeInt(texts.size)
                for (t in texts) writeString(out, t)
                out.writeInt(index.lists.size)
                for ((key, list) in index.lists) {
                    writeString(out, key)
                    out.writeInt(list.size)
                    for (e in list) {
                        out.writeLong(e.start); out.writeLong(e.stop)
                        out.writeInt(ids[e.title]!!); out.writeInt(ids[e.subtitle]!!)
                        out.writeInt(ids[e.category]!!); out.writeInt(ids[e.episode]!!)
                        out.writeLong(e.descOffset); out.writeInt(e.descLength)
                    }
                }
            }
            tmp.renameTo(path) || run { path.delete(); tmp.renameTo(path) }
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    private fun writeString(out: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
    }

    /** The index when it is the one of this XMLTV file and these channels, null otherwise. */
    fun read(path: File, xmltv: File, signature: ByteArray): Index? {
        if (!path.exists()) return null
        return try {
            val bytes = path.readBytes()
            val b = ByteBuffer.wrap(bytes)
            fun str(): String {
                val n = b.int
                if (n < 0 || n > b.remaining()) throw IllegalStateException("bad index")
                val s = String(bytes, b.position(), n, Charsets.UTF_8)
                b.position(b.position() + n)
                return s
            }
            if (b.int != MAGIC || b.int != VERSION) return null
            if (b.long != xmltv.length() || b.long != xmltv.lastModified()) return null
            val sigLen = b.int
            if (sigLen != signature.size) return null
            val sig = ByteArray(sigLen).also { b.get(it) }
            if (!sig.contentEquals(signature)) return null
            val index = Index()
            index.descName = str()
            val descSize = b.long
            // the descriptions it points into, as they were written
            val desc = File(xmltv.parentFile, index.descName)
            if (index.descName.isEmpty() || index.descName.contains('/') || (if (desc.exists()) desc.length() else -1L) != descSize) return null
            repeat(b.int) { index.languages.add(str()) }
            repeat(b.int) { val k = str(); index.idByName[k] = str() }
            val textCount = b.int
            if (textCount < 0 || textCount > bytes.size) return null
            val texts = Array(textCount) { str() }
            val keys = b.int
            for (k in 0 until keys) {
                val key = str()
                val count = b.int
                if (count < 0 || count > (1 shl 24)) return null
                val list = ArrayList<Entry>(count)
                for (i in 0 until count) {
                    val start = b.long
                    val stop = b.long
                    val title = b.int; val subtitle = b.int; val category = b.int; val episode = b.int
                    val offset = b.long
                    val length = b.int
                    if (title !in 0 until textCount || subtitle !in 0 until textCount || category !in 0 until textCount || episode !in 0 until textCount) return null
                    list.add(Entry(start, stop, texts[title], texts[subtitle], texts[category], texts[episode], offset, length))
                }
                index.lists[key] = list
            }
            index
        } catch (e: Exception) {
            null
        }
    }

    /** A description of the file of a guide's descriptions. */
    fun readDescription(path: String, offset: Long, length: Int): String {
        if (offset < 0 || length <= 0 || path.isEmpty()) return ""
        return try {
            java.io.RandomAccessFile(path, "r").use { f ->
                f.seek(offset)
                val b = ByteArray(length)
                f.readFully(b)
                String(b, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }
}
