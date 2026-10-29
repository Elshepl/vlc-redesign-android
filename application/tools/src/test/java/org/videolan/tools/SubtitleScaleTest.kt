package org.videolan.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/** #10: the steps of the desktop's subtitle-text-scale hotkeys (Ctrl + mouse wheel) */
class SubtitleScaleTest {
    private fun walk(from: Int, up: Boolean, count: Int): List<Int> {
        var s = from
        return List(count) { s = SubtitleScale.step(s, up); s }
    }

    @Test
    fun up() = assertEquals(listOf(110, 120, 130, 140, 150, 175, 200, 225), walk(100, true, 8))

    @Test
    fun down() = assertEquals(listOf(90, 80, 70, 60, 50, 25, 25), walk(100, false, 7))

    @Test
    fun snapsToTheStep() {
        assertEquals(120, SubtitleScale.step(113, true))
        assertEquals(100, SubtitleScale.step(113, false))
        assertEquals(175, SubtitleScale.step(160, true))
    }

    @Test
    fun range() {
        assertEquals(500, SubtitleScale.step(500, true))
        assertEquals(500, SubtitleScale.step(490, true))
        assertEquals(25, SubtitleScale.step(30, false))
        assertEquals(25, SubtitleScale.clamp(3))
        assertEquals(500, SubtitleScale.clamp(900))
    }
}
