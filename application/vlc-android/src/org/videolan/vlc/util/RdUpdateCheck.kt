/*
 * VLC Redesign: the app's own update check. Once a day it reads the latest release of
 * KuchiSofts/vlc-redesign on GitHub; when that release is newer than this app and carries an
 * Android APK, the user is offered to download it (Android installs it over this one: every
 * release is signed with the same key). Nothing is downloaded or installed by itself.
 */
package org.videolan.vlc.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object RdUpdateCheck {
    private const val TAG = "VLC/RdUpdateCheck"
    /** the switch in Settings › General and in the privacy step of the welcome (on by default) */
    const val KEY_CHECK = "rd_check_updates"
    private const val KEY_LAST = "rd_update_last_check"
    /** a version the user chose to skip: not offered again */
    const val KEY_SKIPPED = "rd_update_skipped"
    private const val LATEST = "https://api.github.com/repos/KuchiSofts/vlc-redesign/releases/latest"
    private val INTERVAL = TimeUnit.DAYS.toMillis(1)

    data class Release(val version: String, val apkUrl: String, val pageUrl: String)

    /** The newer release to offer, or null (switched off, checked today, skipped, none, offline) */
    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        val settings = Settings.getInstance(context)
        if (!settings.getBoolean(KEY_CHECK, true)) return@withContext null
        val now = System.currentTimeMillis()
        if (now - settings.getLong(KEY_LAST, 0L) in 0 until INTERVAL) return@withContext null
        val release = try {
            fetch()
        } catch (e: Exception) {
            Log.w(TAG, "No update check: ${e.message}")
            return@withContext null
        }
        settings.putSingle(KEY_LAST, now)
        release?.takeIf { isNewer(it.version, BuildConfig.RD_VERSION_NAME) && it.version != settings.getString(KEY_SKIPPED, null) }
    }

    private fun fetch(): Release? {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "VLC-Media-Player-Redesign-Android/${BuildConfig.RD_VERSION_NAME}")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val version = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name == "vlc-redesign-$version-android.apk")
                    return Release(version, asset.optString("browser_download_url"), json.optString("html_url"))
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    /** "0.5.1" > "0.5.0", "0.10.0" > "0.9.2"; anything after a "-" is ignored */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
