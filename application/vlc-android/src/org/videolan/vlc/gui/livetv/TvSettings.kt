/*
 * VLC Redesign, Live TV in the settings (SettingsTv.qml): the sources, each with what it holds and Update,
 * above the options of the add-on; the add-on switched on or off in General (its page, its entry of the bottom
 * bar and its keys follow).
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.livetv

import android.app.Activity
import android.content.Context
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import kotlinx.coroutines.launch
import org.videolan.vlc.R
import org.videolan.vlc.livetv.KEY_IPTV_ENABLED
import org.videolan.vlc.livetv.KEY_IPTV_GUIDE_DAYS
import org.videolan.vlc.livetv.KEY_IPTV_LANGUAGES
import org.videolan.vlc.livetv.LiveTv
import org.videolan.vlc.livetv.SourceStatus

/** A source of Settings > Live TV: its name, what it holds, Update at its end. */
class TvSourcePreference(context: Context, private val onUpdate: () -> Unit) : Preference(context) {
    init {
        widgetLayoutResource = R.layout.rd_pref_action
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.rd_action) as? TextView)?.let {
            it.text = context.getString(R.string.rd_tv_update)
            it.setOnClickListener { onUpdate() }
        }
    }
}

object TvSettings {

    fun setup(page: PreferenceFragmentCompat) {
        val ctx = page.requireContext()
        LiveTv.init(ctx)
        LiveTv.start()
        page.lifecycleScope.launch {
            page.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveTv.state.collect { bind(page, it.sources, it.languages) }
            }
        }
    }

    private fun bind(page: PreferenceFragmentCompat, sources: List<SourceStatus>, languages: Set<String>) {
        val ctx = page.context ?: return
        val group = page.findPreference<PreferenceCategory>("iptv_sources") ?: return
        // the rows of the sources, before the buttons
        for (i in group.preferenceCount - 1 downTo 0) if (group.getPreference(i) is TvSourcePreference) group.removePreference(group.getPreference(i))
        sources.forEachIndexed { i, s ->
            group.addPreference(TvSourcePreference(ctx) { LiveTv.refresh(listOf(s.id)) }.apply {
                key = "iptv_source_${s.id}"
                order = i
                title = s.name
                summary = summary(ctx, s)
                isIconSpaceReserved = false
            })
        }
        page.findPreference<Preference>("iptv_manage")?.apply {
            order = 100
            title = ctx.getString(if (sources.isNotEmpty()) R.string.rd_tv_manage_help else R.string.rd_tv_no_source)
        }
        page.findPreference<Preference>("iptv_catalog")?.order = 101
        page.findPreference<Preference>("iptv_languages_found")?.apply {
            order = 102
            isVisible = languages.isNotEmpty()
            title = ctx.getString(R.string.rd_tv_guide_languages_found, languages.sorted().joinToString(", "))
        }
    }

    private fun summary(ctx: Context, s: SourceStatus): String {
        val r = ctx.resources
        if (s.busy) return ctx.getString(R.string.rd_tv_updating)
        if (s.error.isNotEmpty() && s.channels == 0) return ctx.getString(R.string.rd_tv_could_not_load, s.name, s.error)
        var t = r.getQuantityString(R.plurals.rd_tv_n_channels, s.channels, s.channels)
        if (s.withGuide > 0) t += ", " + r.getQuantityString(R.plurals.rd_tv_n_with_guide, s.withGuide, s.withGuide)
        if (s.catchupDays > 0) t += ", " + r.getQuantityString(R.plurals.rd_tv_catchup_days_short, s.catchupDays, s.catchupDays)
        return t
    }

    /** a row of the page tapped: true when it was one of Live TV's */
    fun click(page: PreferenceFragmentCompat, key: String): Boolean {
        val fm = page.childFragmentManager
        return when (key) {
            "iptv_manage" -> { TvSourcesDialog.show(fm, if (LiveTv.state.value.sources.isEmpty()) "link" else "sources"); true }
            "iptv_catalog" -> { TvSourcesDialog.show(fm, "catalog"); true }
            else -> key.startsWith("iptv_source_") && run { TvSourcesDialog.show(fm, "sources"); true }
        }
    }

    /** an option changed: the add-on on or off at once, the guides read again for their days and languages */
    fun changed(activity: Activity, key: String) {
        when (key) {
            KEY_IPTV_ENABLED -> {
                LiveTv.init(activity)
                if (LiveTv.enabled) LiveTv.start() else LiveTv.stop()
                // its page in the sidebar follows
                activity.recreate()
            }
            KEY_IPTV_GUIDE_DAYS, KEY_IPTV_LANGUAGES -> LiveTv.reloadGuides()
        }
    }
}
