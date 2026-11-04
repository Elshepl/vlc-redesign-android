/*
 * VLC Redesign: the designer's cat (Juxtopposed's Figma "Player" 247:445, the desktop's IdleScene.qml, the TV
 * app's idle.js): the dark room, its floor and the white pixel cat sitting in the middle of it. Drawn in blocks of
 * whole pixels, not from a picture: sharp on every screen, nothing to load. It covers the view as the picture
 * covers the design's frame (1920 x 1080 scaled to cover, the cat at its centre, its feet on the floor).
 *
 * The cat lives as on the TV: its eyes blink every 6 s, its tail is up a block for half of 2.4 s, its right ear
 * up for a moment every 9 s. The view draws again only when one of them changes, and only while it is on
 * screen (no drawing, no timer once it is hidden, detached or its window is away).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class IdleSceneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** The room darker toward its top: the idle screen under a page, its titles and lists stay easy to read. */
    var shaded = false
        set(v) { if (field != v) { field = v; shade = null; invalidate() } }

    /** Where the cat sits across the view (px), when a sidebar covers a side of the room; -1: in the middle. */
    var catCentre = -1
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The room a few pixels away from its place, in a small circle (the idle screen: nothing burns in). */
    var drift = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    private val paint = Paint().apply { isAntiAlias = false }
    private var floorShader: Shader? = null
    private var shade: Shader? = null
    private var shaderKey = 0L
    private val start = SystemClock.uptimeMillis()
    private val tick = Runnable { invalidate() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) removeCallbacks(tick)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) removeCallbacks(tick)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        // the design's pixels to the view's, covering it (IdleScene.qml)
        val scale = max(w / 1920f, h / 1080f)
        val block = max(1, (24.0625f * scale).roundToInt())
        val sceneX = (w - 1920 * scale) / 2
        val sceneY = (h - 1080 * scale) / 2
        val a = drift * 1.1
        val dx = if (drift == 0) 0 else (sin(a) * 10 * scale).roundToInt()
        val dy = if (drift == 0) 0 else (sin(a * 0.7) * 6 * scale).roundToInt()
        val floorY = (sceneY + 785 * scale).roundToInt() + dy
        val bottom = (sceneY + 1080 * scale).roundToInt() + dy
        // the wall
        canvas.drawColor(WALL)
        // the floor: darker at its edge, as light as the wall at the frame's bottom
        val key = (floorY.toLong() shl 32) or bottom.toLong()
        if (floorShader == null || key != shaderKey) {
            shaderKey = key
            floorShader = LinearGradient(0f, floorY.toFloat(), 0f, max(floorY + 1, bottom).toFloat(), FLOOR, WALL, Shader.TileMode.CLAMP)
        }
        paint.shader = floorShader
        canvas.drawRect(0f, floorY.toFloat(), w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        // the cat, its feet on the floor, in the middle of the scene; how it moves now
        val t = SystemClock.uptimeMillis() - start
        val blink = t % BLINK
        val eyesClosed = blink in BLINK_FROM until BLINK_TO
        val wag = t % TAIL
        val tailUp = wag >= TAIL_UP
        val twitch = t % EAR
        val earUp = twitch in EAR_FROM until EAR_TO
        val size = 17 * block
        val centre = if (catCentre >= 0) catCentre.toFloat() else sceneX + (755.9375f + 17 * 24.0625f / 2) * scale
        val catX = (centre - size / 2f).roundToInt() + dx
        val catY = floorY - size
        for (i in RUNS.indices step 4) {
            val col = RUNS[i]
            val row = RUNS[i + 1]
            var color = RUNS[i + 3]
            var lift = 0
            when {
                // the two pupils (row 4, columns 3 and 8): the fur's colour for a blink
                row == 4 && RUNS[i + 2] == 1 && color == BLACK && (col == 3 || col == 8) -> if (eyesClosed) color = FUR
                // the tail (from row 9, column 14) and the right ear (rows 0 and 1, from column 11): a block up
                row >= 9 && col >= 14 -> if (tailUp) lift = block
                row <= 1 && col >= 11 -> if (earUp) lift = block
            }
            paint.color = color
            val x = catX + col * block
            val y = catY + row * block - lift
            canvas.drawRect(x.toFloat(), y.toFloat(), (x + RUNS[i + 2] * block).toFloat(), (y + block).toFloat(), paint)
        }
        // the page's words over the room: darker toward the top, where the titles and the lists start
        if (shaded) {
            if (shade == null) shade = LinearGradient(0f, 0f, 0f, h * 400f / 1080f, SHADE, 0x000E0E0E, Shader.TileMode.CLAMP)
            paint.shader = shade
            canvas.drawRect(0f, 0f, w.toFloat(), h * 400f / 1080f, paint)
            paint.shader = null
        }
        // the next change of the eyes, the tail or the ear, only while the view is on screen
        removeCallbacks(tick)
        if (isShown && windowVisibility == VISIBLE) {
            val next = minOf(
                if (blink < BLINK_FROM) BLINK_FROM - blink else if (blink < BLINK_TO) BLINK_TO - blink else BLINK - blink + BLINK_FROM,
                if (wag < TAIL_UP) TAIL_UP - wag else TAIL - wag,
                if (twitch < EAR_FROM) EAR_FROM - twitch else if (twitch < EAR_TO) EAR_TO - twitch else EAR - twitch + EAR_FROM)
            postDelayed(tick, max(16L, next))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        floorShader = null
        shade = null
    }

    private companion object {
        const val WALL = 0xFF2D2B29.toInt()
        const val FLOOR = 0xFF272523.toInt()
        const val SHADE = 0xD90E0E0E.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val FUR = 0xFFE0E0E0.toInt()
        // the TV's keyframes (player.css): a blink at 95-98 % of 6 s, the tail up from 50 % of 2.4 s, the ear up
        // at 91-95 % of 9 s (their ramps counted by halves)
        const val BLINK = 6000L
        const val BLINK_FROM = 5670L
        const val BLINK_TO = 5940L
        const val TAIL = 2400L
        const val TAIL_UP = 1188L
        const val EAR = 9000L
        const val EAR_FROM = 8145L
        const val EAR_TO = 8595L
        private const val W = 0xFFFFFFFF.toInt()
        private const val G = 0xFFE0E0E0.toInt()
        private const val K = 0xFF000000.toInt()
        private const val P = 0xFFFFD6E4.toInt()
        // column, row, blocks, colour of each run of a row (IdleScene.qml, from the design's video.png)
        val RUNS = intArrayOf(
            1, 0, 1, W, 12, 0, 1, W, 1, 1, 1, G, 2, 1, 1, W,
            11, 1, 1, W, 12, 1, 1, G, 1, 2, 3, G, 4, 2, 5, W,
            9, 2, 1, G, 10, 2, 1, W, 11, 2, 2, G, 1, 3, 12, G,
            0, 4, 2, K, 2, 4, 1, G, 3, 4, 1, K, 4, 4, 4, G,
            8, 4, 1, K, 9, 4, 3, G, 12, 4, 2, K, 1, 5, 1, G,
            2, 5, 2, P, 4, 5, 4, G, 8, 5, 2, P, 10, 5, 3, G,
            0, 6, 2, K, 2, 6, 3, G, 5, 6, 1, K, 6, 6, 6, G,
            12, 6, 2, K, 2, 7, 2, G, 4, 7, 1, K, 5, 7, 1, G,
            6, 7, 1, K, 7, 7, 5, G, 3, 8, 8, G, 4, 9, 5, G,
            9, 9, 1, W, 14, 9, 1, W, 4, 10, 5, G, 9, 10, 1, W,
            15, 10, 1, W, 4, 11, 7, G, 16, 11, 1, W, 4, 12, 1, W,
            5, 12, 2, G, 7, 12, 1, W, 8, 12, 4, G, 16, 12, 1, W,
            4, 13, 1, W, 5, 13, 2, G, 7, 13, 1, W, 8, 13, 6, G,
            14, 13, 2, W, 4, 14, 1, W, 5, 14, 2, G, 7, 14, 1, W,
            8, 14, 2, G, 10, 14, 1, W, 11, 14, 3, G, 4, 15, 1, W,
            5, 15, 2, G, 7, 15, 1, W, 8, 15, 1, G, 9, 15, 1, W,
            10, 15, 4, G, 3, 16, 2, W, 5, 16, 1, G, 6, 16, 2, W,
            8, 16, 1, G, 9, 16, 1, W, 10, 16, 3, G)
    }
}
