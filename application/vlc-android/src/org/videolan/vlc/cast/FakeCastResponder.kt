/*
 * VLC Redesign, casting tests (debug builds only): makes test Chromecasts that run on the computer
 * (the fake devices of the desktop's cast tests) visible to the phone's discovery, where the network
 * does not carry mDNS (an emulator). Each line of <external files>/fakecast.txt is a device,
 * "name|model|address|port"; while the discovery runs, it announces them on the multicast group
 * of mDNS every second, the way a Chromecast answers.
 *
 * Nothing runs without the file, and release builds never start it (BuildConfig.DEBUG).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.cast

import android.content.Context
import android.util.Log
import org.videolan.vlc.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

object FakeCastResponder {
    private const val TAG = "VLC/FakeCast"

    private data class Device(val name: String, val model: String, val ip: String, val port: Int, val id: String)

    @Volatile private var running = false
    private var worker: Thread? = null

    @Synchronized
    fun start(context: Context) {
        if (!BuildConfig.DEBUG || running) return
        val dir = context.getExternalFilesDir(null) ?: return
        running = true
        // (one announcer: one stopped and started again at once ends when it sees it is not the current one)
        val t = thread(start = false, name = "FakeCastResponder", isDaemon = true) { run(File(dir, "fakecast.txt")) }
        worker = t
        t.start()
    }

    @Synchronized
    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun devices(file: File): List<Device> = try {
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
            val p = line.trim().split('|')
            if (p.size < 4 || line.startsWith("#")) null
            else Device(p[0], p[1], p[2], p[3].toIntOrNull() ?: 8009, "%032x".format(line.trim().hashCode().toLong() and 0xffffffffL))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun run(file: File) {
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket().apply { timeToLive = 1 }
            val group = InetAddress.getByName("224.0.0.251")
            while (running && worker === Thread.currentThread()) {
                // read each time: a line taken out is a device switched off
                val list = devices(file)
                for (d in list) {
                    val packet = announcement(d)
                    for (nif in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                        if (!nif.isUp || !nif.supportsMulticast()) continue
                        try {
                            socket.networkInterface = nif
                            socket.send(DatagramPacket(packet, packet.size, group, 5353))
                        } catch (_: Exception) {
                        }
                    }
                }
                Thread.sleep(1000L)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "stopped: $e")
        } finally {
            socket?.close()
        }
    }

    private fun name(out: ByteArrayOutputStream, name: String) {
        for (label in name.split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
    }

    private fun u16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xff)
        out.write(v and 0xff)
    }

    private fun u32(out: ByteArrayOutputStream, v: Long) {
        u16(out, ((v shr 16) and 0xffff).toInt())
        u16(out, (v and 0xffff).toInt())
    }

    private fun record(out: ByteArrayOutputStream, owner: String, type: Int, cls: Int, ttl: Long, data: ByteArray) {
        name(out, owner)
        u16(out, type)
        u16(out, cls)
        u32(out, ttl)
        u16(out, data.size)
        out.write(data)
    }

    /** an answer of a Chromecast to "_googlecast._tcp.local": PTR, then TXT, SRV and A */
    private fun announcement(d: Device): ByteArray {
        val service = "_googlecast._tcp.local"
        val instance = "${d.model.replace(' ', '-')}-${d.id}.$service"
        val host = "${d.id}.local"
        val out = ByteArrayOutputStream()
        u16(out, 0); u16(out, 0x8400); u16(out, 0); u16(out, 1); u16(out, 0); u16(out, 3)
        record(out, service, 12, 0x0001, 120, ByteArrayOutputStream().also { name(it, instance) }.toByteArray())
        val txt = ByteArrayOutputStream()
        val ca = if (d.model.contains("Audio", true) || d.model.contains("Group", true)) 4100 else 4101
        for (s in listOf("id=${d.id}", "cd=${d.id.uppercase()}", "rm=", "ve=05", "md=${d.model}", "ic=/setup/icon.png",
                "fn=${d.name}", "ca=$ca", "st=0", "bs=FA8F${d.id.take(8).uppercase()}", "nf=1", "rs=")) {
            val b = s.toByteArray(Charsets.UTF_8)
            txt.write(b.size)
            txt.write(b)
        }
        record(out, instance, 16, 0x8001, 4500, txt.toByteArray())
        val srv = ByteArrayOutputStream()
        u16(srv, 0); u16(srv, 0); u16(srv, d.port)
        name(srv, host)
        record(out, instance, 33, 0x8001, 120, srv.toByteArray())
        record(out, host, 1, 0x8001, 120, InetAddress.getByName(d.ip).address)
        return out.toByteArray()
    }
}
