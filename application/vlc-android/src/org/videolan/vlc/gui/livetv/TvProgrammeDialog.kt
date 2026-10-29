/*
 * VLC Redesign, Live TV: a programme of the guide (TvProgrammeDialog.qml, the dialogs of the design as Folder
 * information): its title and episode, the channel and the time, its category and description; Watch it (on
 * now, or from the archive), from its start, or a reminder for one to come.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.livetv

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.videolan.vlc.R
import org.videolan.vlc.livetv.Entry
import org.videolan.vlc.livetv.LiveTv
import org.videolan.vlc.livetv.TvChannel
import org.videolan.vlc.livetv.TvPlayback

internal object TvProgrammeDialog {

    fun show(context: Context, c: TvChannel, e: Entry, onChanged: () -> Unit = {}) {
        val n = System.currentTimeMillis() / 1000
        val onNow = e.start <= n && n < e.stop
        val past = e.stop <= n
        val archived = LiveTv.isArchived(c, e, n)
        val reminded = LiveTv.hasReminder(c.key, e.start)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.tvPx(24f), context.tvPx(8f), context.tvPx(24f), context.tvPx(8f))
        }
        body.addView(context.tvText(e.title, 18f, context.tvColor(R.color.rd_fg_1), medium = true, lines = 3))
        val episode = listOf(e.episode, e.subtitle).filter { it.isNotEmpty() }.joinToString(" · ")
        if (episode.isNotEmpty()) body.addView(context.tvText(episode, 14f, context.tvColor(R.color.rd_fg_a2), lines = 2), lp(context, 4f))
        if (past && !archived) body.addView(context.tvText(context.getString(R.string.rd_tv_over_no_archive), 13f, context.tvColor(R.color.rd_fg_a3), lines = 3), lp(context, 8f))
        fun row(label: Int, value: String) {
            if (value.isEmpty()) return
            val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(context.tvText(context.getString(label), 13f, context.tvColor(R.color.rd_fg_a3)), LinearLayout.LayoutParams(context.tvPx(96f), LinearLayout.LayoutParams.WRAP_CONTENT))
            r.addView(context.tvText(value, 13f, context.tvColor(R.color.rd_fg_a1), lines = 2), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            body.addView(r, lp(context, 10f))
        }
        row(R.string.rd_tv_channel, c.name)
        row(R.string.rd_tv_time, TvTexts.day(context, e.start) + ", " + TvTexts.span(context, e.start, e.stop))
        if (onNow) row(R.string.rd_tv_time_left, TvTexts.duration(context, e.stop - n))
        row(R.string.rd_tv_category, e.category)
        row(R.string.rd_tv_catchup, if (c.catchupDays > 0) context.resources.getQuantityString(R.plurals.rd_tv_past_days, c.catchupDays, c.catchupDays) else context.getString(R.string.rd_tv_none))
        val desc = LiveTv.description(c, e)
        if (desc.isNotEmpty()) body.addView(context.tvText(desc, 14f, context.tvColor(R.color.rd_fg_a2), lines = 0).apply { setLineSpacing(0f, 1.3f) }, lp(context, 14f))
        val scroll = ScrollView(context).apply { addView(body) }
        val b = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rd_tv_programme)
            .setView(scroll)
            .setNegativeButton(R.string.rd_tv_close, null)
        when {
            onNow -> b.setPositiveButton(R.string.rd_tv_watch) { _, _ -> TvPlayback.play(context, c.key) }
            past && archived -> b.setPositiveButton(R.string.rd_tv_watch) { _, _ -> TvPlayback.playCatchup(context, c.key, e.start, e.stop, e.title) }
            !past -> b.setPositiveButton(if (reminded) R.string.rd_tv_remove_reminder else R.string.rd_tv_remind_me) { _, _ ->
                LiveTv.toggleReminder(c.key, e.start, e.title)
                TvReminders.schedule(context)
                onChanged()
            }
        }
        if (onNow && c.catchupDays > 0) b.setNeutralButton(R.string.rd_tv_watch_from_the_start) { _, _ -> TvPlayback.playCatchup(context, c.key, e.start, e.stop, e.title) }
        b.show()
    }

    private fun lp(context: Context, top: Float) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.tvPx(top) }
}

/** The reminders of the guide: at the start of a programme, a message with the channel to watch (in the app). */
internal object TvReminders {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pending: Runnable? = null

    fun schedule(context: Context) {
        pending?.let { handler.removeCallbacks(it) }
        val app = context.applicationContext
        val next = LiveTv.reminders.filter { it.start > System.currentTimeMillis() / 1000 - 60 }.minByOrNull { it.start } ?: return
        val r = Runnable {
            val c = LiveTv.state.value.byKey[next.key]
            LiveTv.toggleReminder(next.key, next.start, next.title)
            if (c != null) LiveTv.message(app.getString(R.string.rd_tv_reminder_due, next.title, c.name))
            schedule(app)
        }
        pending = r
        handler.postDelayed(r, maxOf(0L, next.start * 1000 - System.currentTimeMillis()))
    }
}
