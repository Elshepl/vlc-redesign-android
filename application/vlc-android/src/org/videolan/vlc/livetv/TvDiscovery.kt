/*
 * VLC Redesign, Live TV: the TV servers of the home network, found and offered in TV Sources › Accounts ("Found on
 * your network: … Add"): HDHomeRun tuners (their UDP discovery on port 65001, and SiliconDust's api.hdhomerun.com/
 * discover for the tuners of this network), Jellyfin and Emby servers (UDP 7359, "who is JellyfinServer?" / "who is
 * EmbyServer?") and Channels DVR servers (their port 8089 on this network's addresses). A second or two, off the main
 * thread; nothing leaves the network but the request to api.hdhomerun.com.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/** A server found: its account type, its name, its address. */
data class TvFound(val type: String, val name: String, val address: String)

object TvDiscovery {

    const val HDHR_PORT = 65001
    const val MB_PORT = 7359

    /* ---- HDHomeRun: type 0x0002, tags device type (tuner) and device id (any), a little-endian CRC32 ---- */

    fun hdhrRequest(): ByteArray {
        val payload = byteArrayOf(
            0x01, 0x04, 0x00, 0x00, 0x00, 0x01,                          // device type: a tuner
            0x02, 0x04, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())  // device id: any
        val frame = ByteArray(4 + payload.size)
        frame[0] = 0x00; frame[1] = 0x02
        frame[2] = (payload.size shr 8).toByte(); frame[3] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, 4, payload.size)
        val crc = CRC32().apply { update(frame) }.value
        return frame + byteArrayOf(crc.toByte(), (crc shr 8).toByte(), (crc shr 16).toByte(), (crc shr 24).toByte())
    }

    /** A discovery reply (type 0x0003): its base URL (tag 0x2A), else its address from the sender; its id (0x02). */
    fun hdhrReply(data: ByteArray, length: Int, sender: String): TvFound? {
        if (length < 8 || data[0].toInt() != 0x00 || data[1].toInt() != 0x03) return null
        val payloadLength = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        if (4 + payloadLength + 4 > length) return null
        val crc = CRC32().apply { update(data, 0, 4 + payloadLength) }.value
        val got = (data[4 + payloadLength].toLong() and 0xff) or ((data[5 + payloadLength].toLong() and 0xff) shl 8) or
            ((data[6 + payloadLength].toLong() and 0xff) shl 16) or ((data[7 + payloadLength].toLong() and 0xff) shl 24)
        if (crc != got) return null
        var i = 4
        val end = 4 + payloadLength
        var base = ""
        var id = ""
        while (i + 2 <= end) {
            val tag = data[i].toInt() and 0xff
            var len = data[i + 1].toInt() and 0xff
            i += 2
            if (len and 0x80 != 0) {
                if (i >= end) break
                len = (len and 0x7f) or ((data[i].toInt() and 0xff) shl 7)
                i++
            }
            if (i + len > end) break
            when (tag) {
                0x2A -> base = String(data, i, len, Charsets.UTF_8).trimEnd('\u0000')
                0x02 -> if (len == 4) id = "%02X%02X%02X%02X".format(data[i], data[i + 1], data[i + 2], data[i + 3])
            }
            i += len
        }
        val address = base.ifEmpty { if (sender.isNotEmpty()) "http://$sender" else "" }.trimEnd('/')
        if (address.isEmpty()) return null
        return TvFound("hdhomerun", if (id.isNotEmpty()) "HDHomeRun $id" else "HDHomeRun", address)
    }

    /** api.hdhomerun.com/discover: [{DeviceID, LocalIP, BaseURL, FriendlyName}] (StorageID: a DVR, not a tuner) */
    fun hdhrApi(body: String): List<TvFound> {
        val a = try { JSONArray(body) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<TvFound>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("DeviceID").isEmpty()) continue
            val base = o.optString("BaseURL").ifEmpty { o.optString("LocalIP").takeIf { it.isNotEmpty() }?.let { "http://$it" } ?: "" }.trimEnd('/')
            if (base.isEmpty()) continue
            out.add(TvFound("hdhomerun", o.optString("FriendlyName").ifEmpty { "HDHomeRun" } + " " + o.optString("DeviceID"), base))
        }
        return out
    }

    fun mbRequest(type: String) = (if (type == "emby") "who is EmbyServer?" else "who is JellyfinServer?").toByteArray(Charsets.UTF_8)

    /** {"Address": "http://192.168.1.5:8096", "Id": "...", "Name": "..."} (the names in any case) */
    fun mbReply(text: String, type: String): TvFound? {
        val o = try { JSONObject(text) } catch (e: Exception) { return null }
        fun field(name: String) = o.keys().asSequence().firstOrNull { it.equals(name, true) }?.let { o.optString(it) } ?: ""
        val address = field("Address").trimEnd('/')
        if (!address.startsWith("http")) return null
        return TvFound(type, field("Name").ifEmpty { if (type == "emby") "Emby" else "Jellyfin" }, address)
    }

    /** Channels DVR's page answers on its port: its name in it */
    fun isChannelsDvr(body: String) = body.contains("Channels DVR", true) || body.contains("channels-dvr", true)

    /* ---- the search ---- */

    /** The IPv4 broadcast addresses of this device's networks (255.255.255.255 too) and its own /24 hosts. */
    private fun networks(): Pair<List<InetAddress>, List<String>> {
        val broadcasts = ArrayList<InetAddress>()
        val prefixes = ArrayList<String>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address as? Inet4Address ?: continue
                    ia.broadcast?.let { broadcasts.add(it) }
                    if (a.isSiteLocalAddress) prefixes.add(a.hostAddress!!.substringBeforeLast('.'))
                }
            }
        } catch (e: Exception) { }
        broadcasts.add(InetAddress.getByName("255.255.255.255"))
        return broadcasts.distinct() to prefixes.distinct()
    }

    private fun udp(request: ByteArray, targets: List<Pair<Int, List<InetAddress>>>, waitMs: Int, reply: (ByteArray, Int, String) -> Unit) {
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 300
                for ((port, list) in targets) for (t in list) try { s.send(DatagramPacket(request, request.size, t, port)) } catch (e: Exception) { }
                val until = System.currentTimeMillis() + waitMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < until) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: SocketTimeoutException) { continue }
                    reply(p.data, p.length, p.address?.hostAddress ?: "")
                }
            }
        } catch (e: Exception) { }
    }

    /** The ports the extra hosts are asked on (HDHomeRun's UDP, Jellyfin's and Emby's UDP, Channels DVR's): a test
     *  server on a computer where the usual ones are taken. */
    class Ports(val hdhr: Int = HDHR_PORT, val mb: Int = MB_PORT, val channelsDvr: Int = 8089) {
        companion object {
            /** "16501,17359,18089" (debug builds' setting), else the usual ones */
            fun of(text: String): Ports {
                val p = text.split(',').map { it.trim().toIntOrNull() ?: 0 }
                fun at(i: Int, def: Int) = p.getOrNull(i)?.takeIf { it in 1..65535 } ?: def
                return Ports(at(0, HDHR_PORT), at(1, MB_PORT), at(2, 8089))
            }
        }
    }

    /**
     * The servers of this network (a few seconds). extraHosts: addresses asked as well, on extraPorts (debug builds:
     * the emulator's host, where a test server answers).
     */
    fun find(extraHosts: List<String> = emptyList(), waitMs: Int = 1800, extraPorts: Ports = Ports()): List<TvFound> {
        val found = Collections.synchronizedList(ArrayList<TvFound>())
        val (broadcasts, prefixes) = networks()
        val extra = extraHosts.mapNotNull { try { InetAddress.getByName(it) } catch (e: Exception) { null } }
        val pool = Executors.newFixedThreadPool(24)
        pool.execute {
            udp(hdhrRequest(), listOf(HDHR_PORT to broadcasts, extraPorts.hdhr to extra), waitMs) { d, n, from -> hdhrReply(d, n, from)?.let { found.add(it) } }
        }
        for (type in listOf("jellyfin", "emby")) pool.execute {
            udp(mbRequest(type), listOf(MB_PORT to broadcasts, extraPorts.mb to extra), waitMs) { d, n, _ -> mbReply(String(d, 0, n, Charsets.UTF_8), type)?.let { found.add(it) } }
        }
        pool.execute {
            val r = TvHttp.request(TvRequest("https://api.hdhomerun.com/discover", timeoutMs = 4000))
            if (r.ok) found.addAll(hdhrApi(r.body))
        }
        // Channels DVR: its port on this network's addresses (and the extra ones)
        val hosts = LinkedHashMap<String, Int>()
        for (p in prefixes.take(2)) for (i in 1..254) hosts["$p.$i"] = 8089
        for (h in extraHosts) hosts[h] = extraPorts.channelsDvr
        for ((h, port) in hosts) pool.execute {
            try {
                Socket().use { it.connect(InetSocketAddress(h, port), 250) }
                val r = TvHttp.request(TvRequest("http://$h:$port/", timeoutMs = 1500, limit = 256 shl 10))
                if (r.ok && isChannelsDvr(r.body)) found.add(TvFound("channelsdvr", "Channels DVR", "http://$h:$port"))
            } catch (e: Exception) { }
        }
        pool.shutdown()
        pool.awaitTermination(waitMs + 4000L, TimeUnit.MILLISECONDS)
        pool.shutdownNow()
        // one of each address (a tuner found by both ways, a server answering on two networks)
        return synchronized(found) { found.distinctBy { it.type + " " + it.address.lowercase() } }
    }
}
