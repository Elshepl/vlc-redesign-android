/*
 * VLC Redesign: the waveform seek bar of the music view (WaveformSlider.qml of the desktop).
 *
 * Waveform.peaks() decodes the audio of a local file with the platform's codecs and keeps the loudest sample of
 * each of 512 equal parts of its length (the desktop's 512 bins); WaveformDrawable draws them in the seek well:
 * 2 dp bars every 5 dp, at most 14 dp high, inset 6 dp, the played ones in the accent (80 %), the others #404040
 * (#929291 in the light theme), and the 8 x 24 handle of the seek bar on top.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.audio

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.util.LruCache
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Waveform {
    private const val TAG = "VLC/Waveform"
    const val BINS = 512
    /** longer files are not analysed (the desktop's analysis is bounded too): minutes of decoding */
    private const val MAX_DURATION_US = 30L * 60 * 1_000_000
    private val cache = LruCache<String, FloatArray>(16)

    fun cached(uri: Uri): FloatArray? = cache.get(uri.toString())

    /** The 512 peaks (0..1, not yet scaled) of a local audio file, null when it cannot be read; blocking */
    fun peaks(context: Context, uri: Uri): FloatArray? {
        cache.get(uri.toString())?.let { return it }
        if (uri.scheme != "file" && uri.scheme != "content") return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file") extractor.setDataSource(uri.path!!) else extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: return null
            val format = extractor.getTrackFormat(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else return null
            if (duration <= 0 || duration > MAX_DURATION_US) return null
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val bins = FloatArray(BINS)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var rate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var frame = 0L
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let { channels = it.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels); rate = it.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, rate) }
                } else if (o >= 0) {
                    val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val shorts = out.asShortBuffer()
                    val total = duration * rate / 1_000_000.0
                    var s = 0
                    // the loudest sample of every frame, into the bin of its time (16-bit PCM, the decoders' output)
                    while (s + channels <= shorts.remaining()) {
                        var peak = 0
                        for (c in 0 until channels) peak = max(peak, abs(shorts.get(s + c).toInt()))
                        val bin = min(BINS - 1, (frame * BINS / total).toInt())
                        val v = peak / 32768f
                        if (v > bins[bin]) bins[bin] = v
                        s += channels
                        frame++
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                if (Thread.currentThread().isInterrupted) return null
            }
            cache.put(uri.toString(), bins)
            return bins
        } catch (e: Exception) {
            Log.w(TAG, "no waveform for $uri: $e")
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
        }
    }

    /**
     * The loudness (RMS, 0..1) of every [binMs] of a local file's sound from [fromMs] to [toMs], for the subtitle
     * sync timeline; null when it cannot be read. Only that stretch is decoded (from the key frame before it).
     * Blocking; [progress] gets what is read so far, every 5 s of sound.
     */
    fun levels(context: Context, uri: Uri, fromMs: Long, toMs: Long, binMs: Int, progress: ((FloatArray) -> Unit)? = null): FloatArray? {
        if (uri.scheme != "file" && uri.scheme != "content") return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme == "file") extractor.setDataSource(uri.path!!) else extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(maxOf(0L, fromMs) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val count = ((toMs - fromMs) / binMs).toInt().coerceAtLeast(1)
            val sums = DoubleArray(count)
            val samples = IntArray(count)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var rate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var reported = fromMs
            fun snapshot() = FloatArray(count) { if (samples[it] > 0) kotlin.math.sqrt(sums[it] / samples[it]).toFloat() else 0f }
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || extractor.sampleTime > toMs * 1000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let { channels = it.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels); rate = it.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, rate) }
                } else if (o >= 0) {
                    val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val shorts = out.asShortBuffer()
                    var s = 0
                    var frame = 0L
                    // the mean square of every bin (16-bit PCM, the decoders' output), from the buffer's own time;
                    // about 4000 frames a second are enough for a loudness
                    val stride = max(1, rate / 4000)
                    while (s + channels <= shorts.remaining()) {
                        val t = info.presentationTimeUs / 1000 + frame * 1000 / rate
                        val bin = ((t - fromMs) / binMs).toInt()
                        if (bin in 0 until count) {
                            var peak = 0
                            for (c in 0 until channels) peak = max(peak, abs(shorts.get(s + c).toInt()))
                            val v = peak / 32768.0
                            sums[bin] += v * v
                            samples[bin]++
                        }
                        s += channels * stride
                        frame += stride
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (progress != null && info.presentationTimeUs / 1000 - reported > 5000) {
                        reported = info.presentationTimeUs / 1000
                        progress(snapshot())
                    }
                }
                if (Thread.currentThread().isInterrupted) return null
            }
            if (org.videolan.vlc.BuildConfig.DEBUG) Log.d(TAG, "levels $fromMs..$toMs: ${samples.count { it > 0 }}/$count bins")
            return snapshot()
        } catch (e: Exception) {
            Log.w(TAG, "no levels for $uri: $e")
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, def: Int) = if (containsKey(key)) getInteger(key) else def
}

/** The seek well with the waveform; the played part follows the drawable's level (0..10000), as a progress layer */
class WaveformDrawable(context: Context, private val bins: FloatArray, private val accent: Int, private val well: Int, private val unplayed: Int) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val inset = 6 * density
    private val pitch = 5 * density
    private val barWidth = 2 * density
    private val maxHeight = 14 * density
    private val radius = 4 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onLevelChange(level: Int) = true.also { invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.color = well
        rect.set(b)
        canvas.drawRoundRect(rect, radius, radius, paint)
        val width = b.width().toFloat()
        val columns = max(1, min(Waveform.BINS, floor((width - 2 * inset + pitch - barWidth) / pitch).toInt()))
        // the peaks of the columns, scaled to the loudest (at least 0.05, as the desktop)
        val peaks = FloatArray(columns)
        var largest = 0f
        for (v in bins) largest = max(largest, v)
        val scale = max(largest, 0.05f)
        for (c in 0 until columns) {
            val first = c * Waveform.BINS / columns
            val last = max(first + 1, (c + 1) * Waveform.BINS / columns)
            var p = 0f
            for (i in first until last) p = max(p, bins[i])
            peaks[c] = p / scale
        }
        val played = level / 10000f
        val playedCount = max(0, min(columns, floor((played * width - inset - barWidth / 2) / pitch).toInt() + 1))
        val cy = b.exactCenterY()
        for (c in 0 until columns) {
            val h = max(barWidth, 2 * (peaks[c] * maxHeight / 2).roundToInt().toFloat())
            val x = b.left + inset + c * pitch
            paint.color = if (c < playedCount) accent else unplayed
            paint.alpha = if (c < playedCount) (255 * 0.8f).toInt() else 255
            rect.set(x, cy - h / 2, x + barWidth, cy + h / 2)
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
        }
        paint.alpha = 255
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
