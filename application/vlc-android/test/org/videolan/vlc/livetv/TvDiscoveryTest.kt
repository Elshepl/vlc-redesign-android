package org.videolan.vlc.livetv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/**
 * VLC Redesign, Live TV: the servers of the home network (TvDiscovery): HDHomeRun's discovery packets (UDP 65001,
 * libhdhomerun's format), SiliconDust's api.hdhomerun.com/discover, Jellyfin's and Emby's answers on UDP 7359 and
 * Channels DVR's page. The packets and answers only (the search itself is tried on the emulator against test servers).
 */
class TvDiscoveryTest {

    private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /** a reply as a tuner sends it: type 3, its tags, a little-endian CRC32 */
    private fun reply(vararg tags: Pair<Int, ByteArray>): ByteArray {
        var payload = ByteArray(0)
        for ((tag, value) in tags) {
            val len = if (value.size < 0x80) byteArrayOf(value.size.toByte()) else byteArrayOf(((value.size and 0x7f) or 0x80).toByte(), (value.size shr 7).toByte())
            payload += byteArrayOf(tag.toByte()) + len + value
        }
        val frame = byteArrayOf(0x00, 0x03, (payload.size shr 8).toByte(), payload.size.toByte()) + payload
        val crc = CRC32().apply { update(frame) }.value
        return frame + byteArrayOf(crc.toByte(), (crc shr 8).toByte(), (crc shr 16).toByte(), (crc shr 24).toByte())
    }

    @Test
    fun hdhomerunsRequest() {
        // libhdhomerun's hdhomerun_discover: a tuner (type 1), any device id (FFFFFFFF), the CRC 0x357F504E
        assertArrayEquals(hex("00 02 00 0c 01 04 00 00 00 01 02 04 ff ff ff ff 4e 50 7f 35"), TvDiscovery.hdhrRequest())
    }

    @Test
    fun hdhomerunsReply() {
        val r = reply(0x01 to hex("00 00 00 01"), 0x02 to hex("12 34 AB CD"), 0x10 to hex("04"),
            0x2A to "http://192.168.1.30".toByteArray(), 0x27 to "http://192.168.1.30/lineup.json".toByteArray())
        assertEquals(TvFound("hdhomerun", "HDHomeRun 1234ABCD", "http://192.168.1.30"), TvDiscovery.hdhrReply(r, r.size, "192.168.1.30"))
        // (in a buffer larger than the packet)
        val big = r + ByteArray(100)
        assertEquals("http://192.168.1.30", TvDiscovery.hdhrReply(big, r.size, "")!!.address)
        // no base URL (an old tuner): its address
        val old = reply(0x02 to hex("10 20 30 40"))
        assertEquals(TvFound("hdhomerun", "HDHomeRun 10203040", "http://192.168.1.31"), TvDiscovery.hdhrReply(old, old.size, "192.168.1.31"))
        // a long value (its length in two bytes)
        val longUrl = "http://tuner.example/" + "x".repeat(150)
        val l = reply(0x2A to longUrl.toByteArray())
        assertEquals(longUrl, TvDiscovery.hdhrReply(l, l.size, "")!!.address)
        // a damaged packet, a request, too short: nothing
        val bad = r.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull(TvDiscovery.hdhrReply(bad, bad.size, "192.168.1.30"))
        val req = TvDiscovery.hdhrRequest()
        assertNull(TvDiscovery.hdhrReply(req, req.size, "192.168.1.30"))
        assertNull(TvDiscovery.hdhrReply(r, 6, "192.168.1.30"))
        assertNull(TvDiscovery.hdhrReply(old, old.size, ""))
    }

    @Test
    fun hdhomerunsApi() {
        val body = """[{"DeviceID":"1234ABCD","LocalIP":"192.168.1.30","BaseURL":"http://192.168.1.30","DiscoverURL":"http://192.168.1.30/discover.json","LineupURL":"http://192.168.1.30/lineup.json"},
            {"StorageID":"5678-DVR","LocalIP":"192.168.1.40:59090","StorageURL":"http://192.168.1.40:59090/recorded_files.json"},
            {"DeviceID":"0000BEEF","LocalIP":"192.168.1.32","FriendlyName":"HDHomeRun FLEX 4K"}]"""
        assertEquals(listOf(TvFound("hdhomerun", "HDHomeRun 1234ABCD", "http://192.168.1.30"), TvFound("hdhomerun", "HDHomeRun FLEX 4K 0000BEEF", "http://192.168.1.32")),
            TvDiscovery.hdhrApi(body))
        assertEquals(emptyList<TvFound>(), TvDiscovery.hdhrApi("[]"))
        assertEquals(emptyList<TvFound>(), TvDiscovery.hdhrApi("<html>"))
    }

    @Test
    fun jellyfinAndEmby() {
        assertEquals("who is JellyfinServer?", String(TvDiscovery.mbRequest("jellyfin")))
        assertEquals("who is EmbyServer?", String(TvDiscovery.mbRequest("emby")))
        assertEquals(TvFound("jellyfin", "Living Room", "http://192.168.1.5:8096"),
            TvDiscovery.mbReply("""{"Address":"http://192.168.1.5:8096/","Id":"a1b2","Name":"Living Room","EndpointAddress":null}""", "jellyfin"))
        // (Emby's names in another case, no name)
        assertEquals(TvFound("emby", "Emby", "http://192.168.1.6:8096"), TvDiscovery.mbReply("""{"address":"http://192.168.1.6:8096","id":"e1"}""", "emby"))
        assertNull(TvDiscovery.mbReply("""{"Address":"192.168.1.5"}""", "jellyfin"))
        assertNull(TvDiscovery.mbReply("who is JellyfinServer?", "jellyfin"))
    }

    @Test
    fun thePortsOfATestServer() {
        val p = TvDiscovery.Ports.of("16501, 17359,18089")
        assertEquals(listOf(16501, 17359, 18089), listOf(p.hdhr, p.mb, p.channelsDvr))
        val usual = TvDiscovery.Ports.of("")
        assertEquals(listOf(65001, 7359, 8089), listOf(usual.hdhr, usual.mb, usual.channelsDvr))
        val some = TvDiscovery.Ports.of("x,99999")
        assertEquals(listOf(65001, 7359, 8089), listOf(some.hdhr, some.mb, some.channelsDvr))
    }

    @Test
    fun channelsDvrsPage() {
        assertTrue(TvDiscovery.isChannelsDvr("<html><head><title>Channels DVR</title></head></html>"))
        assertTrue(TvDiscovery.isChannelsDvr("<script src=\"/admin/channels-dvr.js\"></script>"))
        assertFalse(TvDiscovery.isChannelsDvr("<html><title>Router</title></html>"))
    }
}
