package org.videolan.vlc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VLC Redesign, the update check: which release counts as newer than the app (RdUpdateCheck.isNewer) */
class RdUpdateCheckTest {

    @Test
    fun newerPatchMinorMajor() {
        assertTrue(RdUpdateCheck.isNewer("0.5.1", "0.5.0"))
        assertTrue(RdUpdateCheck.isNewer("0.6.0", "0.5.9"))
        assertTrue(RdUpdateCheck.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun numbersNotText() {
        assertTrue(RdUpdateCheck.isNewer("0.10.0", "0.9.2"))
        assertFalse(RdUpdateCheck.isNewer("0.9.2", "0.10.0"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(RdUpdateCheck.isNewer("0.5.0", "0.5.0"))
        assertFalse(RdUpdateCheck.isNewer("0.4.1", "0.5.0"))
        assertFalse(RdUpdateCheck.isNewer("0.5", "0.5.0"))
    }

    @Test
    fun suffixIgnored() {
        assertFalse(RdUpdateCheck.isNewer("0.5.0-beta", "0.5.0"))
        assertTrue(RdUpdateCheck.isNewer("0.5.1-rc1", "0.5.0"))
    }
}
