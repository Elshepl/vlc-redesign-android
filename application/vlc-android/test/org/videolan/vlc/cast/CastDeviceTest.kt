package org.videolan.vlc.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VLC Redesign, casting: the same device found twice is one row of the cast list (RendererDelegate) */
class CastDeviceTest {

    @Test
    fun sameNameIsSameDevice() {
        assertTrue(sameDevice("Living room TV", "Living room TV"))
        assertTrue(sameDevice("living room tv ", "Living room TV"))
    }

    @Test
    fun nameWithModelIsSameDevice() {
        assertTrue(sameDevice("Living room TV (Chromecast)", "Living room TV"))
        assertTrue(sameDevice("Living room TV", "Living room TV (Chromecast)"))
    }

    @Test
    fun otherDevicesAreNot() {
        assertFalse(sameDevice("Living room TV", "Living room TV 2"))
        assertFalse(sameDevice("Kitchen speaker", "Living room TV"))
        assertFalse(sameDevice("", ""))
        assertFalse(sameDevice("TV", "TV2 (Chromecast)"))
    }
}
