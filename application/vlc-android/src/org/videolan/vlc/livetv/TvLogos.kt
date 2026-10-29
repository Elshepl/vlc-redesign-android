/*
 * VLC Redesign, Live TV: the logos of the channels (TvLogo.qml), downloaded once to the add-on's cache and kept
 * small in memory; until one comes (or never), the initials of the channel.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.livetv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

object TvLogos {
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "LiveTv-logos").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** "Channel 12 HD" -> "C1", "BBC One" -> "BO". */
    fun initials(name: String): String {
        val words = name.split(' ', '-', '_', '.', ':').filter { it.isNotEmpty() && it.lowercase() !in setOf("hd", "fhd", "uhd", "4k", "sd") }
        return when {
            words.isEmpty() -> name.take(2).uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    fun cached(url: String): Bitmap? = if (url.isEmpty()) null else memory.get(url)

    /** The logo of url for the image view (its tag the url: a recycled row gets only its own). */
    fun load(context: Context, url: String, view: ImageView, size: Int, done: (Boolean) -> Unit) {
        view.tag = url
        if (url.isEmpty() || failed.contains(url)) { done(false); return }
        memory.get(url)?.let { view.setImageBitmap(it); done(true); return }
        view.setImageDrawable(null)
        done(false)
        get(context, url, size) { bmp ->
            if (view.tag == url && bmp != null) {
                view.setImageBitmap(bmp)
                done(true)
            }
        }
    }

    fun get(context: Context, url: String, size: Int, done: (Bitmap?) -> Unit) {
        memory.get(url)?.let { done(it); return }
        synchronized(pending) {
            pending[url]?.let { it.add(done); return }
            pending[url] = mutableListOf(done)
        }
        val app = context.applicationContext
        pool.execute {
            val bmp = try { fetch(app, url, size) } catch (e: Throwable) { null }
            if (bmp != null) memory.put(url, bmp) else synchronized(failed) { failed.add(url) }
            val callbacks = synchronized(pending) { pending.remove(url) } ?: emptyList()
            main.post { callbacks.forEach { it(bmp) } }
        }
    }

    private fun fetch(context: Context, url: String, size: Int): Bitmap? {
        if (!LiveTv.isInitialized) return null
        val dir = LiveTv.logoDir.apply { mkdirs() }
        val file = File(dir, TvNet.hashOf(url))
        if (!file.exists()) {
            if (url.startsWith("data:")) return null
            TvNet.download(context, url, file, null)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) { file.delete(); return null }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun trim() = memory.evictAll()
}
