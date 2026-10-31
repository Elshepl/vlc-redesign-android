/*
 * VLC Redesign: folders and playlists pinned to the sidebar (the desktop's "Pin to Sidebar", SidebarItems.qml).
 *
 * A folder of the browser (local or network), a folder of the Videos page or a playlist can be pinned from its
 * menu. The pins are rows of the sidebar's order (Edit Sidebar moves and hides them as the pages, [TvNavigation]):
 * they show among the pages of the tablet sidebar, and on a phone (or a window without the sidebar) as the first
 * row of Home; a tap opens the folder or the playlist. Kept in the app's preferences (rd_pins, JSON), in the order
 * they were pinned; [pins] tells the views when they change, [layout] when Edit Sidebar moved or hid them.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.Folder
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.Playlist
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.KEY_FOLDER
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.R
import org.videolan.vlc.gui.HeaderMediaListActivity
import org.videolan.vlc.gui.SecondaryActivity
import org.videolan.vlc.gui.audio.AudioBrowserFragment
import org.videolan.vlc.gui.browser.KEY_MEDIA

object Pins {
    private const val KEY = "rd_pins"
    const val FOLDER = "folder"
    const val VIDEO_FOLDER = "videofolder"
    const val PLAYLIST = "playlist"

    /** A pin: a browser folder (its address), a folder of the Videos page or a playlist (their library ids) */
    data class Pin(val kind: String, val id: String, val title: String)

    private val state = MutableStateFlow<List<Pin>?>(null)

    /** Bumped when Edit Sidebar moves or hides pins (the pins themselves are unchanged) */
    val layout = MutableStateFlow(0)

    /** The pin's key in the sidebar's order and hidden lists (comma-free: the id is URI-encoded) */
    fun key(pin: Pin) = "pin:" + pin.kind + ":" + Uri.encode(pin.id)

    fun pins(context: Context): StateFlow<List<Pin>?> {
        if (state.value == null) state.value = load(context)
        return state
    }

    fun list(context: Context): List<Pin> = pins(context).value ?: emptyList()

    private fun load(context: Context): List<Pin> = try {
        val array = JSONArray(Settings.getInstance(context).getString(KEY, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }.map { Pin(it.getString("kind"), it.getString("id"), it.optString("title")) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun store(context: Context, pins: List<Pin>) {
        Settings.getInstance(context).putSingle(KEY, JSONArray().apply {
            pins.forEach { put(JSONObject().put("kind", it.kind).put("id", it.id).put("title", it.title)) }
        }.toString())
        state.value = pins
    }

    /** The pin of a library item or a browser folder, null when it cannot be pinned */
    fun of(item: MediaLibraryItem?): Pin? = when (item) {
        is Playlist -> Pin(PLAYLIST, item.id.toString(), item.title)
        is Folder -> Pin(VIDEO_FOLDER, item.id.toString(), item.title)
        is MediaWrapper -> if (item.type == MediaWrapper.TYPE_DIR && item.uri != null) Pin(FOLDER, item.uri.toString(), item.title) else null
        else -> null
    }

    fun isPinned(context: Context, pin: Pin) = list(context).any { it.kind == pin.kind && it.id == pin.id }

    fun pin(context: Context, pin: Pin) {
        if (isPinned(context, pin)) return
        // pinned again: shown, at the end, whatever the old pin's place was
        org.videolan.vlc.gui.livetv.TvNavigation.forget(context, key(pin))
        store(context, list(context) + pin)
    }

    fun unpin(context: Context, pin: Pin) = store(context, list(context).filterNot { it.kind == pin.kind && it.id == pin.id })

    /** The menu entry of an item: Pin or Unpin (null: not an item to pin) */
    fun option(context: Context, item: MediaLibraryItem?): ContextOption? {
        val pin = of(item) ?: return null
        return if (isPinned(context, pin)) ContextOption.CTX_UNPIN else ContextOption.CTX_PIN
    }

    /** Does what [option] of an item's menu asks; true when it was Pin or Unpin */
    fun handle(context: Context, option: ContextOption, item: MediaLibraryItem?): Boolean {
        val pin = of(item) ?: return false
        when (option) {
            ContextOption.CTX_PIN -> pin(context, pin)
            ContextOption.CTX_UNPIN -> unpin(context, pin)
            else -> return false
        }
        return true
    }

    /** The texts of the menu: the sidebar's when the window has it, Home's else */
    fun label(context: Context, pinned: Boolean): String {
        val sidebar = context.resources.getBoolean(R.bool.rd_tablet_sidebar)
        return context.getString(when {
            sidebar && pinned -> R.string.rd_unpin_from_sidebar
            sidebar -> R.string.rd_pin_to_sidebar
            pinned -> R.string.rd_unpin_from_home
            else -> R.string.rd_pin_to_home
        })
    }

    fun icon(pin: Pin) = if (pin.kind == PLAYLIST) R.drawable.rd_ic_playlists else R.drawable.rd_ic_folder

    /** Opens a pinned folder or playlist; one that is gone says so */
    fun open(activity: FragmentActivity, pin: Pin) {
        activity.lifecycleScope.launch {
            val intent = when (pin.kind) {
                FOLDER -> {
                    val media = MLServiceLocator.getAbstractMediaWrapper(Uri.parse(pin.id)).apply {
                        type = MediaWrapper.TYPE_DIR
                        title = pin.title
                    }
                    Intent(activity, SecondaryActivity::class.java).putExtra(KEY_MEDIA, media).putExtra("fragment", SecondaryActivity.FILE_BROWSER)
                }
                VIDEO_FOLDER -> withContext(Dispatchers.IO) {
                    Medialibrary.getInstance().takeIf { it.isInitiated }?.getFolder(Folder.TYPE_FOLDER_VIDEO, pin.id.toLong())
                }?.let { folder ->
                    Intent(activity, SecondaryActivity::class.java).putExtra(SecondaryActivity.KEY_FRAGMENT, SecondaryActivity.VIDEO_GROUP_LIST).putExtra(KEY_FOLDER, folder)
                }
                PLAYLIST -> withContext(Dispatchers.IO) {
                    Medialibrary.getInstance().takeIf { it.isInitiated }?.getPlaylist(pin.id.toLong(), false, false)
                }?.let { playlist ->
                    Intent(activity, HeaderMediaListActivity::class.java).putExtra(AudioBrowserFragment.TAG_ITEM, playlist)
                }
                else -> null
            }
            if (intent == null) RdToast.makeText(activity, R.string.rd_pinned_missing, android.widget.Toast.LENGTH_SHORT).show()
            else activity.startActivity(intent)
        }
    }
}
