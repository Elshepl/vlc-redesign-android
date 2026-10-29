/*
 * VLC Redesign, casting: how the cast goes, for the cast list, the casting screen and the player
 * (the desktop's RendererManager castStatus / castMessage).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.cast

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.flow.MutableStateFlow
import org.videolan.libvlc.RendererItem
import org.videolan.vlc.R

object CastState {

    enum class Status {
        /** playing here */
        OFF,
        /** the device chosen, not playing yet */
        CONNECTING,
        PLAYING,
        /** stopped there: the casting screen says why, with Try again / Play here */
        FAILED
    }

    enum class Error {
        /** it could not be reached, or stopped answering */
        UNREACHABLE,
        /** it refused the media */
        REFUSED,
        /** it stopped playing by itself (its remote, its own menu) */
        STOPPED,
        /** another phone or app took it */
        TAKEN,
        OTHER
    }

    data class State(
        val status: Status = Status.OFF,
        /** the device's name, as listed */
        val name: String = "",
        /** a screen (else the sound only) */
        val video: Boolean = true,
        val error: Error? = null
    )

    val state = MutableStateFlow(State())
    val live: LiveData<State> by lazy(LazyThreadSafetyMode.NONE) { state.asLiveData() }

    val isCasting get() = state.value.status != Status.OFF

    /** the casting screen's title */
    fun title(context: Context, s: State = state.value): String = when (s.status) {
        Status.FAILED -> context.getString(R.string.rd_cast_not_playing_on, s.name)
        Status.CONNECTING -> context.getString(R.string.rd_cast_connecting_to, s.name)
        else -> context.getString(if (s.video) R.string.rd_cast_playing_on else R.string.rd_cast_sound_plays_on, s.name)
    }

    /** why it failed */
    fun message(context: Context, s: State = state.value): String = when (s.error) {
        Error.UNREACHABLE -> context.getString(R.string.rd_cast_no_answer, s.name)
        Error.REFUSED -> context.getString(R.string.rd_cast_refused, s.name)
        Error.STOPPED -> context.getString(R.string.rd_cast_stopped, s.name)
        Error.TAKEN -> context.getString(R.string.rd_cast_taken, s.name)
        else -> context.getString(R.string.rd_cast_could_not_play, s.name)
    }
}

/** VLC_RENDERER_CAN_VIDEO: a screen, else a speaker (libvlc 3 keeps the flags without a getter) */
fun RendererItem.canVideo(): Boolean = try {
    val f = RendererItem::class.java.getDeclaredField("flags")
    f.isAccessible = true
    (f.getInt(this) and RendererItem.LIBVLC_RENDERER_CAN_VIDEO) != 0
} catch (e: Exception) {
    true
}

/** The same device, found twice (by two discoveries, or under its name and its name with the model) */
fun sameDevice(a: String, b: String): Boolean {
    val x = a.trim()
    val y = b.trim()
    return x.isNotEmpty() && y.isNotEmpty() && (x.equals(y, true) || x.startsWith("$y (", true) || y.startsWith("$x (", true))
}

fun sameDevice(a: RendererItem?, b: RendererItem?): Boolean =
    a != null && b != null && (a == b || (a.type == b.type && sameDevice(a.displayName, b.displayName)))
