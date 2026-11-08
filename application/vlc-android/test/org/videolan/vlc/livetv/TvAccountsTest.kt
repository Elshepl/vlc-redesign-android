package org.videolan.vlc.livetv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.StringWriter

/**
 * VLC Redesign, Live TV accounts (TvAccounts): every type's Sign in / Test, channels and guide, its requests answered
 * from saved answers (test/fixtures/livetv/tvaccounts: the same fixtures as the Samsung TV app's, written from the
 * servers' API documents and what they answered), no network.
 */
class TvAccountsTest {

    private val calls = ArrayList<TvRequest>()
    private var routes: List<Pair<String, (TvRequest) -> TvAnswer>> = emptyList()

    private fun fixture(name: String): String = listOf("test/fixtures/livetv/tvaccounts/$name", "application/vlc-android/test/fixtures/livetv/tvaccounts/$name")
        .map { File(it) }.first { it.exists() }.readText()

    private fun ok(name: String): (TvRequest) -> TvAnswer = { TvAnswer(200, fixture(name)) }

    @Before
    fun setUp() {
        TvAccounts.http = { r ->
            calls.add(r)
            routes.firstOrNull { r.url.contains(it.first) }?.second?.invoke(r) ?: TvAnswer(404, "")
        }
        TvAccounts.deviceId = "test-device"
        TvAccounts.pollMs = 1
    }

    @After
    fun tearDown() {
        TvAccounts.http = { TvHttp.request(it) }
    }

    @Test
    fun theTypesAndTheirFields() {
        assertEquals(listOf("jellyfin", "emby", "tvheadend", "nextpvr", "channelsdvr", "hdhomerun", "schedulesdirect", "m3ulogin"), TvAccounts.TYPES.map { it.id })
        val sd = TvAccounts.type("schedulesdirect")!!
        assertTrue(sd.guideOnly)
        assertEquals(listOf("username", "password"), sd.fields.map { it.name })
        assertTrue(TvAccounts.type("jellyfin")!!.signsIn)
        assertFalse(TvAccounts.type("hdhomerun")!!.signsIn)
        assertTrue(TvAccounts.type("emby")!!.fields.first { it.name == "key" }.let { it.optional && it.secret })
    }

    @Test
    fun aTypedAddress() {
        assertEquals("http://192.168.1.5:8096", TvAccounts.address("192.168.1.5", "jellyfin"))
        assertEquals("http://nas.local:8920", TvAccounts.address("nas.local:8920/", "jellyfin"))
        // (the same as the Samsung TV app's)
        assertEquals("http://192.168.1.20:8089", TvAccounts.address("192.168.1.20", "channelsdvr"))
        assertEquals("http://tvh.home:9982", TvAccounts.address("tvh.home:9982/", "tvheadend"))
        assertEquals("http://192.168.1.30", TvAccounts.address("http://192.168.1.30", "hdhomerun"))
        assertEquals("https://media.example:8089", TvAccounts.address("https://media.example/", "channelsdvr"))
        assertEquals("http://192.168.1.30", TvAccounts.address("192.168.1.30", "hdhomerun"))
        assertEquals("http://box:9981", TvAccounts.address(" box ", "tvheadend"))
        assertEquals("http://box:8866", TvAccounts.address("box", "nextpvr"))
        assertEquals("http://[fe80::1]:8089", TvAccounts.address("http://[fe80::1]", "channelsdvr"))
        assertEquals("", TvAccounts.address("  ", "jellyfin"))
    }

    @Test
    fun channelsDvr() {
        routes = listOf("/devices/ANY/channels.m3u" to ok("demo.m3u"))
        val a = TvAccount(address = "http://dvr:8089")
        assertEquals(TvCheckState.WORKS, TvAccounts.check("channelsdvr", a).state)
        val l = TvAccounts.load("channelsdvr", a)
        assertEquals(3, l.channels.size)
        assertEquals(listOf("http://dvr:8089/devices/ANY/guide/xmltv"), l.guides)
        assertFalse(l.jsonGuide)
        assertTrue(l.playlist.startsWith("#EXTM3U"))
    }

    @Test
    fun tvheadendWithTheAccount() {
        val m3u = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\",One\nhttp://tvh:9981/stream/channelid/1?profile=pass\n"
        routes = listOf("/playlist/channels" to { r -> if (r.auth?.password == "secret") TvAnswer(200, m3u) else TvAnswer(401, "") })
        val a = TvAccount(address = "http://tvh:9981", username = "demo", password = "secret")
        assertEquals(TvCheckState.SIGNED_IN, TvAccounts.check("tvheadend", a).state)
        val l = TvAccounts.load("tvheadend", a)
        // the player answers the server's sign-in from the address
        assertEquals("http://demo:secret@tvh:9981/stream/channelid/1?profile=pass", l.channels[0].url)
        assertEquals("http://tvh:9981/xmltv/channels", l.guides[0])
        assertEquals("demo", TvAccounts.authOf("tvheadend", a)!!.user)
        assertEquals(TvCheckState.WRONG, TvAccounts.check("tvheadend", TvAccount(address = "http://tvh:9981", username = "demo", password = "nope")).state)
        try { TvAccounts.load("tvheadend", TvAccount(address = "http://tvh:9981", username = "demo", password = "nope")); fail() } catch (e: TvAccountException) { assertTrue(e.refused) }
    }

    @Test
    fun tvheadendWithItsAccessCode() {
        routes = listOf("/playlist/auth/channels" to ok("demo.m3u"))
        val a = TvAccount(address = "http://tvh:9981", code = "ab c")
        val l = TvAccounts.load("tvheadend", a)
        assertEquals("http://tvh:9981/playlist/auth/channels?auth=ab%20c&profile=pass", calls.last().url)
        assertEquals("http://tvh:9981/xmltv/channels?auth=ab%20c", l.guides[0])
        assertEquals(null, calls.last().auth)
        assertEquals(null, TvAccounts.authOf("tvheadend", a))
    }

    @Test
    fun hdhomerun() {
        routes = listOf("/discover.json" to ok("hdhr-discover.json"), "/lineup.json" to ok("hdhr-lineup.json"), "api.hdhomerun.com/api/guide" to ok("hdhr-guide.json"))
        val a = TvAccount(address = "http://192.168.1.30")
        val c = TvAccounts.check("hdhomerun", a)
        assertEquals(TvCheckState.WORKS, c.state)
        assertEquals("Demo Tuner", c.user)
        val l = TvAccounts.load("hdhomerun", a)
        // (a DRM channel left out)
        assertTrue(l.channels.isNotEmpty())
        assertTrue(l.channels.none { it.name.isEmpty() })
        assertEquals("hdhr:2.1", l.channels[0].tvgId)
        assertEquals("http://192.168.1.30:5004/auto/v2.1", l.channels[0].url)
        assertEquals("HD", l.channels[0].group)
        assertEquals(2, l.channels[0].number)
        assertTrue(l.jsonGuide)
        assertEquals("demo-auth-123", l.token)
        a.token = l.token
        val g = TvAccounts.guide("hdhomerun", a, l.channels, 0, Long.MAX_VALUE)
        assertTrue(calls.last().url.endsWith("DeviceAuth=demo-auth-123"))
        assertEquals("hdhr:2.1", g[0].channel)
        assertEquals(1794124800L, g[0].start)
        assertEquals("Open Movie Night", g[0].title)
        val lineup = JSONArray(fixture("hdhr-lineup.json"))
        assertEquals((0 until lineup.length()).count { lineup.getJSONObject(it).optInt("DRM") != 1 }, l.channels.size)
    }

    @Test
    fun m3uWithASignIn() {
        val m3u = "#EXTM3U\n#EXTINF:-1,Demo One\nhttps://iptv.example/live/1.ts\n"
        routes = listOf("/get.php" to { r -> if (r.auth?.user == "me") TvAnswer(200, m3u) else TvAnswer(401, "") })
        val a = TvAccount(url = "https://iptv.example/get.php", guide = "https://iptv.example/xmltv.php", username = "me", password = "pw")
        assertEquals(TvCheckState.SIGNED_IN, TvAccounts.check("m3ulogin", a).state)
        val l = TvAccounts.load("m3ulogin", a)
        assertEquals("https://me:pw@iptv.example/live/1.ts", l.channels[0].url)
        assertEquals(listOf("https://iptv.example/xmltv.php"), l.guides)
        assertEquals(TvCheckState.WRONG, TvAccounts.check("m3ulogin", TvAccount(url = a.url, username = "else", password = "pw")).state)
    }

    @Test
    fun theHashesTheSignInsAskFor() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", TvHttp.md5(""))
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", TvHttp.md5("The quick brown fox jumps over the lazy dog"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", TvHttp.sha1("abc"))
        assertEquals(TvHttp.md5(":" + TvHttp.md5("1234") + ":np-salt"), TvAccounts.npLoginHash("1234", "np-salt"))
        // no PIN: NextPVR's default
        assertEquals(TvAccounts.npLoginHash("0000", "s"), TvAccounts.npLoginHash("", "s"))
    }

    @Test
    fun jellyfin() {
        routes = listOf(
            "/Users/AuthenticateByName" to { r -> if (JSONObject(r.body!!).optString("Pw") == "right") TvAnswer(200, fixture("jellyfin-auth.json")) else TvAnswer(401, "") },
            "/LiveTv/Channels" to ok("jellyfin-channels.json"),
            "/LiveTv/Programs" to ok("jellyfin-programs.json"))
        val a = TvAccount(address = "http://jf:8096", username = "demo", password = "right")
        val c = TvAccounts.check("jellyfin", a)
        assertEquals(TvCheckState.SIGNED_IN, c.state)
        assertEquals("Demo User", c.user)
        assertEquals("jf-token-abc", c.token)
        assertEquals("u-1234", c.userId)
        val auth = calls.last().headers["Authorization"]!!
        assertTrue(auth, auth.startsWith("MediaBrowser Client=\"Juxto%20Media%20Player\"") && auth.contains("DeviceId=\"test-device\""))
        val l = TvAccounts.load("jellyfin", a)
        assertEquals(listOf("jf:c1", "jf:c2"), l.channels.map { it.tvgId })
        assertEquals("http://jf:8096/Videos/c1/stream?static=true&ApiKey=jf-token-abc", l.channels[0].url)
        assertEquals("http://jf:8096/Items/c1/Images/Primary?tag=tag1&maxHeight=120", l.channels[0].logo)
        assertEquals("", l.channels[1].logo)
        assertEquals(101, l.channels[0].number)
        assertTrue(l.jsonGuide)
        assertTrue(calls.last().url.contains("userId=u-1234"))
        val g = TvAccounts.guide("jellyfin", a, l.channels, 1794124800, 1794211200)
        val body = JSONObject(calls.last().body!!)
        assertEquals("c1", body.getJSONArray("ChannelIds").getString(0))
        assertEquals("2026-11-08T08:00:00Z", body.getString("MinEndDate"))
        assertEquals("2026-11-09T08:00:00Z", body.getString("MaxStartDate"))
        assertEquals("u-1234", body.getString("UserId"))
        assertEquals("jf:c1", g[0].channel)
        assertEquals(TvAccounts.isoSeconds("2026-11-08T10:00:00Z"), g[0].start)
        assertEquals("Part One", g[0].subtitle)
        assertEquals(TvCheckState.WRONG, TvAccounts.check("jellyfin", TvAccount(address = "http://jf:8096", username = "demo", password = "wrong")).state)
    }

    @Test
    fun jellyfinQuickConnect() {
        var polls = 0
        routes = listOf(
            "/QuickConnect/Initiate" to ok("jellyfin-quickconnect-initiate.json"),
            "/QuickConnect/Connect" to { _ -> TvAnswer(200, JSONObject().put("Authenticated", ++polls >= 3).toString()) },
            "/Users/AuthenticateWithQuickConnect" to ok("jellyfin-auth.json"))
        val steps = ArrayList<TvCheck>()
        val c = TvAccounts.check("jellyfin", TvAccount(address = "http://jf:8096")) { steps.add(it) }
        assertEquals(TvCheckState.CODE, steps.single().state)
        assertEquals("123456", steps.single().code)
        assertEquals(TvCheckState.SIGNED_IN, c.state)
        assertEquals("jf-token-abc", c.token)
        assertEquals(3, polls)
        assertTrue(calls.any { it.url.endsWith("/QuickConnect/Connect?secret=s3cr3t") })
    }

    @Test
    fun emby() {
        routes = listOf(
            "/emby/System/Info" to { r -> if (r.url.contains("api_key=good")) TvAnswer(200, fixture("emby-system.json")) else TvAnswer(401, "") },
            "/emby/Users/AuthenticateByName" to ok("emby-auth.json"),
            "/emby/LiveTv/Channels" to ok("emby-channels.json"),
            "/emby/LiveTv/Programs" to ok("emby-programs.json"))
        val byKey = TvAccount(address = "http://emby:8096", key = "good")
        val c = TvAccounts.check("emby", byKey)
        assertEquals(TvCheckState.WORKS, c.state)
        assertEquals("Demo Emby", c.user)
        assertEquals(TvCheckState.REFUSED, TvAccounts.check("emby", TvAccount(address = "http://emby:8096", key = "bad")).state)
        val signed = TvAccounts.check("emby", TvAccount(address = "http://emby:8096", username = "demo", password = "x"))
        assertEquals(TvCheckState.SIGNED_IN, signed.state)
        assertEquals("emby-token", signed.token)
        val l = TvAccounts.load("emby", byKey)
        assertEquals("http://emby:8096/emby/videos/e1/stream?static=true&api_key=good", l.channels[0].url)
        assertEquals("emby:e1", l.channels[0].tvgId)
        assertEquals("good", calls.last().headers["X-Emby-Token"])
        val g = TvAccounts.guide("emby", byKey, l.channels, 1794124800, 1794211200)
        assertTrue(calls.last().url.contains("ChannelIds=e1&MinEndDate="))
        assertEquals("Late Picture Show", g[0].title)
        assertEquals(7200L, g[0].stop - g[0].start)
    }

    @Test
    fun nextpvr() {
        routes = listOf(
            "method=session.initiate" to ok("nextpvr-initiate.json"),
            "method=session.login" to { r -> if (r.url.endsWith("md5=" + TvAccounts.npLoginHash("1234", "np-salt"))) TvAnswer(200, fixture("nextpvr-login-ok.json")) else TvAnswer(200, "{\"stat\":\"fail\"}") },
            // (channel.listings first: it starts as channel.list does)
            "method=channel.listings" to ok("nextpvr-listings.json"),
            "method=channel.list" to ok("nextpvr-channels.json"))
        val a = TvAccount(address = "http://npvr:8866", pin = "1234")
        assertEquals(TvCheckState.WORKS, TvAccounts.check("nextpvr", a).state)
        assertEquals(TvCheckState.WRONG, TvAccounts.check("nextpvr", TvAccount(address = "http://npvr:8866", pin = "9999")).state)
        val l = TvAccounts.load("nextpvr", a)
        assertEquals("npvr:7148", l.channels[0].tvgId)
        assertEquals("Llama Toons", l.channels[0].name)
        assertEquals("http://npvr:8866/live?channeloid=7148&client=juxto-test-device&sid=np-sid-1", l.channels[0].url)
        assertTrue(l.channels[0].logo.contains("method=channel.icon&channel_id=7148"))
        val g = TvAccounts.guide("nextpvr", a, l.channels, 1794124800, 1794211200)
        assertEquals("Toon Time", g[0].title)
        assertEquals("npvr:7148", g[0].channel)
        // (its answers in XML too)
        assertEquals("abc", TvAccounts.npValue("<rsp stat=\"ok\"><sid>abc</sid></rsp>", "sid"))
    }

    @Test
    fun schedulesDirect() {
        routes = listOf(
            "20141201/token" to { r -> if (JSONObject(r.body!!).optString("password") == TvHttp.sha1("pass")) TvAnswer(200, fixture("sd-token.json")) else TvAnswer(400, fixture("sd-invalid-user.json")) },
            "20141201/lineups/" to ok("sd-lineup.json"),
            "20141201/lineups" to ok("sd-lineups.json"),
            "20141201/schedules" to ok("sd-schedules.json"),
            "20141201/programs" to ok("sd-programs.json"))
        val a = TvAccount(username = "demo", password = "pass")
        val c = TvAccounts.check("schedulesdirect", a)
        assertEquals(TvCheckState.SIGNED_IN, c.state)
        assertEquals("f3fca79eb2f1c4b5a5e3a6f2c5d8e9a1", c.token)
        assertEquals(1794210000L, c.tokenUntil)
        assertTrue(calls.last().headers["User-Agent"]!!.startsWith("JuxtoMediaPlayer/"))
        assertEquals(TvCheckState.WRONG, TvAccounts.check("schedulesdirect", TvAccount(username = "demo", password = "nope")).state)
        val channels = listOf(Channel().also { it.name = "Demo Open Movies" }, Channel().also { it.name = "KBNY" }, Channel().also { it.name = "Elsewhere" })
        val g = TvAccounts.guide("schedulesdirect", a, channels, 1794124800, 1794211200)
        assertEquals(listOf("Open Movie Night", "Sintel"), g.map { it.title })
        assertEquals("Demo Open Movies", g[0].channel)
        assertEquals("The Second Hour", g[0].subtitle)
        assertEquals("A Blender open movie.", g[1].desc)
        assertEquals(5400L, g[1].stop - g[1].start)
        // (the stations of the channels by name and callsign)
        val m = TvAccounts.sdMatch(channels, JSONObject(fixture("sd-lineup.json")).getJSONArray("stations"))
        assertEquals(setOf("11111", "22222"), m.keys)
        assertEquals("KBNY", m["22222"]!![0].name)
        // its token kept: no sign-in again while it lasts
        calls.clear()
        TvAccounts.guide("schedulesdirect", TvAccount(username = "demo", password = "pass", token = "kept", tokenUntil = System.currentTimeMillis() / 1000 + 86400), channels, 1794124800, 1794211200)
        assertTrue(calls.none { it.url.endsWith("/token") })
        assertEquals("kept", calls.first().headers["token"])
    }

    @Test
    fun aGuideGivenAsJsonWrittenAsXmltv() {
        val out = StringWriter()
        TvAccounts.writeXmltv(listOf(TvProg("jf:c1", 1794124800, 1794128400, "News & <Weather>", "Part \"One\"", "Today.", "E2"),
            TvProg("jf:c1", 0, 0, "dropped")), out)
        val x = out.toString()
        assertTrue(x, x.contains("<channel id=\"jf:c1\">"))
        assertTrue(x, x.contains("<programme start=\"20261108080000 +0000\" stop=\"20261108090000 +0000\" channel=\"jf:c1\">"))
        assertTrue(x, x.contains("<title>News &amp; &lt;Weather&gt;</title>"))
        assertTrue(x, x.contains("<sub-title>Part &quot;One&quot;</sub-title>"))
        assertFalse(x.contains("dropped"))
    }

    @Test
    fun timesOfTheJsonApis() {
        assertEquals(1794168000L, TvAccounts.isoSeconds("2026-11-08T20:00:00.0000000Z"))
        assertEquals(1794168000L - 7200, TvAccounts.isoSeconds("2026-11-08T20:00:00+02:00"))
        assertEquals(0L, TvAccounts.isoSeconds("soon"))
        assertEquals("2026-11-08T20:00:00Z", TvAccounts.iso(1794168000L))
    }

    @Test
    fun failuresTold() {
        routes = listOf("/discover.json" to { _ -> TvAnswer(0, "", "ConnectException") })
        assertEquals(TvCheckState.NO_ANSWER, TvAccounts.check("hdhomerun", TvAccount(address = "http://x")).state)
        assertEquals(TvCheckState.BUSY, TvAccounts.stateOf(TvAnswer(429, "")))
        assertEquals(TvCheckState.REFUSED, TvAccounts.stateOf(TvAnswer(403, "")))
        try { TvAccounts.load("jellyfin", TvAccount(address = "http://jf")); fail() } catch (e: TvAccountException) { assertTrue(e.refused) }
        assertNotNull(TvAccounts.withCredentials("http://a/b", "u@x", "p:w").let { assertEquals("http://u%40x:p%3Aw@a/b", it); it })
    }
}
