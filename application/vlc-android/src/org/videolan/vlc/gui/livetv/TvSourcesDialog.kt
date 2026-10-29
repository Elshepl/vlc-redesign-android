/*
 * VLC Redesign, Live TV: the TV sources (TvSourceDialog.qml, the dialogs of the design as Open Network Stream):
 * the ones added with what each can do; a link of the user's (an M3U playlist or file, an Xtream Codes login, a
 * guide); or one of the catalog of free, legal channel lists, added in one tap with its guide.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.livetv

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.videolan.vlc.R
import org.videolan.vlc.livetv.LiveTv
import org.videolan.vlc.livetv.SourceConfig
import org.videolan.vlc.livetv.SourceStatus
import java.util.Locale

class TvSourcesDialog : DialogFragment() {

    private var page = "sources"
    private var kind = "m3u"
    private var editing = ""
    private var error = ""

    private lateinit var tabs: TvSegmented
    private lateinit var body: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var scroll: ScrollView

    // the fields of the link page, kept across its rebuilds
    private var fName = ""
    private var fUrl = ""
    private var fServer = ""
    private var fUser = ""
    private var fPassword = ""
    private var fEpg = ""
    private var fFor = ""

    // the catalog's filters
    private var catalog: List<JSONObject>? = null
    private var cSearch = ""
    private var cLanguage = ""
    private var cCountry = ""
    private var cCategory = ""
    private var cSort = "name"
    private var cFeature = ""
    private var catalogShown = 40
    private var catalogLoading = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { }
            fUrl = uri.toString()
            if (fName.isEmpty()) fName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
            build()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getString("page") ?: arguments?.getString("page") ?: "sources"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = Dialog(requireContext(), R.style.Theme_AppCompat_DayNight_Dialog).apply {
        window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { w ->
            val dm = resources.displayMetrics
            val width = minOf(dm.widthPixels - requireContext().tvPx(24f), requireContext().tvPx(720f))
            w.setLayout(width, (dm.heightPixels * 0.9f).toInt())
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.rd_panel_framed)
            setPadding(ctx.tvPx(20f), ctx.tvPx(18f), ctx.tvPx(20f), ctx.tvPx(14f))
        }
        root.addView(ctx.tvText(getString(R.string.rd_tv_tv_sources), 18f, ctx.tvColor(R.color.rd_fg_1), medium = true))
        tabs = TvSegmented(ctx) { v -> page = v; editing = ""; error = ""; build() }
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.tvPx(36f)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(14f) })
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(ctx).apply { isFillViewport = true; addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ctx.tvPx(14f) })
        footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(12f) })
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        build()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveTv.state.collect { if (page == "sources") build() else if (page == "catalog") refreshCatalogList(requireContext()) }
            }
        }
    }

    private fun build() {
        if (!::body.isInitialized) return
        val ctx = context ?: return
        val sources = LiveTv.state.value.sources
        tabs.setOptions(listOf(getString(R.string.rd_tv_your_sources_n, sources.size.toString()) to "sources",
            getString(R.string.rd_tv_add_link) to "link", getString(R.string.rd_tv_catalog) to "catalog"))
        tabs.value = page
        body.removeAllViews()
        footer.removeAllViews()
        when (page) {
            "link" -> buildLink(ctx, sources)
            "catalog" -> buildCatalog(ctx, sources)
            else -> buildSources(ctx, sources)
        }
        footer.addView(ctx.tvButton(getString(R.string.rd_tv_close)) { dismissAllowingStateLoss() }, 0)
    }

    private fun gap(ctx: Context, v: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(v) }

    private fun heading(ctx: Context, text: String, suffix: String = "", description: String = "") {
        val t = ctx.tvText(if (suffix.isEmpty()) text else "$text  $suffix", 14f, ctx.tvColor(R.color.rd_fg_2), medium = true)
        body.addView(t, gap(ctx, 14f))
        if (description.isNotEmpty()) body.addView(ctx.tvText(description, 12f, ctx.tvColor(R.color.rd_fg_a3), lines = 0), gap(ctx, 4f))
    }

    /* ---- the sources added ---- */

    private fun buildSources(ctx: Context, sources: List<SourceStatus>) {
        heading(ctx, getString(R.string.rd_tv_your_tv_sources), description = getString(
            if (sources.isNotEmpty()) R.string.rd_tv_sources_order_help else R.string.rd_tv_no_source_help))
        sources.forEachIndexed { i, s -> body.addView(sourceCard(ctx, s, i, sources.size), gap(ctx, 10f)) }
        footer.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        if (sources.isNotEmpty()) footer.addView(ctx.tvButton(getString(R.string.rd_tv_update_all)) { LiveTv.refresh() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(8f) })
        footer.addView(ctx.tvButton(getString(R.string.rd_tv_add_link), accent = true) { page = "link"; editing = ""; clearFields(); build() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(8f) })
    }

    private fun sourceCard(ctx: Context, s: SourceStatus, index: Int, count: Int): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.rd_card_a3)
            setPadding(ctx.tvPx(14f), ctx.tvPx(12f), ctx.tvPx(14f), ctx.tvPx(12f))
            alpha = if (s.enabled) 1f else 0.6f
        }
        card.addView(ctx.tvText(s.name.ifEmpty { getString(if (s.kind == "xtream") R.string.rd_tv_kind_xtream else R.string.rd_tv_kind_m3u) }, 15f, ctx.tvColor(R.color.rd_fg_1), medium = true))
        val badges = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun badge(v: View) = badges.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.tvPx(6f) })
        badge(ctx.tvBadge(resources.getQuantityString(R.plurals.rd_tv_n_channels, s.channels, s.channels), R.drawable.rd_ic_tv))
        if (s.withGuide > 0) badge(ctx.tvBadge(resources.getQuantityString(R.plurals.rd_tv_guide_n, s.withGuide, s.withGuide), R.drawable.rd_ic_duo_lists))
        if (s.catchupDays > 0) badge(ctx.tvBadge(resources.getQuantityString(R.plurals.rd_tv_catchup_n, s.catchupDays, s.catchupDays), R.drawable.rd_ic_recent))
        if (s.vod) badge(ctx.tvBadge(getString(R.string.rd_tv_films), R.drawable.rd_ic_video))
        if (s.series) badge(ctx.tvBadge(getString(R.string.rd_tv_series), R.drawable.rd_ic_playlists))
        card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(badges) }, gap(ctx, 8f))
        val status = when {
            s.busy -> if (s.stage == "guide") getString(R.string.rd_tv_updating_guide, "%.1f".format(s.received / 1048576.0)) else getString(R.string.rd_tv_updating_channels)
            s.error.isNotEmpty() && s.channels == 0 -> getString(R.string.rd_tv_could_not_load, s.name, s.error)
            s.updated > 0 -> getString(R.string.rd_tv_updated_at, TvTexts.day(ctx, s.updated), TvTexts.time(ctx, s.updated))
            else -> ""
        }
        if (status.isNotEmpty()) card.addView(ctx.tvText(status, 12f, ctx.tvColor(if (s.error.isNotEmpty() && s.channels == 0 && !s.busy) R.color.rd_warning else R.color.rd_fg_a3), lines = 2), gap(ctx, 8f))
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun add(v: View) = buttons.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.tvPx(6f) })
        if (count > 1) {
            add(ctx.tvIconButton(R.drawable.rd_ic_chevron_down, getString(R.string.rd_tv_move_up)) { LiveTv.moveSource(s.id, -1) }.apply { rotation = 180f; isEnabled = index > 0; alpha = if (index > 0) 1f else 0.3f; minimumWidth = ctx.tvPx(36f); minimumHeight = ctx.tvPx(36f) })
            add(ctx.tvIconButton(R.drawable.rd_ic_chevron_down, getString(R.string.rd_tv_move_down)) { LiveTv.moveSource(s.id, 1) }.apply { isEnabled = index < count - 1; alpha = if (index < count - 1) 1f else 0.3f; minimumWidth = ctx.tvPx(36f); minimumHeight = ctx.tvPx(36f) })
        }
        add(ctx.tvButton(getString(if (s.enabled) R.string.rd_tv_turn_off else R.string.rd_tv_turn_on)) { LiveTv.setSourceEnabled(s.id, !s.enabled) })
        add(ctx.tvButton(getString(R.string.rd_tv_update)) { LiveTv.refresh(listOf(s.id)) }.apply { isEnabled = s.enabled && !s.busy })
        add(ctx.tvButton(getString(R.string.rd_tv_edit)) { edit(s.id) })
        add(ctx.tvButton(getString(R.string.rd_tv_remove)) { LiveTv.removeSource(s.id) })
        card.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(buttons) }, gap(ctx, 10f))
        return card
    }

    private fun clearFields() {
        fName = ""; fUrl = ""; fServer = ""; fUser = ""; fPassword = ""; fEpg = ""; fFor = ""; error = ""
    }

    private fun edit(id: String) {
        val s = LiveTv.source(id) ?: return
        clearFields()
        editing = id
        kind = s.kind
        fName = s.name; fUrl = s.url; fServer = s.server; fUser = s.username; fPassword = ""
        fEpg = s.epg.joinToString("\n")
        page = "link"
        build()
    }

    /* ---- a link of the user's ---- */

    private fun watch(field: EditText, set: (String) -> Unit) = field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { set(s?.toString() ?: "") }
    })

    private fun buildLink(ctx: Context, sources: List<SourceStatus>) {
        val kinds = TvSegmented(ctx) { v -> kind = v; error = ""; build() }
        val options = mutableListOf(getString(R.string.rd_tv_kind_m3u) to "m3u", getString(R.string.rd_tv_kind_xtream) to "xtream")
        if (editing.isEmpty()) options.add(getString(R.string.rd_tv_kind_epg) to "epg")
        kinds.setOptions(options)
        kinds.value = kind
        body.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(kinds, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.tvPx(36f))) }, gap(ctx, 4f))
        if (kind != "epg") {
            heading(ctx, getString(R.string.rd_tv_name), getString(R.string.rd_tv_optional))
            body.addView(ctx.tvField(getString(R.string.rd_tv_name_hint)).apply { setText(fName); watch(this) { fName = it }; contentDescription = getString(R.string.rd_tv_name) }, gap(ctx, 6f))
        }
        when (kind) {
            "m3u" -> {
                heading(ctx, getString(R.string.rd_tv_playlist_link), description = getString(R.string.rd_tv_playlist_link_help))
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(ctx.tvField(getString(R.string.rd_tv_playlist_hint)).apply { setText(fUrl); watch(this) { fUrl = it }; contentDescription = getString(R.string.rd_tv_playlist_link) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(ctx.tvButton(getString(R.string.rd_tv_browse)) { picker.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/octet-stream", "text/plain", "*/*")) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(8f) })
                body.addView(row, gap(ctx, 6f))
            }
            "xtream" -> {
                heading(ctx, getString(R.string.rd_tv_server), description = getString(R.string.rd_tv_server_help))
                body.addView(ctx.tvField(getString(R.string.rd_tv_server_hint)).apply { setText(fServer); watch(this) { fServer = it }; contentDescription = getString(R.string.rd_tv_server) }, gap(ctx, 6f))
                heading(ctx, getString(R.string.rd_tv_user_name))
                body.addView(ctx.tvField("").apply { setText(fUser); watch(this) { fUser = it }; contentDescription = getString(R.string.rd_tv_user_name) }, gap(ctx, 6f))
                heading(ctx, getString(R.string.rd_tv_password), if (editing.isNotEmpty()) getString(R.string.rd_tv_password_unchanged) else "")
                body.addView(ctx.tvField("", password = true).apply { setText(fPassword); watch(this) { fPassword = it }; contentDescription = getString(R.string.rd_tv_password) }, gap(ctx, 6f))
            }
            "epg" -> {
                heading(ctx, getString(R.string.rd_tv_for_source), description = getString(if (sources.isNotEmpty()) R.string.rd_tv_for_source_help else R.string.rd_tv_add_source_first))
                if (fFor.isEmpty() || sources.none { it.id == fFor }) fFor = sources.firstOrNull()?.id ?: ""
                if (sources.isNotEmpty()) {
                    val chosen = sources.firstOrNull { it.id == fFor }
                    val drop = dropdown(ctx, chosen?.name ?: "") { anchor ->
                        val menu = PopupMenu(ctx, anchor)
                        sources.forEachIndexed { i, s -> menu.menu.add(0, i, i, s.name) }
                        menu.setOnMenuItemClickListener { fFor = sources[it.itemId].id; build(); true }
                        menu.show()
                    }
                    body.addView(drop, gap(ctx, 6f))
                }
            }
        }
        heading(ctx, getString(R.string.rd_tv_guide_links), if (kind == "epg") "" else getString(R.string.rd_tv_optional), getString(R.string.rd_tv_guide_links_help))
        body.addView(ctx.tvField(getString(R.string.rd_tv_guide_hint), multiLine = true).apply { setText(fEpg); watch(this) { fEpg = it }; contentDescription = getString(R.string.rd_tv_guide_links) }, gap(ctx, 6f))
        if (error.isNotEmpty()) body.addView(ctx.tvText(error, 13f, ctx.tvColor(R.color.rd_warning), lines = 0), gap(ctx, 10f))
        body.addView(ctx.tvText(getString(R.string.rd_tv_secrets_help), 12f, ctx.tvColor(R.color.rd_fg_a4), lines = 0), gap(ctx, 14f))
        footer.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(ctx.tvButton(getString(R.string.rd_tv_back)) { page = "sources"; editing = ""; build() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(8f) })
        footer.addView(ctx.tvButton(getString(when {
            editing.isNotEmpty() -> R.string.rd_tv_save
            kind == "epg" -> R.string.rd_tv_add_guide
            else -> R.string.rd_tv_add_source
        }), accent = true) { accept() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(8f) })
    }

    private fun dropdown(ctx: Context, text: String, open: (View) -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ContextCompat.getDrawable(ctx, R.drawable.rd_spinner_bg)
        setPadding(ctx.tvPx(12f), 0, ctx.tvPx(8f), 0)
        minimumHeight = ctx.tvPx(36f)
        isClickable = true
        isFocusable = true
        addView(ctx.tvText(text, 14f, ctx.tvColor(R.color.rd_fg_a1)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(android.widget.ImageView(ctx).apply { setImageResource(R.drawable.rd_ic_chevron_down) }, LinearLayout.LayoutParams(ctx.tvPx(20f), ctx.tvPx(20f)))
        setOnClickListener { open(this) }
    }

    private fun accept() {
        val epg = fEpg.split('\n', ' ', ',').map { it.trim() }.filter { it.isNotEmpty() }
        when (kind) {
            "epg" -> {
                if (fFor.isEmpty() || epg.isEmpty()) { error = getString(R.string.rd_tv_fill_fields); build(); return }
                for (u in epg) LiveTv.addGuide(fFor, u)
            }
            "xtream" -> {
                if (fServer.isBlank() || fUser.isBlank() || (editing.isEmpty() && fPassword.isEmpty())) { error = getString(R.string.rd_tv_fill_fields); build(); return }
                val s = (if (editing.isNotEmpty()) LiveTv.source(editing) else null) ?: SourceConfig()
                s.kind = "xtream"; s.name = fName.trim().ifEmpty { org.videolan.vlc.livetv.TvNet.hostOf(org.videolan.vlc.livetv.Xtream.normalizeServer(fServer)) }
                s.server = fServer.trim(); s.username = fUser.trim(); s.password = fPassword
                s.epg.clear(); s.epg.addAll(epg)
                LiveTv.saveSource(s)
            }
            else -> {
                if (fUrl.isBlank()) { error = getString(R.string.rd_tv_fill_fields); build(); return }
                val s = (if (editing.isNotEmpty()) LiveTv.source(editing) else null) ?: SourceConfig()
                val url = fUrl.trim()
                s.kind = "m3u"; s.url = if (!url.contains("://") && !url.startsWith("/")) "http://$url" else url
                s.name = fName.trim().ifEmpty { org.videolan.vlc.livetv.TvNet.hostOf(s.url).takeIf { it != "a file" } ?: Uri.parse(s.url).lastPathSegment ?: "" }
                s.epg.clear(); s.epg.addAll(epg)
                LiveTv.saveSource(s)
            }
        }
        editing = ""
        clearFields()
        page = "sources"
        build()
    }

    /* ---- the catalog ---- */

    private fun loadCatalog() {
        if (catalog != null) return
        catalog = emptyList()
        catalogLoading = true
        Thread {
            val root = LiveTv.catalog()
            val list = root.optJSONArray("sources")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
            view?.post { catalog = list; catalogLoading = false; if (page == "catalog") build() }
        }.start()
        LiveTv.updateCatalog { view?.post { catalog = null; loadCatalog() } }
    }

    private fun strings(o: JSONObject, key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

    private fun languageName(code: String) = Locale(code).getDisplayLanguage(Locale.getDefault()).ifEmpty { code }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    private fun countryName(code: String) = Locale("", code).getDisplayCountry(Locale.getDefault()).ifEmpty { code }

    private fun buildCatalog(ctx: Context, sources: List<SourceStatus>) {
        loadCatalog()
        val all = catalog ?: emptyList()
        val search = ctx.tvField(getString(R.string.rd_tv_search_catalog)).apply {
            setText(cSearch)
            background = ContextCompat.getDrawable(ctx, R.drawable.rd_search_well)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.rd_ic_search, 0, 0, 0)
            compoundDrawablePadding = ctx.tvPx(8f)
            contentDescription = getString(R.string.rd_tv_search_catalog)
            watch(this) { if (cSearch != it) { cSearch = it; refreshCatalogList(ctx) } }
        }
        body.addView(search, gap(ctx, 4f))
        // the filters: choices of the values the sources have
        val filters = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun filter(label: String, value: String, values: List<Pair<String, String>>, set: (String) -> Unit) {
            val text = if (value.isEmpty()) label else values.firstOrNull { it.first == value }?.second ?: value
            filters.addView(dropdown(ctx, text) { anchor ->
                val menu = PopupMenu(ctx, anchor)
                menu.menu.add(0, 0, 0, getString(R.string.rd_tv_all))
                values.forEachIndexed { i, (_, name) -> menu.menu.add(0, i + 1, i + 1, name) }
                menu.setOnMenuItemClickListener { set(if (it.itemId == 0) "" else values[it.itemId - 1].first); build(); true }
                menu.show()
            }.apply { contentDescription = label }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.tvPx(8f) })
        }
        val languages = all.flatMap { strings(it, "languages") }.distinct().map { it to languageName(it) }.sortedBy { it.second }
        val countries = all.flatMap { strings(it, "countries") }.distinct().map { it to countryName(it) }.sortedBy { it.second }
        val categories = all.flatMap { strings(it, "categories") }.distinct().sorted().map { it to it.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }
        filter(getString(R.string.rd_tv_language), cLanguage, languages) { cLanguage = it }
        filter(getString(R.string.rd_tv_country), cCountry, countries) { cCountry = it }
        filter(getString(R.string.rd_tv_category), cCategory, categories) { cCategory = it }
        filters.addView(ctx.tvButton(getString(if (cSort == "name") R.string.rd_tv_sort_az else R.string.rd_tv_sort_most)) { cSort = if (cSort == "name") "channels" else "name"; build() })
        body.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(filters) }, gap(ctx, 10f))
        // what the sources can do (a row of its own)
        val features = TvSegmented(ctx) { v -> cFeature = v; refreshCatalogList(ctx) }
        features.setOptions(listOf(getString(R.string.rd_tv_all) to "", getString(R.string.rd_tv_guide) to "epg", getString(R.string.rd_tv_catchup) to "catchup",
            getString(R.string.rd_tv_films) to "vod", getString(R.string.rd_tv_free) to "free", getString(R.string.rd_tv_guides) to "guides"))
        features.value = cFeature
        body.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(features, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.tvPx(36f))) }, gap(ctx, 10f))
        body.addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; tag = "list" }, gap(ctx, 4f))
        refreshCatalogList(ctx)
        // the legal note and the suggestion form (the project's discussions)
        val note = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        note.addView(ctx.tvText(getString(R.string.rd_tv_catalog_legal), 12f, ctx.tvColor(R.color.rd_fg_a4), lines = 0), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        note.addView(ctx.tvButton(getString(R.string.rd_tv_suggest)) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LiveTv.suggestionUrl()))) } catch (e: Exception) { }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(10f) })
        body.addView(note, gap(ctx, 16f))
        footer.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
    }

    private fun refreshCatalogList(ctx: Context) {
        val list = body.findViewWithTag<LinearLayout>("list") ?: return
        list.removeAllViews()
        val all = catalog
        val sources = LiveTv.state.value.sources
        val guidesGiven = sources.flatMap { it.epg }.toSet()
        if (all == null || all.isEmpty()) {
            list.addView(ctx.tvText(getString(if (all == null || catalogLoading) R.string.rd_tv_loading_catalog else R.string.rd_tv_catalog_empty), 14f, ctx.tvColor(R.color.rd_fg_a3)), gap(ctx, 16f))
            return
        }
        val q = cSearch.trim().lowercase()
        val shown = all.filter { o ->
            val isGuide = o.optString("kind") == "epg"
            (if (cFeature == "guides") isGuide else !isGuide) &&
            (q.isEmpty() || o.optString("name").lowercase().contains(q) || o.optString("description").lowercase().contains(q)) &&
            (cLanguage.isEmpty() || strings(o, "languages").contains(cLanguage)) &&
            (cCountry.isEmpty() || strings(o, "countries").contains(cCountry)) &&
            (cCategory.isEmpty() || strings(o, "categories").contains(cCategory)) &&
            when (cFeature) {
                "epg" -> (o.optJSONArray("epg")?.length() ?: 0) > 0
                "catchup" -> o.optJSONObject("catchup")?.optBoolean("supported") == true
                "vod" -> o.optBoolean("vod") || o.optBoolean("series")
                "free" -> o.optString("price") == "free"
                else -> true
            }
        }.let { l -> if (cSort == "name") l.sortedBy { it.optString("name").lowercase() } else l.sortedByDescending { it.optInt("channels") } }
        if (shown.isEmpty()) {
            list.addView(ctx.tvText(getString(R.string.rd_tv_catalog_no_match), 14f, ctx.tvColor(R.color.rd_fg_a3)), gap(ctx, 16f))
            return
        }
        // the first ones, then more as the list is scrolled to its end (275 cards at once are slow to build)
        val count = minOf(shown.size, catalogShown)
        for (o in shown.take(count)) list.addView(catalogCard(ctx, o, sources, guidesGiven), gap(ctx, 10f))
        if (count < shown.size) list.addView(ctx.tvButton(getString(R.string.rd_tv_later)) { catalogShown = count + 60; refreshCatalogList(ctx) }.apply { text = "+ ${shown.size - count}" }, gap(ctx, 10f))
    }

    private fun catalogCard(ctx: Context, o: JSONObject, sources: List<SourceStatus>, guidesGiven: Set<String>): View {
        val isGuide = o.optString("kind") == "epg"
        val id = o.optString("id")
        val added = if (isGuide) guidesGiven.contains(o.optString("url")) else sources.any { it.catalogId == id }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.rd_card_a3)
            setPadding(ctx.tvPx(14f), ctx.tvPx(12f), ctx.tvPx(14f), ctx.tvPx(12f))
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ctx.tvText(o.optString("name"), 14f, ctx.tvColor(R.color.rd_fg_1), medium = true, lines = 2))
        val desc = o.optString("description")
        if (desc.isNotEmpty()) texts.addView(ctx.tvText(desc, 12f, ctx.tvColor(R.color.rd_fg_a3), lines = 3), gap(ctx, 4f))
        val badges = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun badge(v: View) = badges.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.tvPx(6f) })
        val channels = o.optInt("channels")
        if (!isGuide && channels > 0) badge(ctx.tvBadge(resources.getQuantityString(R.plurals.rd_tv_n_channels, channels, channels), R.drawable.rd_ic_tv))
        if ((o.optJSONArray("epg")?.length() ?: 0) > 0 || isGuide) badge(ctx.tvBadge(getString(R.string.rd_tv_guide), R.drawable.rd_ic_duo_lists))
        val days = o.optJSONObject("catchup")?.optInt("days") ?: 0
        if (o.optJSONObject("catchup")?.optBoolean("supported") == true && days > 0) badge(ctx.tvBadge(resources.getQuantityString(R.plurals.rd_tv_days_short, days, days), R.drawable.rd_ic_recent))
        if (o.optBoolean("vod")) badge(ctx.tvBadge(getString(R.string.rd_tv_films), R.drawable.rd_ic_video))
        if (o.optBoolean("series")) badge(ctx.tvBadge(getString(R.string.rd_tv_series), R.drawable.rd_ic_playlists))
        badge(ctx.tvBadge(getString(if (o.optString("price") == "free") R.string.rd_tv_free else R.string.rd_tv_paid), accent = o.optString("price") == "free"))
        texts.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(badges) }, gap(ctx, 8f))
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val button = ctx.tvButton(getString(when { added -> R.string.rd_tv_added; isGuide -> R.string.rd_tv_add_guide; else -> R.string.rd_tv_add }), accent = !added) {
            if (isGuide) {
                // a guide goes with the first source (the guides serve every source)
                sources.firstOrNull()?.let { LiveTv.addGuide(it.id, o.optString("url")) }
            } else {
                val s = SourceConfig()
                s.kind = "m3u"; s.name = o.optString("name"); s.url = o.optString("url"); s.catalogId = id
                s.epg.addAll(strings(o, "epg"))
                LiveTv.saveSource(s)
            }
        }
        button.isEnabled = !added && (!isGuide || sources.isNotEmpty())
        card.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(10f); gravity = Gravity.CENTER_VERTICAL })
        return card
    }

    companion object {
        const val TAG = "tv_sources"
        fun show(fm: FragmentManager, page: String) {
            if (fm.findFragmentByTag(TAG) != null) return
            TvSourcesDialog().apply { arguments = Bundle().apply { putString("page", page) } }.show(fm, TAG)
        }
    }
}
