/*
 * VLC Redesign, casting: the volume of the device (a Chromecast) while it casts, as the desktop's volume does.
 *
 * libvlc 3's Chromecast output has no volume: the phone talks to the device's receiver itself (Cast v2: a TLS
 * connection on its cast port, a "receiver-0" virtual connection, GET_STATUS / SET_VOLUME on the receiver
 * namespace), as any other sender of the same network does. Its address is the one of the device's icon
 * (http://<address>:8008/setup/icon.png from the discovery).
 *
 * The media session gets [provider] while it casts: the volume keys and the system's volume panel change the
 * device's volume from any screen.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.cast

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media.VolumeProviderCompat
import org.json.JSONObject
import org.videolan.libvlc.RendererItem
import org.videolan.vlc.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

object CastVolume {
    private const val TAG = "VLC/CastVolume"
    private const val STEPS = 20
    private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val SENDER = "sender-vlc-volume"
    private const val RECEIVER = "receiver-0"

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "CastVolume").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** the media session's remote volume while it casts (null: the device's address is not known) */
    var provider: VolumeProviderCompat? = null
        private set

    @Volatile private var host: String? = null
    @Volatile private var port = 8009
    /** the device's volume, 0..1 (-1: not known yet) */
    @Volatile var level = -1f
        private set
    private var socket: SSLSocket? = null
    private var requestId = 1
    /** when the connection was last used: a device closes a sender that does not answer its pings */
    private var lastUse = 0L

    fun rendererChanged(item: RendererItem?) {
        val address = item?.let { addressOf(it) }
        executor.execute { close() }
        host = address
        level = -1f
        provider = if (address == null) null else object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, STEPS, STEPS / 3) {
            override fun onSetVolumeTo(volume: Int) = set(volume.toFloat() / STEPS)
            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) step(direction)
            }
        }
        if (address != null) executor.execute { request(null) }
    }

    /** the volume keys: one step up (+1) or down (-1); [shown] gets the new volume in percent */
    fun step(direction: Int, shown: ((Int) -> Unit)? = null) {
        executor.execute {
            if (level < 0f) request(null)
            val now = if (level < 0f) 0.3f else level
            val target = ((now * STEPS).roundToInt() + direction).coerceIn(0, STEPS).toFloat() / STEPS
            request(target)
            val pct = (level.coerceAtLeast(0f) * 100).roundToInt()
            if (shown != null) main.post { shown(pct) }
        }
    }

    fun set(value: Float) = executor.execute { request(value.coerceIn(0f, 1f)) }

    private fun addressOf(item: RendererItem): String? = try {
        val f = RendererItem::class.java.getDeclaredField("iconUrl")
        f.isAccessible = true
        (f.get(item) as? String)?.let { Uri.parse(it).host }
    } catch (e: Exception) {
        null
    }

    /** reads the volume (and sets it to [value] first when given): on the executor */
    private fun request(value: Float?) {
        if (socket != null && System.currentTimeMillis() - lastUse > 4000L) close()
        for (attempt in 0..1) {
            try {
                val s = socket ?: open() ?: return
                lastUse = System.currentTimeMillis()
                val out = DataOutputStream(s.outputStream)
                val id = requestId++
                val payload = if (value == null) """{"type":"GET_STATUS","requestId":$id}"""
                else """{"type":"SET_VOLUME","volume":{"level":$value},"requestId":$id}"""
                send(out, NS_RECEIVER, payload)
                read(DataInputStream(s.inputStream), out, id)
                return
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.d(TAG, "volume: $e")
                close()
            }
        }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun open(): SSLSocket? {
        val address = host ?: return null
        // a Chromecast's certificate is its own (self-signed): it is the device chosen in the list
        val trust = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val context = SSLContext.getInstance("TLS").apply { init(null, trust, null) }
        val raw = java.net.Socket()
        raw.connect(InetSocketAddress(address, port), 4000)
        raw.soTimeout = 4000
        val s = context.socketFactory.createSocket(raw, address, port, true) as SSLSocket
        s.startHandshake()
        send(DataOutputStream(s.outputStream), NS_CONNECTION, """{"type":"CONNECT"}""")
        socket = s
        return s
    }

    private fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun field(out: ByteArrayOutputStream, number: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write((number shl 3) or 2)
        var n = bytes.size
        while (n >= 0x80) {
            out.write((n and 0x7f) or 0x80)
            n = n ushr 7
        }
        out.write(n)
        out.write(bytes)
    }

    /** a CastMessage (protobuf): version 0, source, destination, namespace, string payload */
    private fun send(out: DataOutputStream, namespace: String, payload: String) {
        val m = ByteArrayOutputStream()
        m.write(0x08); m.write(0)
        field(m, 2, SENDER)
        field(m, 3, RECEIVER)
        field(m, 4, namespace)
        m.write(0x28); m.write(0)
        field(m, 6, payload)
        out.writeInt(m.size())
        out.write(m.toByteArray())
        out.flush()
    }

    /** reads until the answer to [id] (answering the device's pings), keeps the volume it gives */
    private fun read(input: DataInputStream, out: DataOutputStream, id: Int) {
        val until = System.currentTimeMillis() + 4000L
        while (System.currentTimeMillis() < until) {
            val size = input.readInt()
            if (size <= 0 || size > 1 shl 20) throw IllegalStateException("message size $size")
            val bytes = ByteArray(size)
            input.readFully(bytes)
            val (namespace, payload) = decode(bytes)
            if (namespace == NS_HEARTBEAT && payload.contains("\"PING\"")) {
                send(out, NS_HEARTBEAT, """{"type":"PONG"}""")
                continue
            }
            if (namespace != NS_RECEIVER) continue
            val json = JSONObject(payload)
            json.optJSONObject("status")?.optJSONObject("volume")?.let { v ->
                if (v.has("level")) level = v.getDouble("level").toFloat()
                main.post { provider?.currentVolume = (level * STEPS).roundToInt() }
            }
            if (json.optInt("requestId", -1) == id) return
        }
    }

    private fun decode(bytes: ByteArray): Pair<String, String> {
        var i = 0
        var namespace = ""
        var payload = ""
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val b = bytes[i++].toInt() and 0xff
                r = r or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return r
                shift += 7
            }
        }
        while (i < bytes.size) {
            val key = varint().toInt()
            when (key and 7) {
                0 -> varint()
                2 -> {
                    val n = varint().toInt()
                    val text = String(bytes, i, n, Charsets.UTF_8)
                    i += n
                    when (key ushr 3) {
                        4 -> namespace = text
                        6 -> payload = text
                    }
                }
                else -> return namespace to payload
            }
        }
        return namespace to payload
    }
}
