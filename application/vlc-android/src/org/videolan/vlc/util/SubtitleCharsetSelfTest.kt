/*
 * VLC Redesign, debug builds: the subtitle encoding corpus of the desktop (extras/juxtopposed/fixtures/subtitles)
 * read on the device, with the device's own character sets: each file detected (its hints: the language in its
 * name), decoded and compared with its .expected text. The score goes to the log (tag VLC/CharsetSelfTest).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.util.Log
import java.io.File
import java.text.Normalizer

object SubtitleCharsetSelfTest {
    private const val TAG = "VLC/CharsetSelfTest"

    private fun clean(s: String) = Normalizer.normalize(s.removePrefix("﻿").replace("\r\n", "\n"), Normalizer.Form.NFC)

    fun run(dir: File) {
        var pass = 0
        var total = 0
        val files = dir.listFiles()?.sortedBy { it.name } ?: run { Log.w(TAG, "no folder $dir"); return }
        for (f in files) {
            if (f.name.endsWith(".expected")) continue
            val exp = File(f.path + ".expected")
            if (!exp.exists()) continue
            total++
            val bytes = f.readBytes()
            val parts = f.name.split('.')
            val hints = if (parts.size == 3) listOf(parts[1]) else emptyList()
            val cs = SubtitleCharset.detect(bytes, hints, "CP1252") ?: "UTF-8"
            val java = SubtitleCharsetDetector.javaCharset(cs)
            val got = java?.let { clean(String(bytes, it)) }
            if (got != null && got == clean(exp.readText(Charsets.UTF_8))) pass++
            else Log.w(TAG, "FAIL ${f.name}: $cs (${java?.name() ?: "no such charset on this device"})")
        }
        Log.i(TAG, "score: $pass / $total")
    }
}
