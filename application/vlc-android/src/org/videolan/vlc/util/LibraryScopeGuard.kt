/*
 * LibraryScopeGuard.kt
 *
 * Debug builds are run on phones that are in daily use: their media library must only ever index a folder
 * that was added on purpose, never a whole storage. Upstream VLC adds the storage root as an entry point
 * whenever the library database is new (a new install, cleared data, a database reset after a model
 * upgrade) and the onboarding did not preselect folders. In a debug build this guard turns that into
 * "no entry point", refuses any storage root or top-level folder as an entry point, and removes such
 * entry points from an existing database before the start-up reload can scan them.
 */
package org.videolan.vlc.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AlertDialog
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.AndroidDevices
import org.videolan.tools.removeFileScheme
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R

object LibraryScopeGuard {
    private const val TAG = "VLC/LibraryScope"

    /** Release builds keep the upstream behaviour. */
    val enforced: Boolean get() = BuildConfig.DEBUG

    private fun normalize(path: String): String {
        var p = Uri.decode(path.removeFileScheme()).trim()
        while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }

    private fun roots(): List<String> {
        val list = mutableListOf(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY, "/sdcard", "/storage/self/primary", "/mnt/sdcard")
        try {
            list.addAll(AndroidDevices.externalStorageDirectories)
        } catch (_: Exception) {
        }
        return list.map { normalize(it) }.filter { it.length > 1 }
    }

    /**
     * True when [path] is a storage root, a folder above one, or a folder directly inside one
     * (Download, DCIM, Music, ...): the folders whose scan would index someone's whole media.
     */
    fun isTooWide(path: String): Boolean {
        val p = normalize(path)
        if (p.isEmpty() || p == "/") return true
        if (!p.startsWith("/")) return false // a network share or another scheme: not a local storage
        val root = roots().filter { p == it || p.startsWith("$it/") }.maxByOrNull { it.length }
        val rest = if (root != null) p.removePrefix(root) else {
            // unknown mount: treat /storage/<volume> and /mnt/<volume> (or any two first segments) as the root
            val segs = p.split('/').filter { it.isNotEmpty() }
            if (segs.size <= 2) return true
            "/" + segs.drop(2).joinToString("/")
        }
        val depth = rest.split('/').count { it.isNotEmpty() }
        return depth < 2
    }

    /** Whether [path] may become an entry point of the media library. */
    fun allowed(path: String): Boolean {
        if (!enforced) return true
        val tooWide = isTooWide(path)
        if (tooWide) Log.w(TAG, "Debug build: refused to index a whole storage or a top-level folder: $path")
        return !tooWide
    }

    /**
     * A folder chosen by hand (the folder picker, "Add to media library"): when a debug build refuses it, say so in a
     * dialog instead of a tick or a "folder added" that the media library then silently ignores. True when refused.
     */
    fun refuseWithMessage(context: Context, path: String): Boolean {
        if (!enforced || !isTooWide(path)) return false
        Log.w(TAG, "Debug build: the folder picker refused $path")
        AlertDialog.Builder(context)
                .setTitle(R.string.rd_debug_scope_title)
                .setMessage(R.string.rd_debug_scope_message)
                .setPositiveButton(R.string.ok, null)
                .show()
        return true
    }

    /** Debug builds: remove the entry points that would scan a whole storage before anything reloads them. */
    fun pruneEntryPoints(ml: Medialibrary) {
        if (!enforced) return
        // banned folders (Alarms, Ringtones, ...) are stored like roots: they are never scanned, leave them
        val banned = try { ml.bannedFolders().map { normalize(it) }.toSet() } catch (e: Exception) { emptySet() }
        val folders = try { ml.foldersList } catch (e: Exception) { null }?.filterNot { normalize(it) in banned } ?: return
        val wide = folders.filter { isTooWide(it) }
        if (wide.isEmpty()) return
        // removeFolder() also removes the entry points inside the one removed: add the narrow ones back
        val keep = folders.filterNot { isTooWide(it) }
        for (folder in wide) {
            Log.w(TAG, "Debug build: removing the entry point $folder")
            ml.removeFolder(folder)
        }
        val left = try { ml.foldersList.toSet() } catch (e: Exception) { emptySet() }
        for (folder in keep) if (folder !in left) ml.discover(folder)
    }
}
