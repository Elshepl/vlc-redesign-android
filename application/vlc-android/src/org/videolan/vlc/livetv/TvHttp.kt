/*
 * VLC Redesign, Live TV: the requests of the accounts (a TV server, a paid guide): any method, headers, a body, and
 * the account's sign-in when the server asks for it (a 401: Basic, or Digest as Tvheadend asks by default). Plain
 * java.net, without Android's classes (the unit tests run it on the JVM).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A user name and password the server asks for (Basic or Digest, as it says). */
class TvAuth(val user: String, val password: String) {
    val isEmpty get() = user.isEmpty() && password.isEmpty()
}

/** A request of an account. */
class TvRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val auth: TvAuth? = null,
    val timeoutMs: Int = 30_000,
    val limit: Int = 32 shl 20,
)

/** Its answer: the status (0: no answer), the body, why it failed when it did. */
class TvAnswer(val status: Int, val body: String, val error: String = "") {
    val ok get() = error.isEmpty() && status in 200..299
}

object TvHttp {

    /** The answer of a request; never throws (a failure is status 0 and its reason). */
    fun request(r: TvRequest, cancel: AtomicBoolean? = null): TvAnswer = try {
        var authorization: String? = null
        var answer: TvAnswer
        var attempt = 0
        while (true) {
            if (cancel?.get() == true) throw CancelledException()
            val c = (URL(r.url).openConnection() as HttpURLConnection)
            c.connectTimeout = minOf(r.timeoutMs, 20_000)
            c.readTimeout = r.timeoutMs
            c.instanceFollowRedirects = true
            c.requestMethod = r.method
            c.setRequestProperty("User-Agent", r.headers["User-Agent"] ?: TvNet.USER_AGENT)
            for ((k, v) in r.headers) if (k != "User-Agent") c.setRequestProperty(k, v)
            if (authorization != null) c.setRequestProperty("Authorization", authorization)
            val data = r.body?.toByteArray(Charsets.UTF_8)
            if (data != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(data.size)
                c.outputStream.use { it.write(data) }
            }
            val status = c.responseCode
            if (status == 401 && attempt == 0 && r.auth != null && !r.auth.isEmpty) {
                // the server says how it wants the account: answered once
                val challenge = c.getHeaderField("WWW-Authenticate") ?: ""
                c.errorStream?.close()
                c.disconnect()
                authorization = authorizationFor(challenge, r.auth, r.method, URL(r.url))
                if (authorization != null) { attempt++; continue }
                answer = TvAnswer(401, "")
                break
            }
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { read(it, r.limit, cancel) } ?: ""
            c.disconnect()
            answer = TvAnswer(status, body)
            break
        }
        answer
    } catch (e: CancelledException) {
        throw e
    } catch (e: Exception) {
        // (its kind only: a message may hold the address, and a token with it)
        TvAnswer(0, "", e.javaClass.simpleName)
    }

    private fun read(input: java.io.InputStream, limit: Int, cancel: AtomicBoolean?): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            if (cancel?.get() == true) throw CancelledException()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException("the answer is too large")
        }
        return out.toString("UTF-8")
    }

    /** The Authorization header for a server's challenge (Basic or Digest), null when it asks for neither. */
    fun authorizationFor(challenge: String, auth: TvAuth, method: String, url: URL, cnonce: String = newCnonce()): String? {
        val scheme = challenge.trim().substringBefore(' ').lowercase(Locale.ROOT)
        return when (scheme) {
            "basic" -> basic(auth)
            "digest" -> {
                val uri = url.path.ifEmpty { "/" } + (url.query?.let { "?$it" } ?: "")
                digest(parseChallenge(challenge), auth, method, uri, cnonce, 1)
            }
            else -> null
        }
    }

    fun basic(auth: TvAuth) = "Basic " + base64("${auth.user}:${auth.password}".toByteArray(Charsets.UTF_8))

    // (java.util.Base64 is Android 8; android.util.Base64 isn't on the JVM of the tests)
    fun base64(data: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(table[b0 shr 2])
            sb.append(table[((b0 and 3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            sb.append(if (b1 < 0) '=' else table[((b1 and 15) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            sb.append(if (b2 < 0) '=' else table[b2 and 63])
            i += 3
        }
        return sb.toString()
    }

    /** realm="x", nonce="y", qop="auth,auth-int", algorithm=MD5 -> {realm: x, ...} (the names in lower case) */
    fun parseChallenge(challenge: String): Map<String, String> {
        val out = HashMap<String, String>()
        val text = challenge.trim().substringAfter(' ', "")
        var i = 0
        while (i < text.length) {
            while (i < text.length && (text[i] == ',' || text[i].isWhitespace())) i++
            val eq = text.indexOf('=', i)
            if (eq < 0) break
            val name = text.substring(i, eq).trim().lowercase(Locale.ROOT)
            var j = eq + 1
            val value: String
            if (j < text.length && text[j] == '"') {
                val sb = StringBuilder()
                j++
                while (j < text.length && text[j] != '"') {
                    if (text[j] == '\\' && j + 1 < text.length) j++
                    sb.append(text[j]); j++
                }
                value = sb.toString()
                j++
            } else {
                val end = text.indexOf(',', j).let { if (it < 0) text.length else it }
                value = text.substring(j, end).trim()
                j = end
            }
            out[name] = value
            i = j
        }
        return out
    }

    /** The Digest answer of RFC 2617 (MD5 or MD5-sess, with qop auth when offered). */
    fun digest(c: Map<String, String>, auth: TvAuth, method: String, uri: String, cnonce: String, nc: Int): String? {
        val realm = c["realm"] ?: return null
        val nonce = c["nonce"] ?: return null
        val algorithm = c["algorithm"] ?: "MD5"
        if (!algorithm.equals("MD5", true) && !algorithm.equals("MD5-sess", true)) return null
        val qop = c["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it.equals("auth", true) }
        val ncText = "%08x".format(Locale.ROOT, nc)
        var ha1 = md5("${auth.user}:$realm:${auth.password}")
        if (algorithm.equals("MD5-sess", true)) ha1 = md5("$ha1:$nonce:$cnonce")
        val ha2 = md5("$method:$uri")
        val response = if (qop != null) md5("$ha1:$nonce:$ncText:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        val sb = StringBuilder("Digest username=\"${auth.user}\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
        sb.append(", algorithm=").append(algorithm)
        if (qop != null) sb.append(", qop=").append(qop).append(", nc=").append(ncText).append(", cnonce=\"").append(cnonce).append('"')
        c["opaque"]?.let { sb.append(", opaque=\"").append(it).append('"') }
        return sb.toString()
    }

    fun md5(text: String) = hex(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)))
    fun sha1(text: String) = hex(MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)))
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun newCnonce(): String {
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        return hex(b)
    }
}
