/*****************************************************************************
 * VLCOptions.java
 *
 * Copyright © 2015 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 */

package org.videolan.resources

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.libvlc.util.VLCUtil
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.KEY_AOUT
import org.videolan.tools.KEY_AUDIO_DIGITAL_OUTPUT
import org.videolan.tools.KEY_AUDIO_REPLAY_GAIN_DEFAULT
import org.videolan.tools.KEY_AUDIO_REPLAY_GAIN_ENABLE
import org.videolan.tools.KEY_AUDIO_REPLAY_GAIN_MODE
import org.videolan.tools.KEY_AUDIO_REPLAY_GAIN_PEAK_PROTECTION
import org.videolan.tools.KEY_AUDIO_REPLAY_GAIN_PREAMP
import org.videolan.tools.KEY_CASTING_AUDIO_ONLY
import org.videolan.tools.KEY_CASTING_PASSTHROUGH
import org.videolan.tools.KEY_CASTING_QUALITY
import org.videolan.tools.KEY_CUSTOM_LIBVLC_OPTIONS
import org.videolan.tools.KEY_DEBLOCKING
import org.videolan.tools.KEY_ENABLE_FRAME_SKIP
import org.videolan.tools.KEY_ENABLE_TIME_STRETCHING_AUDIO
import org.videolan.tools.KEY_ENABLE_VERBOSE_MODE
import org.videolan.tools.KEY_HARDWARE_ACCELERATION
import org.videolan.tools.KEY_NETWORK_CACHING_VALUE
import org.videolan.tools.KEY_OPENGL
import org.videolan.tools.KEY_SET_LOCALE
import org.videolan.tools.KEY_PREFERRED_RESOLUTION
import org.videolan.tools.KEY_PREFER_SMBV1
import org.videolan.tools.KEY_SUBTITLES_AUTOLOAD
import org.videolan.tools.KEY_SUBTITLES_BACKGROUND
import org.videolan.tools.KEY_SUBTITLES_BACKGROUND_COLOR
import org.videolan.tools.KEY_SUBTITLES_BACKGROUND_COLOR_OPACITY
import org.videolan.tools.KEY_SUBTITLES_BOLD
import org.videolan.tools.KEY_SUBTITLES_COLOR
import org.videolan.tools.KEY_SUBTITLES_COLOR_OPACITY
import org.videolan.tools.KEY_SUBTITLES_OUTLINE
import org.videolan.tools.KEY_SUBTITLES_OUTLINE_COLOR
import org.videolan.tools.KEY_SUBTITLES_OUTLINE_COLOR_OPACITY
import org.videolan.tools.KEY_SUBTITLES_OUTLINE_SIZE
import org.videolan.tools.KEY_SUBTITLES_SHADOW
import org.videolan.tools.KEY_SUBTITLES_SHADOW_COLOR
import org.videolan.tools.KEY_SUBTITLES_SHADOW_COLOR_OPACITY
import org.videolan.tools.KEY_SUBTITLES_SIZE
import org.videolan.tools.KEY_SUBTITLE_TEXT_ENCODING
import org.videolan.tools.Preferences
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.VlcMigrationHelper
import org.videolan.vlc.isVLC4
import java.io.File
import java.util.Collections

object VLCOptions {
    private const val TAG = "VLC/VLCConfig"

    private const val AOUT_AAUDIO = 0
    private const val AOUT_AUDIOTRACK = 1
    private const val AOUT_OPENSLES = 2

    private const val HW_ACCELERATION_AUTOMATIC = -1
    private const val HW_ACCELERATION_DISABLED = 0
    private const val HW_ACCELERATION_DECODING = 1
    private const val HW_ACCELERATION_FULL = 2

    var audiotrackSessionId = 0
        private set

    // TODO should return List<String>
    /* generate an audio session id so as to share audio output with external equalizer *//* CPU intensive plugin, setting for slow devices *//* XXX: why can't the default be fine ? #7792 *//* Configure keystore *///Chromecast
    val libOptions: ArrayList<String>
        get() {
            val context = AppContextProvider.appContext
            val pref = Settings.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && audiotrackSessionId == 0) {
                val audioManager = context.getSystemService<AudioManager>()!!
                audiotrackSessionId = audioManager.generateAudioSessionId()
            }

            val options = ArrayList<String>(50)

            val timeStrechingDefault = context.resources.getBoolean(R.bool.time_stretching_default)
            val timeStreching = pref.getBoolean(KEY_ENABLE_TIME_STRETCHING_AUDIO, timeStrechingDefault)
            val subtitlesEncoding = pref.getString(KEY_SUBTITLE_TEXT_ENCODING, "") ?: ""
            val frameSkip = pref.getBoolean(KEY_ENABLE_FRAME_SKIP, false)
            val verboseMode = pref.getBoolean(KEY_ENABLE_VERBOSE_MODE, true)
            val castingAudioOnly = pref.getBoolean(KEY_CASTING_AUDIO_ONLY, false)

            var deblocking = -1
            try {
                deblocking = getDeblocking(Integer.parseInt(pref.getString(KEY_DEBLOCKING, "-1")!!))
            } catch (ignored: NumberFormatException) {
            }

            val networkCaching = pref.getInt(KEY_NETWORK_CACHING_VALUE, 0).coerceIn(0, 60000)
            val freetypeRelFontsize = pref.getString(KEY_SUBTITLES_SIZE, "16")
            val freetypeBold = pref.getBoolean(KEY_SUBTITLES_BOLD, false)

            val freetypeColor = try {
                Integer.decode(String.format("0x%06X", (0xFFFFFF and pref.getInt(KEY_SUBTITLES_COLOR, 16777215))))
            } catch (e: ClassCastException) {
                Log.w(TAG, "Forced migration of subtitles color")
                //Migration failed somehow. Migrating here
                var color = 16777215
                pref.getString(KEY_SUBTITLES_COLOR, "16777215")?.let {oldSetting ->
                    try {
                        val oldColor = oldSetting.toInt()
                        val newColor = Color.argb(255, Color.red(oldColor), Color.green(oldColor), Color.blue(oldColor))
                        pref.putSingle(KEY_SUBTITLES_COLOR, newColor)
                        color = newColor
                    } catch (e: Exception) {
                        pref.edit().remove(KEY_SUBTITLES_COLOR).apply()
                    }
                }

                color
            }
            val freetypeColorOpacity = pref.getInt(KEY_SUBTITLES_COLOR_OPACITY, 255)

            val freetypeBackgroundColor = Integer.decode(String.format("0x%06X", (0xFFFFFF and pref.getInt(KEY_SUBTITLES_BACKGROUND_COLOR, 16777215))))
            val freetypeBackgroundColorOpacity = pref.getInt(KEY_SUBTITLES_BACKGROUND_COLOR_OPACITY, 255)
            val freetypeBackgroundEnabled = pref.getBoolean(KEY_SUBTITLES_BACKGROUND, false)

            val freetypeOutlineEnabled = pref.getBoolean(KEY_SUBTITLES_OUTLINE, true)
            val freetypeOutlineSize = pref.getString(KEY_SUBTITLES_OUTLINE_SIZE, "4")
            val freetypeOutlineColor = Integer.decode(String.format("0x%06X", (0xFFFFFF and pref.getInt(KEY_SUBTITLES_OUTLINE_COLOR, 0))))
            val freetypeOutlineOpacity = pref.getInt(KEY_SUBTITLES_OUTLINE_COLOR_OPACITY, 255)


            val freetypeShadowEnabled = pref.getBoolean(KEY_SUBTITLES_SHADOW, true)
            val freetypeShadowColor = Integer.decode(String.format("0x%06X", (0xFFFFFF and pref.getInt(KEY_SUBTITLES_SHADOW_COLOR, ContextCompat.getColor(context, R.color.black)))))
            val freetypeShadowOpacity = pref.getInt(KEY_SUBTITLES_SHADOW_COLOR_OPACITY, 128)


            val opengl = Integer.parseInt(pref.getString(KEY_OPENGL, "-1")!!)
            if (castingAudioOnly) options.add("--no-sout-chromecast-video")
            options.add(if (timeStreching) "--audio-time-stretch" else "--no-audio-time-stretch")
            options.add("--avcodec-skiploopfilter")
            options.add("" + deblocking)
            options.add("--avcodec-skip-frame")
            options.add(if (frameSkip) "2" else "0")
            options.add("--avcodec-skip-idct")
            options.add(if (frameSkip) "2" else "0")
            options.add("--subsdec-encoding")
            // VLC Redesign: "Default" guesses the code page of a subtitle file that is not Unicode from the language,
            // as the desktop does through its "GetACP" translation (libvlc on Android has no translations, so it
            // always guessed Windows-1252: a Hebrew or Arabic SRT came out as Latin letters). UTF-8 files are still
            // detected as such.
            options.add(subtitlesEncoding.ifEmpty { defaultSubtitlesEncoding(pref.getString(KEY_SET_LOCALE, "") ?: "") })
            // VLC Redesign: lay out each subtitle line in the direction of its own text (Hebrew and Arabic right to
            // left), as VLC 4 on the desktop does; VLC 3.0's default is left to right for every line
            options.add("--freetype-text-direction=2")
            options.add("--stats")
            if (networkCaching > 0) options.add("--network-caching=$networkCaching")
            options.add("--audio-resampler")
            options.add("soxr")
            options.add("--audiotrack-session-id=$audiotrackSessionId")

            if (isVLC4()) options.add("--sub-text-scale=" + (1600 / freetypeRelFontsize!!.toFloat()).toString()) else options.add("--freetype-rel-fontsize=" + freetypeRelFontsize!!)
            if (freetypeBold) options.add("--freetype-bold")
            options.add("--freetype-color=$freetypeColor")
            options.add("--freetype-opacity=$freetypeColorOpacity")
            if (freetypeBackgroundEnabled) {
                options.add("--freetype-background-color=$freetypeBackgroundColor")
                options.add("--freetype-background-opacity=$freetypeBackgroundColorOpacity")
            } else options.add("--freetype-background-opacity=0")

            if (freetypeShadowEnabled) {
                options.add("--freetype-shadow-color=$freetypeShadowColor")
                options.add("--freetype-shadow-opacity=$freetypeShadowOpacity")
            } else options.add("--freetype-shadow-opacity=0")

            if (freetypeOutlineEnabled) {
                options.add("--freetype-outline-thickness=$freetypeOutlineSize")
                    options.add("--freetype-outline-color=$freetypeOutlineColor")
                    options.add("--freetype-outline-opacity=$freetypeOutlineOpacity")
            } else options.add("--freetype-outline-opacity=0")


            if (opengl == 1) options.add("--vout=gles2,none")
            else if (opengl == 0) options.add("--vout=android_display,none")
            options.add("--keystore")
            options.add(if (AndroidUtil.isMarshMallowOrLater) "file_crypt,none" else "file_plaintext,none")
            options.add("--keystore-file")
            options.add(File(context.getDir("keystore", Context.MODE_PRIVATE), "file").absolutePath)
            options.add(if (verboseMode) "-vv" else "-v")
            // fixme comment temporarily
            // (VLC Redesign: VLC 4 builds take these from the media's options, setMediaOptions)
            if (!isVLC4()) {
                if (pref.getBoolean(KEY_CASTING_PASSTHROUGH, false))
                    options.add("--sout-chromecast-audio-passthrough")
                else
                    options.add("--no-sout-chromecast-audio-passthrough")
                options.add("--sout-chromecast-conversion-quality=" + pref.getString(KEY_CASTING_QUALITY, "2")!!)
            }
            options.add("--sout-keep")

            val customOptions = pref.getString(KEY_CUSTOM_LIBVLC_OPTIONS, null)
            if (!customOptions.isNullOrEmpty()) {
                val optionsArray = customOptions.split("\\r?\\n".toRegex()).toTypedArray()
                if (!optionsArray.isNullOrEmpty()) Collections.addAll(options, *optionsArray)
            }
            if (pref.getBoolean(KEY_PREFER_SMBV1, true))
                options.add("--smb-force-v1")
            if (!Settings.showTvUi) {
                //Ambisonic
                val hstfDir = context.getDir("vlc", Context.MODE_PRIVATE)
                val hstfPath = "${hstfDir.absolutePath}/.share/hrtfs/dodeca_and_7channel_3DSL_HRTF.sofa"
                options.add("--hrtf-file")
                options.add(hstfPath)
            }
            if (pref.getBoolean(KEY_AUDIO_REPLAY_GAIN_ENABLE, false)) {
                options.add("--audio-replay-gain-mode=${pref.getString(KEY_AUDIO_REPLAY_GAIN_MODE, "track")}")
                options.add("--audio-replay-gain-preamp=${pref.getString(KEY_AUDIO_REPLAY_GAIN_PREAMP, "0.0")}")
                options.add("--audio-replay-gain-default=${pref.getString(KEY_AUDIO_REPLAY_GAIN_DEFAULT, "-7.0")}")
                if (pref.getBoolean(KEY_AUDIO_REPLAY_GAIN_PEAK_PROTECTION, true))
                    options.add("--audio-replay-gain-peak-protection")
                else
                    options.add("--no-audio-replay-gain-peak-protection")
            }
            // VLC Redesign: the desktop's Speaker setup (stereo-mode, Automatic = 0) and its Audio delay for every
            // media (audio-desync, ms), both libvlc 3 options
            pref.getString("rd_stereo_mode", "0")?.toIntOrNull()?.takeIf { it in 1..5 }?.let { options.add("--stereo-mode=$it") }
            pref.getString("rd_audio_desync", "0")?.trim()?.toIntOrNull()?.takeIf { it != 0 }?.let { options.add("--audio-desync=$it") }
            // VLC Redesign: the desktop's other settings that libvlc 3 has (Settings > Video, Subtitles, Playback,
            // Network, Performance); unset = VLC's default
            fun text(key: String) = pref.getString(key, "")?.trim().orEmpty()
            text("rd_deinterlace").toIntOrNull()?.takeIf { it != -1 }?.let { options.add("--deinterlace=$it") }
            text("rd_deinterlace_mode").takeIf { it.matches(Regex("[a-z0-9]+")) && it != "auto" }?.let { options.add("--deinterlace-mode=$it") }
            text("rd_avi_index").toIntOrNull()?.takeIf { it in 1..3 }?.let { options.add("--avi-index=$it") }
            text("rd_sub_fuzzy").toIntOrNull()?.takeIf { it in 0..4 && it != 3 }?.let { options.add("--sub-autodetect-fuzzy=$it") }
            text("rd_file_caching").toIntOrNull()?.takeIf { it in 0..60000 }?.let { options.add("--file-caching=$it") }
            text("rd_live_caching").toIntOrNull()?.takeIf { it in 0..60000 }?.let { options.add("--live-caching=$it") }
            // libvlc 3 on Android has no --http-proxy (it is the Windows build's only): its vlc_getProxyUrl() reads
            // the Java properties http.proxyHost / http.proxyPort, so the desktop's HTTP proxy is set there
            applyHttpProxy(text("rd_http_proxy"))
            // the quality alone does nothing: as on the desktop, the "postproc" video filter has to run as well
            val postproc = text("rd_postproc").toIntOrNull()?.takeIf { it in 1..6 }
            postproc?.let { options.add("--postproc-q=$it") }
            // VLC Redesign: the video effects of the player's Effects dialog are filters of the whole libvlc too
            options.addAll(RdEffects.videoOptions(pref, postproc != null))
            // the desktop's Two-pass equalizer (the equalizer filter reads it when it opens)
            if (pref.getBoolean("rd_eq_2pass", false)) options.add("--equalizer-2pass")
            text("rd_avcodec_threads").toIntOrNull()?.takeIf { it in 1..32 }?.let { options.add("--avcodec-threads=$it") }
            pref.getInt("rd_sub_margin", 0).takeIf { it in 1..400 }?.let { options.add("--sub-margin=$it") }
            if (pref.getBoolean(KEY_SUBTITLES_SHADOW, true)) pref.getInt("rd_shadow_distance", 6).takeIf { it in 0..20 && it != 6 }
                    ?.let { options.add("--freetype-shadow-distance=" + String.format(java.util.Locale.US, "%.2f", it / 100f)) }
            val soundFontFile = getSoundFontFile(context)
            if (soundFontFile.exists()) {
                options.add("--soundfont=${soundFontFile.path}")
            }
            options.add("--preferred-resolution=${pref.getString(KEY_PREFERRED_RESOLUTION, "-1")!!}")
            if (BuildConfig.DEBUG) Log.d(this::class.java.simpleName, "VLC Options: ${options.joinToString(" ")}")
            return options
        }

    /** The proxy this app set last, so that clearing the setting never removes a proxy the system set. */
    private var rdProxySet: Pair<String, String>? = null

    /** "host:port" or "http://[user@]host:port/" (the desktop's field) -> http.proxyHost / http.proxyPort; empty clears ours. */
    private fun applyHttpProxy(value: String) {
        val m = Regex("^(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?(?:[^@/]*@)?(\\[[0-9a-fA-F:]+]|[^:/\\s]+)(?::(\\d{1,5}))?/?$").find(value)
        if (m != null) {
            val host = m.groupValues[1].removePrefix("[").removeSuffix("]")
            val port = m.groupValues[2].ifEmpty { "80" }
            System.setProperty("http.proxyHost", host)
            System.setProperty("http.proxyPort", port)
            rdProxySet = host to port
            Log.d(TAG, "HTTP proxy $host:$port")
        } else rdProxySet?.let { (host, port) ->
            if (System.getProperty("http.proxyHost") == host && System.getProperty("http.proxyPort") == port) {
                System.clearProperty("http.proxyHost")
                System.clearProperty("http.proxyPort")
            }
            rdProxySet = null
        }
    }

    fun isAudioDigitalOutputEnabled(pref: SharedPreferences) = pref.getBoolean(KEY_AUDIO_DIGITAL_OUTPUT, false)

    fun setAudioDigitalOutputEnabled(pref: SharedPreferences, enabled: Boolean) {
        pref.putSingle(KEY_AUDIO_DIGITAL_OUTPUT, enabled)
    }

    fun getAout(pref: SharedPreferences): String? {
        var aout = -1
        try {
            aout = Integer.parseInt(pref.getString(KEY_AOUT, "-1")!!)
        } catch (ignored: NumberFormatException) {
        }

        val hwaout = VlcMigrationHelper.getAudioOutputFromDevice()
        if (hwaout == VlcMigrationHelper.AudioOutput.OPENSLES)
            aout = AOUT_OPENSLES

        return if (aout == AOUT_OPENSLES) "opensles" else if (aout == AOUT_AUDIOTRACK) "audiotrack" else null /* aaudio is the default */
    }

    private fun getDeblocking(deblocking: Int): Int {
        var ret = deblocking
        if (deblocking < 0) {
            /**
             * Set some reasonable deblocking defaults:
             *
             * Skip all (4) for armv6 and MIPS by default
             * Skip non-ref (1) for all armv7 more than 1.2 Ghz and more than 2 cores
             * Skip non-key (3) for all devices that don't meet anything above
             */
            val m = VLCUtil.getMachineSpecs() ?: return ret
            if (m.hasArmV6 && !m.hasArmV7 || m.hasMips)
                ret = 4
            else if (m.frequency >= 1200 && m.processors > 2)
                ret = 1
            else if (m.bogoMIPS >= 1200 && m.processors > 2) {
                ret = 1
                Log.d(TAG, "Used bogoMIPS due to lack of frequency info")
            } else
                ret = 3
        } else if (deblocking > 4) { // sanity check
            ret = 3
        }
        return ret
    }

    fun setMediaOptions(media: IMedia, context: Context, flags: Int, hasRenderer: Boolean) {
        val noHardwareAcceleration = flags and MediaWrapper.MEDIA_NO_HWACCEL != 0
        val noVideo = flags and MediaWrapper.MEDIA_VIDEO == 0
        val paused = flags and MediaWrapper.MEDIA_PAUSED != 0
        var hardwareAcceleration = HW_ACCELERATION_DISABLED
        val prefs = Settings.getInstance(context)

        if (!noHardwareAcceleration) {
            try {
                hardwareAcceleration = Integer.parseInt(prefs.getString(KEY_HARDWARE_ACCELERATION, "$HW_ACCELERATION_AUTOMATIC")!!)
            } catch (ignored: NumberFormatException) {}

        }
        if (hardwareAcceleration == HW_ACCELERATION_DISABLED)
            media.setHWDecoderEnabled(false, false)
        else if (hardwareAcceleration == HW_ACCELERATION_FULL || hardwareAcceleration == HW_ACCELERATION_DECODING) {
            media.setHWDecoderEnabled(true, true)
            if (hardwareAcceleration == HW_ACCELERATION_DECODING) {
                media.addOption(":no-mediacodec-dr")
                media.addOption(":no-omxil-dr")
            }
        } /* else automatic: use default options */

        if (noVideo) media.addOption(":no-video")
        if (paused) media.addOption(":start-paused")
        if (!prefs.getBoolean(KEY_SUBTITLES_AUTOLOAD, true)) media.addOption(":sub-language=none")
        // VLC Redesign: the video effects of the player's Effects dialog (filters of this media)
        if (!noVideo && !hasRenderer) RdEffects.videoMediaOptions(prefs).forEach { media.addOption(it) }

        if (hasRenderer) {
            // VLC Redesign, casting: the subtitles are there but none is on when the cast starts: libvlc 3's Chromecast
            // output restarts its stream for a subtitle at the start and the device (the test Chromecast) ended the
            // media at once. One chosen while it plays is burnt into the picture.
            media.addOption(":sub-language=none")
            // VLC Redesign, casting: the same default as libvlc's option above and the settings (off): libvlc 3
            // takes libvlc's (its Chromecast output does not read the media's options), VLC 4 builds these
            media.addOption(":sout-chromecast-audio-passthrough=" + prefs.getBoolean(KEY_CASTING_PASSTHROUGH, false))
            media.addOption(":sout-chromecast-conversion-quality=" + prefs.getString(KEY_CASTING_QUALITY, "2")!!)
        }
    }

    fun getEqualizerEnabledState(context: Context): Boolean {
        return Settings.getInstance(context).getBoolean("equalizer_enabled", false)
    }

    fun getSoundFontFile(context: Context)= File( context.getDir("soundfont", Context.MODE_PRIVATE)!!.path+"/soundfont.sf2")

    /**
     * VLC Redesign: the Windows code page most used for a language (the desktop's "GetACP" translations), the guess
     * for subtitle files that are not Unicode. The app's language, else the system's.
     */
    fun defaultSubtitlesEncoding(appLocale: String): String {
        val lang = appLocale.ifEmpty { java.util.Locale.getDefault().toString() }.replace('-', '_').lowercase()
        val base = lang.substringBefore('_')
        return when {
            base == "he" || base == "iw" || base == "yi" -> "CP1255"
            base in setOf("ar", "fa", "ur", "ps", "ckb", "ku", "sd", "ug") -> "CP1256"
            base in setOf("ru", "uk", "be", "bg", "mk", "sr", "kk", "ky", "tg", "mn", "tt", "ba") -> "CP1251"
            base == "el" -> "CP1253"
            base == "tr" || base == "az" -> "CP1254"
            base in setOf("lt", "lv", "et") -> "CP1257"
            base == "vi" -> "CP1258"
            base in setOf("pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs", "sq") -> "CP1250"
            base == "th" -> "CP874"
            base == "ja" -> "CP932"
            base == "ko" -> "CP949"
            base == "zh" -> if (lang.contains("tw") || lang.contains("hk") || lang.contains("hant")) "BIG5" else "GB18030"
            else -> "CP1252"
        }
    }
}
