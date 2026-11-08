package org.videolan.vlc.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Random

/**
 * VLC Redesign, Live TV requests (TvHttp): the Basic and Digest sign-ins of the TV servers (Tvheadend, an M3U behind
 * a password), the RFC 2617 example, and a real request to a server on this computer that asks for each.
 */
class TvHttpTest {

    @Test
    fun base64AsTheJvmWritesIt() {
        assertEquals("QWxhZGRpbjpvcGVuIHNlc2FtZQ==", TvHttp.base64("Aladdin:open sesame".toByteArray()))
        assertEquals("", TvHttp.base64(ByteArray(0)))
        val r = Random(7)
        for (n in 1..40) {
            val b = ByteArray(n).also { r.nextBytes(it) }
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), TvHttp.base64(b))
        }
    }

    @Test
    fun basic() {
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", TvHttp.basic(TvAuth("Aladdin", "open sesame")))
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", TvHttp.authorizationFor("Basic realm=\"tv\"", TvAuth("Aladdin", "open sesame"), "GET", URL("http://tvh/x")))
    }

    @Test
    fun aChallengeRead() {
        val c = TvHttp.parseChallenge("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\", algorithm=MD5, stale=FALSE")
        assertEquals("testrealm@host.com", c["realm"])
        assertEquals("auth,auth-int", c["qop"])
        assertEquals("dcd98b7102dd2f0e8b11d0f600bfb0c093", c["nonce"])
        assertEquals("MD5", c["algorithm"])
        assertEquals("FALSE", c["stale"])
        assertEquals("a \"quoted\" one", TvHttp.parseChallenge("Digest realm=\"a \\\"quoted\\\" one\"")["realm"])
        assertTrue(TvHttp.parseChallenge("Bearer").isEmpty())
    }

    @Test
    fun digestOfRfc2617() {
        // RFC 2617 3.5: Mufasa, "Circle Of Life"
        val c = TvHttp.parseChallenge("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")
        val d = TvHttp.digest(c, TvAuth("Mufasa", "Circle Of Life"), "GET", "/dir/index.html", "0a4f113b", 1)!!
        assertTrue(d, d.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(d, d.startsWith("Digest username=\"Mufasa\", realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", uri=\"/dir/index.html\""))
        assertTrue(d, d.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""))
        assertTrue(d, d.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
        // the address's path and query are its uri
        val a = TvHttp.authorizationFor("Digest realm=\"r\", nonce=\"n\"", TvAuth("u", "p"), "GET", URL("http://tvh:9981/playlist/channels?profile=pass"), "c")!!
        assertTrue(a, a.contains("uri=\"/playlist/channels?profile=pass\""))
        // no qop (RFC 2069): md5(ha1:nonce:ha2)
        val ha1 = TvHttp.md5("u:r:p")
        val ha2 = TvHttp.md5("GET:/playlist/channels?profile=pass")
        assertTrue(a, a.contains("response=\"" + TvHttp.md5("$ha1:n:$ha2") + "\""))
        // MD5-sess, and an algorithm it can't answer
        val s = TvHttp.digest(mapOf("realm" to "r", "nonce" to "n", "algorithm" to "MD5-sess", "qop" to "auth"), TvAuth("u", "p"), "GET", "/", "c", 1)!!
        val sessHa1 = TvHttp.md5("$ha1:n:c")
        assertTrue(s, s.contains("response=\"" + TvHttp.md5("$sessHa1:n:00000001:c:auth:" + TvHttp.md5("GET:/")) + "\""))
        assertNull(TvHttp.digest(mapOf("realm" to "r", "nonce" to "n", "algorithm" to "SHA-256"), TvAuth("u", "p"), "GET", "/", "c", 1))
        assertNull(TvHttp.authorizationFor("Bearer realm=\"x\"", TvAuth("u", "p"), "GET", URL("http://x/")))
    }

    /** an answer of the test server: its status, a header, its body */
    private class Reply(val status: Int, val header: String = "", val body: String = "")

    /** a small HTTP server on this computer (the JDK's isn't on the tests' class path): a request at a time */
    private fun serve(handler: (method: String, uri: String, headers: Map<String, String>) -> Reply): ServerSocket {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { break }
                s.use { sock ->
                    val input = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                    val first = input.readLine() ?: return@use
                    val headers = HashMap<String, String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
                    }
                    val parts = first.split(' ')
                    val r = handler(parts[0], parts.getOrElse(1) { "/" }, headers)
                    val body = r.body.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 ${r.status} X\r\n" + (if (r.header.isNotEmpty()) r.header + "\r\n" else "") +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    /** a server on this computer asking for Digest (as Tvheadend does) or Basic: the request answers it once */
    @Test
    fun aServerThatAsksForTheAccount() {
        val digestAsked = "WWW-Authenticate: Digest realm=\"tvheadend\", qop=\"auth\", nonce=\"abc\""
        val server = serve { method, uri, headers ->
            val h = headers["authorization"] ?: ""
            if (uri.startsWith("/digest")) {
                val c = TvHttp.parseChallenge(h)
                val want = TvHttp.digest(mapOf("realm" to "tvheadend", "nonce" to "abc", "qop" to "auth"), TvAuth("demo", "secret"), method, uri, c["cnonce"] ?: "", 1)
                    ?.let { TvHttp.parseChallenge(it)["response"] }
                if (h.startsWith("Digest ") && c["uri"] == uri && c["response"] == want) Reply(200, body = "#EXTM3U\n") else Reply(401, digestAsked)
            } else {
                if (h == TvHttp.basic(TvAuth("demo", "secret"))) Reply(200, body = "ok") else Reply(401, "WWW-Authenticate: Basic realm=\"iptv\"")
            }
        }
        try {
            val base = "http://127.0.0.1:" + server.localPort
            val d = TvHttp.request(TvRequest("$base/digest/playlist?profile=pass", auth = TvAuth("demo", "secret")))
            assertEquals(200, d.status)
            assertEquals("#EXTM3U\n", d.body)
            assertEquals(401, TvHttp.request(TvRequest("$base/digest/playlist", auth = TvAuth("demo", "wrong"))).status)
            assertEquals(401, TvHttp.request(TvRequest("$base/digest/playlist")).status)
            val b = TvHttp.request(TvRequest("$base/basic", auth = TvAuth("demo", "secret")))
            assertTrue(b.ok)
            assertEquals("ok", b.body)
            assertEquals(401, TvHttp.request(TvRequest("$base/basic", auth = TvAuth("demo", "nope"))).status)
        } finally {
            server.close()
        }
        // no answer: status 0 and the kind of failure only (never the address)
        val none = TvHttp.request(TvRequest("http://127.0.0.1:1/x", timeoutMs = 2000))
        assertEquals(0, none.status)
        assertTrue(none.error, none.error.isNotEmpty() && !none.error.contains("127.0.0.1"))
    }
}
