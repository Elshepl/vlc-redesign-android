package org.videolan.tools

/**
 * VLC Redesign (#10, suggested in #10): the subtitle size in percent, as the classic desktop VLC's
 * Ctrl + mouse wheel (hotkeys subtitle-text-scale-up / -down / -normal, libvlc "sub-text-scale"): within 50 % of
 * 100 % a step is 10, further 25, the size snapped to a multiple of the step, from 25 % to 500 %. It multiplies the
 * size of the subtitle settings (KEY_SUBTITLES_SIZE), as on the desktop.
 */
object SubtitleScale {
    const val MIN = 25
    const val MAX = 500
    const val DEFAULT = 100

    fun clamp(scale: Int) = scale.coerceIn(MIN, MAX)

    /** The next size up or down from [scale], with the steps of the desktop's hotkeys. */
    fun step(scale: Int, up: Boolean): Int {
        val distance = if (scale > DEFAULT) scale - DEFAULT else DEFAULT - scale
        val increment = if (distance / 25 <= 1) 10 else 25
        var next = scale + if (up) increment else -increment
        next -= next % increment
        return clamp(next)
    }

    fun get(settings: android.content.SharedPreferences) = clamp(settings.getInt(KEY_SUBTITLES_TEXT_SCALE, DEFAULT))
}
