/*
 * VLC Redesign: the subtitle font of Settings (sans serif, serif, monospace...) as libvlc can find it.
 *
 * libvlc 3 on Android looks fonts up through fontconfig, which knows the families by their own names ("Noto Serif",
 * "Droid Sans Mono"), not by Android's generic names: "--freetype-font=serif" fell back to the default font. The
 * generic name is found in the system's font list (/system/etc/fonts.xml, its aliases too), and the family name is
 * read from the name table of its regular font file, so it follows the device (Samsung's list, another vendor's).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.resources

import java.io.File
import java.io.RandomAccessFile

object RdSubtitleFont {
    private const val FONTS_XML = "/system/etc/fonts.xml"
    private const val FONTS_DIR = "/system/fonts"
    private val cache = HashMap<String, String>()

    /** The family name libvlc finds for [generic] (a name of Android's font list); [generic] itself when unknown */
    @Synchronized
    fun family(generic: String): String = cache.getOrPut(generic) {
        try {
            fontFile(generic)?.let { familyName(File(FONTS_DIR, it)) } ?: generic
        } catch (e: Exception) {
            generic
        }
    }

    /** The regular font file of a family of the system's font list, following its aliases */
    private fun fontFile(generic: String): String? {
        val xml = File(FONTS_XML).takeIf { it.canRead() }?.readText() ?: return null
        var name = generic
        repeat(4) {
            Regex("""<family\s+name="${Regex.escape(name)}"[^>]*>(.*?)</family>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.let { family ->
                val fonts = Regex("""<font\b([^>]*)>\s*([^<\s]+)""").findAll(family.groupValues[1]).toList()
                val regular = fonts.firstOrNull { f -> """weight="400"""" in f.groupValues[1] && """style="italic"""" !in f.groupValues[1] }
                return (regular ?: fonts.firstOrNull())?.groupValues?.get(2)
            }
            name = Regex("""<alias\s+name="${Regex.escape(name)}"\s+to="([^"]+)"""").find(xml)?.groupValues?.get(1) ?: return null
        }
        return null
    }

    /** The family name of an OpenType / TrueType file (the first face of a collection): name 16, else 1 */
    private fun familyName(file: File): String? = RandomAccessFile(file, "r").use { f ->
        var offset = 0L
        f.seek(0)
        if (f.readInt() == 0x74746366) { // "ttcf": the first font of the collection
            f.seek(12); offset = f.readInt().toLong() and 0xffffffffL
        }
        f.seek(offset + 4)
        val tables = f.readUnsignedShort()
        var nameTable = -1L
        for (i in 0 until tables) {
            f.seek(offset + 12 + i * 16L)
            val tag = f.readInt()
            f.skipBytes(4)
            val at = f.readInt().toLong() and 0xffffffffL
            if (tag == 0x6e616d65) { nameTable = at; break } // "name"
        }
        if (nameTable < 0) return@use null
        f.seek(nameTable + 2)
        val count = f.readUnsignedShort()
        val strings = nameTable + f.readUnsignedShort()
        val found = HashMap<Int, String>()
        for (i in 0 until count) {
            f.seek(nameTable + 6 + i * 12L)
            val platform = f.readUnsignedShort(); f.skipBytes(2)
            val language = f.readUnsignedShort()
            val id = f.readUnsignedShort()
            val length = f.readUnsignedShort()
            val at = f.readUnsignedShort()
            // Windows Unicode in US English (UTF-16 big endian)
            if (platform != 3 || language != 0x409 || (id != 1 && id != 16) || id in found) continue
            val bytes = ByteArray(length)
            f.seek(strings + at); f.readFully(bytes)
            found[id] = String(bytes, Charsets.UTF_16BE)
        }
        (found[16] ?: found[1])?.takeIf { it.isNotBlank() }
    }
}
