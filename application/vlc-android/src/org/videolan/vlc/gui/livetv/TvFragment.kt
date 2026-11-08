/*
 * VLC Redesign, Live TV in the library (the TV entry of the bottom bar and the rail, TvDisplay.qml): the
 * channels of the user's sources by group, with what is on and what is next, or the guide (channels by time),
 * or the films and series of an Xtream Codes account. The header: Channels | Guide (| Films | Series) and the
 * sources; the toolbar's search filters the channels (name or number), its List / Grid switches the view.
 * Only opened while the add-on is on.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.livetv

import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.gui.BaseFragment
import org.videolan.vlc.interfaces.Filterable
import org.videolan.vlc.livetv.KEY_IPTV_ADULT
import org.videolan.vlc.livetv.KEY_IPTV_DESCRIPTIONS
import org.videolan.vlc.livetv.KEY_IPTV_GUIDE_HOURS
import org.videolan.vlc.livetv.KEY_IPTV_LANGUAGES
import org.videolan.vlc.livetv.LiveTv
import org.videolan.vlc.livetv.M3u
import org.videolan.vlc.livetv.TvChannel
import org.videolan.vlc.livetv.TvPlayback
import org.videolan.vlc.livetv.TvState
import org.videolan.vlc.livetv.Xtream

class TvFragment : BaseFragment(), Filterable {

    private val settings by lazy { Settings.getInstance(requireContext()) }
    private var view = "channels"
    private var group = ""
    private var search = ""
    private var grid = false

    private lateinit var segments: TvSegmented
    private lateinit var sourcesButton: View
    private lateinit var header: View
    private lateinit var chips: RecyclerView
    private lateinit var chipAdapter: TvChipAdapter
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: TvChannelAdapter
    private lateinit var guideBox: LinearLayout
    private lateinit var guide: TvGuideView
    private lateinit var guideDay: TextView
    private lateinit var guideTools: LinearLayout
    private lateinit var libraryList: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyList: TextView
    private lateinit var bodyBox: FrameLayout
    private lateinit var languageButton: com.google.android.material.button.MaterialButton
    private lateinit var descriptionsChip: FrameLayout
    private var shown: List<TvChannel> = emptyList()

    override fun getTitle() = getString(R.string.rd_nav_tv)
    override fun hasFAB() = false
    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
    override fun onDestroyActionMode(mode: ActionMode) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = requireContext().getSharedPreferences("livetv", 0)
        view = prefs.getString("view", "channels") ?: "channels"
        grid = prefs.getBoolean("grid", false)
        group = savedInstanceState?.getString("group") ?: ""
        LiveTv.init(requireContext())
        LiveTv.start()
        TvReminders.schedule(requireContext())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("group", group)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.tvColor(R.color.rd_bg_a4))
        }
        // Channels | Guide (| Films | Series), the sources at its end
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        segments = TvSegmented(ctx) { v -> setView(v) }
        head.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(segments, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.tvPx(34f))) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sourcesButton = ctx.tvButton(getString(R.string.rd_tv_sources)) { TvSourcesDialog.show(childFragmentManager, if (LiveTv.state.value.sources.isEmpty()) "link" else "sources") }
        head.addView(sourcesButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(12f) })
        header = head
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(ctx.tvPx(16f), ctx.tvPx(10f), ctx.tvPx(16f), 0) })
        // the groups: All, the favourites, the ones watched last, then the provider's
        chipAdapter = TvChipAdapter(ctx) { g -> group = g; refresh() }
        chips = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = chipAdapter
            clipToPadding = false
            setPadding(ctx.tvPx(16f), 0, ctx.tvPx(16f), 0)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.tvPx(32f)).apply { topMargin = ctx.tvPx(14f) })
        // what the add-on is doing (a guide of 30 MB takes a while)
        status = ctx.tvText("", 12f, ctx.tvColor(R.color.rd_fg_a3))
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(ctx.tvPx(20f), ctx.tvPx(10f), ctx.tvPx(20f), 0) })
        bodyBox = FrameLayout(ctx)
        root.addView(bodyBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ctx.tvPx(10f) })
        // the channels
        adapter = TvChannelAdapter(ctx, { c -> play(c) }, { c, v -> menu(c, v) })
        list = RecyclerView(ctx).apply {
            this.adapter = this@TvFragment.adapter
            clipToPadding = false
            setPadding(ctx.tvPx(12f), 0, ctx.tvPx(12f), ctx.tvPx(20f))
            setHasFixedSize(true)
            isVerticalScrollBarEnabled = true
        }
        bodyBox.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // the guide: the day and the way through the days, the languages, the descriptions
        guideBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        guideTools = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        guide = TvGuideView(ctx, { c -> play(c) }, { c, e -> TvProgrammeDialog.show(requireContext(), c, e) { guide.invalidate() } })
        guideDay = ctx.tvText("", 16f, ctx.tvColor(R.color.rd_fg_a1))
        // the day of the middle of what shows (a phone shows half of the hours)
        guide.onTimeChanged = { t -> val d = TvTexts.day(ctx, t + (guide.shownHours * 1800).toLong()); if (guideDay.text != d) guideDay.text = d }
        guideTools.addView(ctx.tvButton(getString(R.string.rd_tv_now), accent = true) { guide.jumpToNow() })
        guideTools.addView(ctx.tvButton(getString(R.string.rd_tv_earlier)) { guide.earlier() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(8f) })
        guideTools.addView(ctx.tvButton(getString(R.string.rd_tv_later)) { guide.later() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(8f) })
        guideTools.addView(guideDay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(12f); marginEnd = ctx.tvPx(12f) })
        // the languages of the guides (when they give several) and the descriptions, as Settings > Live TV
        languageButton = ctx.tvButton(getString(R.string.rd_tv_language)) { v -> chooseLanguage(v) }
        guideTools.addView(languageButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.tvPx(8f) })
        descriptionsChip = FrameLayout(ctx)
        guideTools.addView(descriptionsChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.tvPx(32f)))
        guideBox.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(guideTools) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(ctx.tvPx(16f), 0, ctx.tvPx(16f), 0) })
        guideBox.addView(guide, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(ctx.tvPx(12f), ctx.tvPx(10f), ctx.tvPx(12f), 0) })
        bodyBox.addView(guideBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // films and series
        libraryList = RecyclerView(ctx).apply { clipToPadding = false; setPadding(ctx.tvPx(12f), 0, ctx.tvPx(12f), ctx.tvPx(20f)) }
        bodyBox.addView(libraryList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        emptyList = ctx.tvText("", 16f, ctx.tvColor(R.color.rd_fg_a3), lines = 3).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        bodyBox.addView(emptyList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { setMargins(ctx.tvPx(24f), 0, ctx.tvPx(24f), 0) })
        // no source yet: what Live TV is, and the way to add one
        empty = buildEmpty()
        root.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0f))
        return root
    }

    private fun buildEmpty(): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ctx.tvPx(24f), 0, ctx.tvPx(24f), 0) }
        box.addView(ImageView(ctx).apply { setImageResource(R.drawable.rd_ic_duo_tv) }, LinearLayout.LayoutParams(ctx.tvPx(48f), ctx.tvPx(48f)))
        box.addView(ctx.tvText(getString(R.string.rd_tv_watch_in_vlc), 18f, ctx.tvColor(R.color.rd_fg_2), medium = true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(16f) })
        box.addView(ctx.tvText(getString(R.string.rd_tv_empty_help), 14f, ctx.tvColor(R.color.rd_fg_a3), lines = 0).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setLineSpacing(0f, 1.3f); maxWidth = ctx.tvPx(520f) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(16f) })
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        buttons.addView(ctx.tvButton(getString(R.string.rd_tv_add_link), accent = true) { TvSourcesDialog.show(childFragmentManager, "link") })
        buttons.addView(ctx.tvButton(getString(R.string.rd_tv_browse_catalog)) { TvSourcesDialog.show(childFragmentManager, "catalog") },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(12f) })
        box.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(16f) })
        return box
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyLayout()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { LiveTv.state.collect { refresh(it) } }
                launch { LiveTv.favorites.collect { refresh() } }
                launch { TvPlayback.current.collect { adapter.tick(); guide.invalidate() } }
                launch { LiveTv.messages.collect { m -> m?.let { activity?.let { a -> org.videolan.vlc.gui.helpers.UiTools.snacker(a, it.second) } } } }
                // the clock: what is on and its progress
                launch { while (true) { delay(30_000); adapter.tick(); guide.invalidate() } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // the add-on switched off while the page was away: back home
        if (!LiveTv.enabled) (activity as? org.videolan.vlc.gui.MainActivity)?.let { TvNavigation.goHome(it) }
        guide.hours = settings.getInt(KEY_IPTV_GUIDE_HOURS, 3)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    private fun applyLayout() {
        val ctx = requireContext()
        val widthDp = resources.configuration.screenWidthDp
        adapter.wide = widthDp >= 600
        if (grid) {
            val span = maxOf(2, (widthDp - 24) / 180)
            list.layoutManager = GridLayoutManager(ctx, span)
        } else list.layoutManager = LinearLayoutManager(ctx)
        adapter.grid = grid
        libraryList.layoutManager = GridLayoutManager(ctx, maxOf(2, (widthDp - 24) / 140))
    }

    private fun setView(v: String) {
        view = v
        requireContext().getSharedPreferences("livetv", 0).edit { putString("view", v) }
        refresh()
        if (v == "movies" || v == "series") loadLibrary()
        activity?.invalidateOptionsMenu()
    }

    private fun play(c: TvChannel) {
        TvPlayback.play(requireContext(), c.key, shown.map { it.key })
    }

    private fun menu(c: TvChannel, anchor: View) {
        val ctx = requireContext()
        val now = c.now(System.currentTimeMillis() / 1000)
        val m = PopupMenu(ctx, anchor)
        m.menu.add(0, 1, 1, R.string.rd_tv_watch)
        if (c.catchupDays > 0 && now != null) m.menu.add(0, 2, 2, R.string.rd_tv_watch_from_start)
        m.menu.add(0, 3, 3, if (LiveTv.isFavorite(c.key)) R.string.rd_tv_unfavorite else R.string.rd_tv_favorite)
        m.menu.add(0, 4, 4, R.string.rd_tv_show_in_guide)
        m.menu.add(0, 5, 5, R.string.rd_tv_whats_on).isEnabled = now != null
        // its id in the guide, given by the user (it wins over the matching), and forgotten
        val given = LiveTv.guideIds()[c.key]
        m.menu.add(0, 6, 6, R.string.rd_tv_its_guide_id)
        if (!given.isNullOrEmpty()) m.menu.add(0, 7, 7, R.string.rd_tv_forget_guide_id)
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> play(c)
                2 -> now?.let { TvPlayback.playCatchup(ctx, c.key, it.start, it.stop, it.title) }
                3 -> LiveTv.toggleFavorite(c.key)
                4 -> { setView("guide"); guide.post { guide.showChannel(c.key) } }
                5 -> now?.let { TvProgrammeDialog.show(ctx, c, it) }
                6 -> editGuideId(c)
                7 -> LiveTv.setGuideId(c.key, "")
            }
            true
        }
        m.show()
    }

    /** "The id of BBC One in the guide": the channel's id as its guide names it ("BBCOne.uk") */
    private fun editGuideId(c: TvChannel) {
        val ctx = requireContext()
        val field = ctx.tvField(c.channel.tvgId.ifEmpty { "BBCOne.uk" }).apply {
            setText(LiveTv.guideIds()[c.key] ?: "")
            textDirection = View.TEXT_DIRECTION_LTR
        }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(ctx.tvPx(20f), ctx.tvPx(8f), ctx.tvPx(20f), 0)
            addView(ctx.tvText(getString(R.string.rd_tv_guide_id_of, c.name), 13f, ctx.tvColor(R.color.rd_fg_a3), lines = 0))
            addView(field)
        }
        val d = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.rd_tv_its_guide_id)
            .setView(box)
            .setPositiveButton(R.string.rd_tv_save) { _, _ -> LiveTv.setGuideId(c.key, field.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        org.videolan.vlc.gui.helpers.RdTvFocusRing.attach(d.window)
    }

    private fun refresh(state: TvState = LiveTv.state.value) {
        if (!::list.isInitialized) return
        val ctx = requireContext()
        val hasSources = state.sources.isNotEmpty()
        val films = state.sources.any { it.enabled && it.vod }
        val series = state.sources.any { it.enabled && it.series }
        if ((view == "movies" && !films) || (view == "series" && !series)) view = "channels"
        segments.setOptions(listOfNotNull(getString(R.string.rd_tv_channels) to "channels", getString(R.string.rd_tv_guide) to "guide",
            if (films) getString(R.string.rd_tv_films) to "movies" else null, if (series) getString(R.string.rd_tv_series) to "series" else null))
        segments.value = view
        header.visibility = if (hasSources) View.VISIBLE else View.GONE
        empty.visibility = if (hasSources) View.GONE else View.VISIBLE
        bodyBox.visibility = if (hasSources) View.VISIBLE else View.GONE
        (empty.layoutParams as LinearLayout.LayoutParams).let { it.weight = if (hasSources) 0f else 1f; empty.layoutParams = it }
        val library = view == "movies" || view == "series"
        chips.visibility = if (hasSources && !library) View.VISIBLE else View.GONE
        // the channels shown: the group, the adult ones only when allowed, the search
        val adult = settings.getBoolean(KEY_IPTV_ADULT, false)
        val all = state.channels.filter { adult || !it.channel.adult }
        val favorites = LiveTv.favorites.value
        val groups = LinkedHashMap<String, Int>()
        for (c in all) { val g = M3u.displayGroup(c.group); if (g.isNotEmpty()) groups[g] = (groups[g] ?: 0) + 1 }
        if (group.isNotEmpty() && group != "favorites" && group != "recent" && !groups.containsKey(group)) group = ""
        chipAdapter.submit(listOf(TvChipAdapter.Chip("", getString(R.string.rd_tv_all), all.size),
            TvChipAdapter.Chip("favorites", getString(R.string.rd_tv_favorites), favorites.size),
            TvChipAdapter.Chip("recent", getString(R.string.rd_tv_recent), -1)) + groups.map { TvChipAdapter.Chip(it.key, it.key, it.value) }, group)
        var list = when (group) {
            "" -> all
            "favorites" -> favorites.mapNotNull { k -> all.firstOrNull { it.key == k } }
            "recent" -> LiveTv.recent.mapNotNull { k -> all.firstOrNull { it.key == k } }
            else -> all.filter { M3u.displayGroup(it.group) == group }
        }
        val q = search.trim().lowercase()
        if (q.isNotEmpty()) list = list.filter { it.name.lowercase().contains(q) || it.number.toString() == q }
        shown = list
        adapter.submit(list)
        guide.channels = list
        // the status of the sources
        val busy = state.sources.firstOrNull { it.busy }
        // (one turned off says nothing)
        val failed = state.sources.firstOrNull { it.enabled && it.error.isNotEmpty() && it.channels == 0 }
        status.text = when {
            busy != null -> if (busy.stage == "guide") getString(R.string.rd_tv_updating_guide_of, busy.name, "%.1f".format(busy.received / 1048576.0))
                            else getString(R.string.rd_tv_updating_channels_of, busy.name)
            failed != null -> getString(R.string.rd_tv_could_not_load, failed.name, failed.error)
            else -> ""
        }
        status.setTextColor(ctx.tvColor(if (busy != null) R.color.rd_fg_a3 else R.color.rd_warning))
        status.visibility = if (hasSources && status.text.isNotEmpty()) View.VISIBLE else View.GONE
        this.list.visibility = if (hasSources && view == "channels" && list.isNotEmpty()) View.VISIBLE else View.GONE
        guideBox.visibility = if (hasSources && view == "guide") View.VISIBLE else View.GONE
        libraryList.visibility = if (hasSources && library) View.VISIBLE else View.GONE
        emptyList.text = when {
            q.isNotEmpty() -> getString(R.string.rd_tv_no_match)
            group == "favorites" -> getString(R.string.rd_tv_no_favorite)
            group == "recent" -> getString(R.string.rd_tv_no_recent)
            !state.loaded || state.sources.any { it.busy } -> getString(R.string.rd_tv_loading_channels)
            else -> getString(R.string.rd_tv_no_channels)
        }
        emptyList.visibility = if (hasSources && view == "channels" && list.isEmpty()) View.VISIBLE else View.GONE
        sourcesButton.visibility = if (hasSources) View.VISIBLE else View.GONE
        // the guide's tools
        val wanted = LiveTv.guideLanguages()
        languageButton.visibility = if (state.languages.size > 1 || wanted.isNotEmpty()) View.VISIBLE else View.GONE
        languageButton.text = if (wanted.isEmpty()) getString(R.string.rd_tv_language) + ": " + getString(R.string.rd_tv_all)
                              else getString(R.string.rd_tv_language) + ": " + wanted.joinToString(", ") { languageName(it) }
        descriptionsChip.removeAllViews()
        val on = settings.getBoolean(KEY_IPTV_DESCRIPTIONS, true)
        descriptionsChip.addView(ctx.tvChip(getString(R.string.rd_tv_descriptions), on) {
            settings.edit { putBoolean(KEY_IPTV_DESCRIPTIONS, !on) }
            refresh()
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun languageName(code: String) = java.util.Locale(code).getDisplayLanguage(java.util.Locale.getDefault()).ifEmpty { code }

    /** Keeps only the programmes in a language when the guides give several (the guides are read again). */
    private fun chooseLanguage(anchor: View) {
        val langs = LiveTv.state.value.languages.sorted()
        val m = PopupMenu(requireContext(), anchor)
        m.menu.add(0, 0, 0, getString(R.string.rd_tv_all))
        langs.forEachIndexed { i, l -> m.menu.add(0, i + 1, i + 1, languageName(l) + " (" + l + ")") }
        m.setOnMenuItemClickListener { item ->
            settings.edit { putString(KEY_IPTV_LANGUAGES, if (item.itemId == 0) "" else langs[item.itemId - 1]) }
            LiveTv.reloadGuides()
            refresh()
            true
        }
        m.show()
    }

    /* Films and series of the first account that has them */

    private var libraryKind = ""

    private fun loadLibrary() {
        val kind = if (view == "series") "series" else "vod"
        val source = LiveTv.state.value.sources.firstOrNull { it.enabled && if (kind == "vod") it.vod else it.series } ?: return
        if (libraryKind == kind + source.id) return
        libraryKind = kind + source.id
        val ctx = requireContext()
        libraryList.adapter = TvLibraryAdapter(ctx, emptyList()) {}
        LiveTv.library(source.id, kind) { items ->
            val adult = settings.getBoolean(KEY_IPTV_ADULT, false)
            val shownItems = items.filter { adult || !it.adult }
            libraryList.post {
                if (!isAdded) return@post
                libraryList.adapter = TvLibraryAdapter(ctx, shownItems) { item -> openItem(source.id, kind, item) }
                emptyList.text = getString(R.string.rd_tv_nothing_here)
                emptyList.visibility = if (shownItems.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openItem(sourceId: String, kind: String, item: Xtream.VodItem) {
        val ctx = requireContext()
        if (kind == "vod") {
            TvPlayback.playUrl(ctx, item.url, item.name)
            return
        }
        LiveTv.seriesInfo(sourceId, item.id) { info ->
            libraryList.post {
                if (!isAdded || info.episodes.isEmpty()) return@post
                val names = info.episodes.map { getString(R.string.rd_tv_season_episode, it.season.toString(), it.number.toString()) + "  " + it.title }.toTypedArray()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle(item.name)
                    .setItems(names) { _, i -> TvPlayback.playUrl(ctx, info.episodes[i].url, item.name + " – " + info.episodes[i].title) }
                    .setNegativeButton(R.string.rd_tv_close, null).show()
            }
        }
    }

    /* The toolbar: the search, List / Grid */

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        val channels = view == "channels" && LiveTv.state.value.sources.isNotEmpty()
        menu.findItem(R.id.ml_menu_display_grid)?.isVisible = channels && !grid
        menu.findItem(R.id.ml_menu_display_list)?.isVisible = channels && grid
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.ml_menu_display_grid, R.id.ml_menu_display_list -> {
                grid = item.itemId == R.id.ml_menu_display_grid
                requireContext().getSharedPreferences("livetv", 0).edit { putBoolean("grid", grid) }
                applyLayout()
                activity?.invalidateOptionsMenu()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun getFilterQuery() = search.ifEmpty { null }
    override fun enableSearchOption() = LiveTv.state.value.sources.isNotEmpty()
    override fun filter(query: String) { search = query; refresh() }
    override fun restoreList() { search = ""; refresh() }
    override fun setSearchVisibility(visible: Boolean) {}
    override fun allowedToExpand() = true
}

/** The posters of the films or series of an account. */
internal class TvLibraryAdapter(private val context: android.content.Context, private val items: List<Xtream.VodItem>, private val onClick: (Xtream.VodItem) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.tvPx(6f), context.tvPx(6f), context.tvPx(6f), context.tvPx(10f))
            isClickable = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(context.tvColor(R.color.rd_tv_logo_well), context.tvDp(6f)); clipToOutline = true }
        box.addView(poster, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.tvPx(180f)))
        box.addView(context.tvText("", 13f, context.tvColor(R.color.rd_fg_a1), lines = 2), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.tvPx(8f) })
        return object : RecyclerView.ViewHolder(box) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val box = holder.itemView as LinearLayout
        val poster = box.getChildAt(0) as ImageView
        (box.getChildAt(1) as TextView).text = item.name
        box.contentDescription = item.name
        org.videolan.vlc.livetv.TvLogos.load(context, item.poster, poster, context.tvPx(200f)) {}
        box.setOnClickListener { onClick(item) }
    }
}
