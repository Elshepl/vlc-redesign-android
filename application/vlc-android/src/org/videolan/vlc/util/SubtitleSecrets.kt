/*
 * VLC Redesign: the user's keys, user names, passwords and sign-in tokens of the subtitle services (OnlineSubtitles),
 * kept apart from the settings: in a file of their own that the settings export and Android's backups leave out
 * (backup_rules.xml, data_extraction_rules.xml), each value encrypted with a key of the Android keystore (AES-GCM,
 * Android 6 and later; the key never leaves the keystore). Android 5 has no keystore AES: the values stay in the
 * app's private file only. Never logged.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.videolan.tools.Settings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SubtitleSecrets {
    /** the file (shared_prefs/rd_subtitle_secrets.xml), out of the backups */
    const val FILE = "rd_subtitle_secrets"
    private const val KEY_ALIAS = "vlc_redesign_subtitles"
    private const val ENCRYPTED = "k1:"
    private const val PLAIN = "p1:"

    @Volatile private var migrated = false

    private fun prefs(context: Context): SharedPreferences {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!migrated) migrate(context, p)
        return p
    }

    /** read once off the main thread, so that the settings' rows don't read the disk on it */
    suspend fun preload(context: Context) = withContext(Dispatchers.IO) { prefs(context).all.size }

    private fun name(id: String, field: String) = "$id.$field"

    fun has(context: Context, id: String, field: String) = prefs(context).contains(name(id, field))

    fun get(context: Context, id: String, field: String): String = decrypt(prefs(context).getString(name(id, field), null) ?: "")

    /** an empty value forgets it; a new key or account forgets the token of the old one */
    fun set(context: Context, id: String, field: String, value: String) {
        val edit = prefs(context).edit()
        if (value.isEmpty()) edit.remove(name(id, field)) else edit.putString(name(id, field), encrypt(value))
        edit.remove(name(id, "token")).remove(name(id, "token_user")).remove(name(id, "token_until"))
        edit.apply()
    }

    /** forgets all the service's (Sign out, Clear) */
    fun clear(context: Context, id: String) {
        val p = prefs(context)
        val edit = p.edit()
        for (k in p.all.keys) if (k.startsWith("$id.")) edit.remove(k)
        edit.apply()
    }

    /** the service's sign-in token of that user, while it is valid */
    fun token(context: Context, id: String, user: String): String? {
        val p = prefs(context)
        if (p.getString(name(id, "token_user"), null) != user) return null
        if (System.currentTimeMillis() > p.getLong(name(id, "token_until"), 0L)) return null
        return decrypt(p.getString(name(id, "token"), null) ?: "").ifEmpty { null }
    }

    fun setToken(context: Context, id: String, user: String, token: String, until: Long) {
        prefs(context).edit().putString(name(id, "token"), encrypt(token)).putString(name(id, "token_user"), user)
            .putLong(name(id, "token_until"), until).apply()
    }

    fun clearToken(context: Context, id: String) {
        prefs(context).edit().remove(name(id, "token")).remove(name(id, "token_user")).remove(name(id, "token_until")).apply()
    }

    /**
     * the OpenSubtitles.com key, user name and password of 0.5.x were in the settings (and so in an exported copy of
     * them): moved here once, and out of the settings. With a key given before, its switch stays on.
     */
    private fun migrate(context: Context, p: SharedPreferences) {
        synchronized(this) {
            if (migrated) return
            val settings = Settings.getInstance(context)
            val old = mapOf(
                OnlineSubtitles.KEY_OSCOM_KEY to OnlineSubtitles.Field.KEY,
                OnlineSubtitles.KEY_OSCOM_USER to OnlineSubtitles.Field.USERNAME,
                OnlineSubtitles.KEY_OSCOM_PASSWORD to OnlineSubtitles.Field.PASSWORD
            )
            if (old.keys.any { settings.contains(it) }) {
                val edit = p.edit()
                val settingsEdit = settings.edit()
                for ((key, field) in old) {
                    val v = settings.getString(key, null) ?: ""
                    if (v.isNotBlank() && !p.contains(name("opensubtitlescom", field.pref))) edit.putString(name("opensubtitlescom", field.pref), encrypt(v))
                    settingsEdit.remove(key)
                }
                if ((settings.getString(OnlineSubtitles.KEY_OSCOM_KEY, null) ?: "").isNotBlank() && !settings.contains("rd_sub_service_opensubtitlescom"))
                    settingsEdit.putBoolean("rd_sub_service_opensubtitlescom", true)
                edit.apply()
                settingsEdit.apply()
            }
            migrated = true
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.M)
    private fun keystoreKey(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    } catch (e: Exception) {
        null
    }

    private fun key(): SecretKey? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) keystoreKey() else null

    private fun encrypt(text: String): String {
        val k = key()
        if (k != null) try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            return ENCRYPTED + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
        } catch (e: Exception) {
            // (a keystore that fails: kept in the private file only, as on Android 5)
        }
        return PLAIN + Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String = try {
        when {
            stored.startsWith(ENCRYPTED) -> {
                val k = key() ?: return ""
                val rest = stored.substring(ENCRYPTED.length)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, Base64.decode(rest.substringBefore(':'), Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(rest.substringAfter(':'), Base64.NO_WRAP)), Charsets.UTF_8)
            }
            stored.startsWith(PLAIN) -> String(Base64.decode(stored.substring(PLAIN.length), Base64.NO_WRAP), Charsets.UTF_8)
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }
}
