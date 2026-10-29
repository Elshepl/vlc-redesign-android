/*
 * VLC Redesign, Live TV: downloads of the playlists, the guides, the logos and the Xtream Codes answers, and the
 * secrets of the sources (their links, their passwords) kept encrypted by the Android keystore, as the desktop
 * keeps them in VLC's keystore.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object TvNet {
    const val USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

    fun hashOf(text: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** The host of an address, for the messages (never the whole address: it often holds a token). */
    fun hostOf(url: String) = try { Uri.parse(url).host ?: "a file" } catch (e: Exception) { "?" }

    private fun open(context: Context, address: String, cancel: AtomicBoolean?): Pair<InputStream, Long> {
        val uri = Uri.parse(address)
        when (uri.scheme?.lowercase()) {
            "content" -> {
                val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("the file cannot be read")
                return stream to -1L
            }
            "file", null, "" -> {
                val f = File(uri.path ?: address)
                return f.inputStream() to f.length()
            }
        }
        var url = URL(address)
        var redirects = 0
        while (true) {
            if (cancel?.get() == true) throw CancelledException()
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            // as it is: a gzip-compressed guide stays so (read inflated later)
            c.setRequestProperty("Accept-Encoding", "identity")
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location == null || ++redirects > 8) throw IOException("too many redirections")
                url = URL(url, location)
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            return c.inputStream to c.contentLengthLong
        }
    }

    /**
     * Saves the resource at address (http(s), file, content) to file, as it is; progress gets the bytes received
     * and the size when known (-1). The file is replaced only once the whole of it came.
     */
    fun download(context: Context, address: String, file: File, cancel: AtomicBoolean?, progress: ((Long, Long) -> Unit)? = null) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".part")
        try {
            val (input, total) = open(context, address, cancel)
            input.use { inp ->
                tmp.outputStream().buffered(256 * 1024).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    var lastReport = 0L
                    while (true) {
                        if (cancel?.get() == true) throw CancelledException()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        received += n
                        if (progress != null && received - lastReport > 256 * 1024) {
                            lastReport = received
                            progress(received, total)
                        }
                    }
                    progress?.invoke(received, total)
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("the file cannot be written")
            }
        } finally {
            tmp.delete()
        }
    }

    /** A small answer (an Xtream Codes API call, the catalog) read into memory. */
    fun fetch(context: Context, address: String, cancel: AtomicBoolean?, limit: Int = 64 shl 20): String {
        val (input, _) = open(context, address, cancel)
        input.use { inp ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                if (cancel?.get() == true) throw CancelledException()
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw IOException("the answer is too large")
            }
            return out.toString("UTF-8")
        }
    }

    /* The secrets: AES-GCM with a key of the Android keystore (never leaves it) */
    private const val KEY_ALIAS = "vlc_redesign_livetv"

    private fun key(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    } catch (e: Exception) {
        null
    }

    fun encrypt(text: String): String {
        if (text.isEmpty()) return ""
        val k = key() ?: return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    fun decrypt(text: String): String {
        if (text.isEmpty()) return ""
        val k = key() ?: return ""
        return try {
            val iv = Base64.decode(text.substringBefore(':'), Base64.NO_WRAP)
            val data = Base64.decode(text.substringAfter(':'), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
