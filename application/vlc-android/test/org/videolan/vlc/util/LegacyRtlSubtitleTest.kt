package org.videolan.vlc.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for legacy RTL subtitle line ends swapping (Arabic/Hebrew subtitles).
 * Matches test cases verified on desktop in PrepareRightToLeftLine.
 */
class LegacyRtlSubtitleTest {

    private val ARB = "تكلم" // "speak" in Arabic

    @Test
    fun testSwapLegacyLines() {
        // Both L (leading mark) and D (trailing dash) present: swap them
        assertEquals("- $ARB.", SubtitleCharset.swapLegacyRtlLine(".$ARB -"))
        assertEquals("- بالتأكيد.", SubtitleCharset.swapLegacyRtlLine(".بالتأكيد -"))
        assertEquals("- أقصد أنّنا نخوض في ذلك؟", SubtitleCharset.swapLegacyRtlLine("؟أقصد أنّنا نخوض في ذلك -"))
        assertEquals("- ليس كلّ مرّة.", SubtitleCharset.swapLegacyRtlLine(".ليس كلّ مرّة -"))

        // Trailing dash only, no leading mark: leave as-is (legitimate dialogue dash)
        assertEquals("- $ARB", SubtitleCharset.swapLegacyRtlLine("$ARB -"))
        assertEquals("- $ARB.", SubtitleCharset.swapLegacyRtlLine("- $ARB."))

        // Leading period only, no dash: move period to end
        assertEquals("$ARB.", SubtitleCharset.swapLegacyRtlLine(".$ARB"))
        assertEquals("بالتأكيد.", SubtitleCharset.swapLegacyRtlLine(".بالتأكيد"))

        // Question mark at end with trailing dash
        assertEquals("- $ARB؟", SubtitleCharset.swapLegacyRtlLine("؟$ARB -"))
        assertEquals("- $ARB؟", SubtitleCharset.swapLegacyRtlLine("$ARB؟ -"))

        // Ellipsis at start (no dash): preserved as ellipsis
        assertEquals("...$ARB", SubtitleCharset.swapLegacyRtlLine("...$ARB"))

        // Trailing spaces
        assertEquals("- $ARB.   ", SubtitleCharset.swapLegacyRtlLine(".$ARB -   "))

        // Bidi control character present: skipped (direction already controlled)
        val controlled = "\u202B . $ARB - \u202C"
        assertEquals(controlled, SubtitleCharset.swapLegacyRtlLine(controlled))

        // English text: untouched
        assertEquals(".Hello -", SubtitleCharset.swapLegacyRtlLine(".Hello -"))
        assertEquals("Hello world.", SubtitleCharset.swapLegacyRtlLine("Hello world."))

        // HTML tags
        assertEquals("<i>- $ARB.</i>", SubtitleCharset.swapLegacyRtlLine("<i>.$ARB -</i>"))
        assertEquals("<font color=\"#ffff00\">- $ARB.</font>", SubtitleCharset.swapLegacyRtlLine("<font color=\"#ffff00\">.$ARB -</font>"))
    }

    @Test
    fun testFixLegacyRtlSrt() {
        val srtInput = """
            1
            00:01:20,000 --> 00:01:23,000
            .بالتأكيد -

            2
            00:01:24,000 --> 00:01:27,000
            ؟أقصد أنّنا نخوض في ذلك -
            .ليس كلّ مرّة -
        """.trimIndent()

        val expectedSrt = """
            1
            00:01:20,000 --> 00:01:23,000
            - بالتأكيد.

            2
            00:01:24,000 --> 00:01:27,000
            - أقصد أنّنا نخوض في ذلك؟
            - ليس كلّ مرّة.
        """.trimIndent()

        val result = SubtitleCharset.fixLegacyRtlSrt(srtInput)
        assertNotNull(result)
        assertEquals(expectedSrt, result)
    }

    @Test
    fun testAlreadyCorrectSrtReturnsNull() {
        val correctSrt = """
            1
            00:01:20,000 --> 00:01:23,000
            - بالتأكيد.
        """.trimIndent()

        assertNull(SubtitleCharset.fixLegacyRtlSrt(correctSrt))
    }
}
