/*
 * VLC Redesign, Live TV: the TV sources (TvSourceDialog.qml, the dialogs of the design as Open Network Stream):
 * the ones added with what each can do; a link of the user's (an M3U playlist or file, an Xtream Codes login, a
 * guide); one of the catalog of free, legal channel lists, added in one tap with its guide (for the user first:
 * Recommended, For you, what the app saw of each: works, not answering); the accounts of the user's TV servers and
 * paid guides (TvAccounts: their fields, Sign in / Test, Sign out; those found on the network offered); and every
 * guide of every source (on/off, Update, Edit, Remove; the guide found for its channels by itself, Undo).
 * Every control is reached with the TV remote, right to left too.
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
import org.videolan.vlc.livetv.CatalogHealth
import org.videolan.vlc.livetv.LiveTv
import org.videolan.vlc.livetv.SourceConfig
import org.videolan.vlc.livetv.SourceStatus
import org.videolan.vlc.livetv.TvAccounts
import org.videolan.vlc.livetv.TvCatalogRules
import org.videolan.vlc.livetv.TvCheck
import org.videolan.vlc.livetv.TvCheckState
import org.videolan.vlc.livetv.TvFound
import java.util.Locale

class TvSourcesDialog : DialogFragment() {

    private var page = "sources"
    private var kind = "m3u"
    private var editing = ""
    private var error = ""

    private lateinit var tabs: TvSegmented
    private lateinit var body: LinearLayout
    /** the footer: Close at the start, then [footer], the actions of the page at the end, wrapping when too wide */
    private lateinit var footerBar: LinearLayout
    private lateinit var footer: TvFlow
    private lateinit var scroll: ScrollView

    // the fields of the link page, kept across its rebuilds
    private var fName = ""
    private var fUrl = ""
    private var fServer = ""
    private var fUser = ""
    private var fPassword = ""
    private var fEpg = ""
    private var fFor = ""

    // the catalog's filters (Recommended and for you first)
    private var catalog: List<JSONObject>? = null
    private var cSearch = ""
    private var cLanguage = ""
    private var cCountry = ""
    private var cCategory = ""
    private var cSort = "you"
    private var cFeature = "rec"

    // the account being added or edited: its type and fields, what Sign in / Test said
    private var aType = ""
    private val aFields = HashMap<String, String>()
    private var aCheck: TvCheck? = null
    private var aChecking = false
    // the servers found on the network (null: not looked for yet)
    private var found: List<TvFound>? = null
    private var looking = false
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
        footerBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footer = TvFlow(ctx, ctx.tvPx(8f), ctx.tvPx(8f), alignEnd = true)
        footerBar.addView(footer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.tvPx(8f) })
        root.addView(footerBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(12f) })
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        build()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveTv.state.collect {
                    if (page == "sources" || page == "accounts" || page == "guides") build()
                    else if (page == "catalog") { setTabs(); refreshCatalogList(requireContext()) }
                }
            }
        }
    }

    /** what had the focus (the TV remote): its text and which one of that text, to be given it again after a rebuild */
    private fun focusKey(root: View): Pair<String, Int>? {
        val f = root.findFocus() ?: return null
        if (f is EditText) return null
        (f.tag as? String)?.takeIf { it.startsWith("fk:") }?.let { return it to -1 }
        val text = ((f as? TextView)?.text ?: f.contentDescription)?.toString() ?: return null
        var n = 0
        var index = -1
        fun walk(v: View) {
            if (index >= 0) return
            val t = ((v as? TextView)?.text ?: v.contentDescription)?.toString()
            if (v.isFocusable && t == text) { if (v === f) index = n else n++ }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return if (index >= 0) text to index else null
    }

    private fun refocus(root: View, key: Pair<String, Int>) {
        if (key.second < 0) { root.findViewWithTag<View>(key.first)?.requestFocus(); return }
        var n = 0
        fun walk(v: View): Boolean {
            val t = ((v as? TextView)?.text ?: v.contentDescription)?.toString()
            if (v.isFocusable && v.isEnabled && t == key.first) { if (n == key.second) { v.requestFocus(); return true }; n++ }
            if (v is ViewGroup) for (i in 0 until v.childCount) if (walk(v.getChildAt(i))) return true
            return false
        }
        walk(root)
    }

    private var lastPage = ""
    /** the focus to give back once the page is drawn again (two rebuilds in a row: the first one's, not the tabs') */
    private var pendingFocus: Pair<String, Int>? = null

    private fun build() {
        if (!::body.isInitialized) return
        val ctx = context ?: return
        val root = view
        val kept = pendingFocus ?: root?.let { focusKey(it) }
        // an account's form opened with the remote (Add, Edit): its first field has the focus, not the tabs
        val opened = page == "account" && lastPage != "account"
        lastPage = page
        buildPage(ctx)
        if (root != null && opened && !root.isInTouchMode) { pendingFocus = null; root.post { firstField(body)?.requestFocus() } }
        else if (root != null && kept != null) {
            pendingFocus = kept
            root.post { pendingFocus = null; refocus(root, kept) }
        }
    }

    private fun firstField(v: View): View? {
        if (v is EditText && v.isFocusable) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstField(v.getChildAt(i))?.let { return it }
        return null
    }

    /** the tabs, "Your Sources (2)" counting a source added from the catalog too (nothing redrawn when the same) */
    private fun setTabs() {
        val sources = LiveTv.state.value.sources
        tabs.setOptions(listOf(getString(R.string.rd_tv_your_sources_n, sources.count { it.kind != "account" }.toString()) to "sources",
            getString(R.string.rd_tv_add_link) to "link", getString(R.string.rd_tv_catalog) to "catalog",
            getString(R.string.rd_tv_accounts) to "accounts", getString(R.string.rd_tv_guides) to "guides"))
        tabs.value = if (page == "account") "accounts" else page
    }

    private fun buildPage(ctx: Context) {
        val sources = LiveTv.state.value.sources
        setTabs()
        body.removeAllViews()
        footer.removeAllViews()
        while (footerBar.childCount > 1) footerBar.removeViewAt(0)
        when (page) {
            "link" -> buildLink(ctx, sources)
            "catalog" -> buildCatalog(ctx, sources)
            "accounts" -> buildAccounts(ctx, sources)
            "account" -> buildAccountForm(ctx)
            "guides" -> buildGuides(ctx, sources)
            else -> buildSources(ctx, sources)
        }
        footerBar.addView(ctx.tvButton(getString(R.string.rd_tv_close)) { dismissAllowingStateLoss() }, 0)
    }

    /**
     * A button that can't act now (busy, checking, the first already, added): dimmed, a press does nothing, but it
     * stays focusable, so the TV remote's focus stays on it (a disabled one gives it up and it jumped to the tabs).
     */
    private fun <T : View> T.usable(on: Boolean): T {
        if (!on) {
            alpha = 0.4f
            setOnClickListener { }
        }
        return this
    }

    private fun gap(ctx: Context, v: Float) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.tvPx(v) }

    private fun heading(ctx: Context, text: String, suffix: String = "", description: String = "") {
        val t = ctx.tvText(if (suffix.isEmpty()) text else "$text  $suffix", 14f, ctx.tvColor(R.color.rd_fg_2), medium = true)
        body.addView(t, gap(ctx, 14f))
        if (description.isNotEmpty()) body.addView(ctx.tvText(description, 12f, ctx.tvColor(R.color.rd_fg_a3), lines = 0), gap(ctx, 4f))
    }

    /* ---- the sources added ---- */

    private fun buildSources(ctx: Context, all: List<SourceStatus>) {
        // (the accounts have a page of their own)
        val sources = all.filter { it.kind != "account" }
        heading(ctx, getString(R.string.rd_tv_your_tv_sources), description = getString(
            if (sources.isNotEmpty()) R.string.rd_tv_sources_order_help else R.string.rd_tv_no_source_help))
        sources.forEachIndexed { i, s -> body.addView(sourceCard(ctx, s, i, sources.size), gap(ctx, 10f)) }
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
        // badges and buttons wrap on a narrow window or a long translation, instead of being cut at the edge
        val badges = TvFlow(ctx, ctx.tvPx(6f), ctx.tvPx(6f))
        fun badge(v: View) = badges.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        badge(ctx.tvBadge(resources.getQuantityString(R.plurals.rd_tv_n_channels, s.channels, s.channels), R.drawable.rd_ic_tv))
        if (s.withGuide > 0) badge(ctx.tvBadge(resources.getQuantityString(R.plurals.rd_tv_guide_n, s.withGuide, s.withGuide), R.drawable.rd_ic_duo_lists))
        if (s.catchupDays > 0) badge(ctx.tvBadge(resources.getQuantityString(R.plurals.rd_tv_catchup_n, s.catchupDays, s.catchupDays), R.drawable.rd_ic_recent))
        if (s.vod) badge(ctx.tvBadge(getString(R.string.rd_tv_films), R.drawable.rd_ic_video))
        if (s.series) badge(ctx.tvBadge(getString(R.string.rd_tv_series), R.drawable.rd_ic_playlists))
        card.addView(badges, gap(ctx, 8f))
        val status = when {
            s.busy -> if (s.stage == "guide") getString(R.string.rd_tv_updating_guide, "%.1f".format(s.received / 1048576.0)) else getString(R.string.rd_tv_updating_channels)
            s.error.isNotEmpty() && s.channels == 0 -> getString(R.string.rd_tv_could_not_load, s.name, s.error)
            s.updated > 0 -> getString(R.string.rd_tv_updated_at, TvTexts.day(ctx, s.updated), TvTexts.time(ctx, s.updated))
            else -> ""
        }
        if (status.isNotEmpty()) card.addView(ctx.tvText(status, 12f, ctx.tvColor(if (s.error.isNotEmpty() && s.channels == 0 && !s.busy) R.color.rd_warning else R.color.rd_fg_a3), lines = 2), gap(ctx, 8f))
        // the guide found for its channels by itself, with Undo
        autoLine(ctx, s)?.let { card.addView(it, gap(ctx, 6f)) }
        val buttons = TvFlow(ctx, ctx.tvPx(6f), ctx.tvPx(6f))
        fun add(v: View) = buttons.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (count > 1) {
            add(ctx.tvIconButton(R.drawable.rd_ic_chevron_down, getString(R.string.rd_tv_move_up)) { LiveTv.moveSource(s.id, -1) }.apply { rotation = 180f; usable(index > 0); minimumWidth = ctx.tvPx(36f); minimumHeight = ctx.tvPx(36f) })
            add(ctx.tvIconButton(R.drawable.rd_ic_chevron_down, getString(R.string.rd_tv_move_down)) { LiveTv.moveSource(s.id, 1) }.apply { usable(index < count - 1); minimumWidth = ctx.tvPx(36f); minimumHeight = ctx.tvPx(36f) })
        }
        add(ctx.tvButton(getString(if (s.enabled) R.string.rd_tv_turn_off else R.string.rd_tv_turn_on)) { LiveTv.setSourceEnabled(s.id, !s.enabled) })
        add(ctx.tvButton(getString(R.string.rd_tv_update)) { LiveTv.refresh(listOf(s.id)) }.usable(s.enabled && !s.busy))
        add(ctx.tvButton(getString(R.string.rd_tv_edit)) { edit(s.id) })
        add(ctx.tvButton(getString(R.string.rd_tv_remove)) { LiveTv.removeSource(s.id) })
        card.addView(buttons, gap(ctx, 10f))
        return card
    }

    /** "Guide found for 112 of 130 channels" with Undo; "Finding a guide…"; "No guide found for these channels" */
    private fun autoLine(ctx: Context, s: SourceStatus): View? {
        val text = when {
            s.autoBusy -> getString(R.string.rd_tv_finding_guide)
            s.autoFound > 0 -> getString(R.string.rd_tv_guide_found, s.autoFound.toString(), s.autoTotal.toString())
            s.autoNone -> getString(R.string.rd_tv_no_guide_found)
            else -> return null
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ctx.tvText(text, 12f, ctx.tvColor(R.color.rd_fg_a2), lines = 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (s.autoFound > 0 && !s.autoBusy) row.addView(ctx.tvButton(getString(R.string.rd_tv_undo)) { LiveTv.removeAutoGuide(s.id) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(8f) })
        return row
    }

    private fun clearFields() {
        fName = ""; fUrl = ""; fServer = ""; fUser = ""; fPassword = ""; fEpg = ""; fFor = ""; error = ""
    }

    /* ---- the accounts: the user's TV servers and paid guides ---- */

    private fun checkText(c: TvCheck): String = when (c.state) {
        TvCheckState.CODE -> getString(R.string.rd_tv_quick_connect, c.code)
        TvCheckState.SIGNED_IN -> if (c.user.isNotEmpty()) getString(R.string.rd_os_signed_in_as, c.user) else getString(R.string.rd_tv_works)
        TvCheckState.WORKS -> getString(R.string.rd_tv_works) + if (c.user.isNotEmpty()) " · " + c.user else ""
        TvCheckState.WRONG -> getString(R.string.rd_os_wrong_login)
        TvCheckState.REFUSED -> getString(R.string.rd_os_login_refused_check)
        TvCheckState.BUSY -> getString(R.string.rd_os_busy_minute)
        TvCheckState.NO_ANSWER -> getString(R.string.rd_os_no_answer_retry)
    }

    private fun typeName(id: String) = if (id == "m3ulogin") getString(R.string.rd_tv_acc_m3ulogin_name) else TvAccounts.type(id)?.name ?: id

    private fun typeDetail(id: String) = getString(when (id) {
        "jellyfin" -> R.string.rd_tv_acc_jellyfin
        "emby" -> R.string.rd_tv_acc_emby
        "tvheadend" -> R.string.rd_tv_acc_tvheadend
        "nextpvr" -> R.string.rd_tv_acc_nextpvr
        "channelsdvr" -> R.string.rd_tv_acc_channelsdvr
        "hdhomerun" -> R.string.rd_tv_acc_hdhomerun
        "schedulesdirect" -> R.string.rd_tv_acc_schedulesdirect
        else -> R.string.rd_tv_acc_m3ulogin
    })

    private fun fieldLabel(name: String) = getString(when (name) {
        "address" -> R.string.rd_tv_server_address
        "username" -> R.string.rd_tv_user_name
        "password" -> R.string.rd_tv_password
        "pin" -> R.string.rd_tv_pin
        "key" -> R.string.rd_tv_api_key
        "code" -> R.string.rd_tv_access_code
        "url" -> R.string.rd_tv_playlist_link
        else -> R.string.rd_tv_guide_link
    })

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun buildAccounts(ctx: Context, sources: List<SourceStatus>) {
        heading(ctx, getString(R.string.rd_tv_accounts), description = getString(R.string.rd_tv_accounts_help))
        // the servers of the network, once a visit of the page (Look again)
        if (found == null && !looking) {
            looking = true
            LiveTv.discover { list -> view?.post { found = list; looking = false; if (page == "accounts") build() } }
        }
        val network = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ctx.tvText(getString(if (looking) R.string.rd_tv_looking_network else R.string.rd_tv_found_network), 13f, ctx.tvColor(R.color.rd_fg_2), medium = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!looking) head.addView(ctx.tvButton(getString(R.string.rd_tv_look_again)) { found = null; build() }, wrap())
        network.addView(head)
        // (one added since: not offered again; the types in their order)
        val added = sources.filter { it.kind == "account" }.map { it.address.trimEnd('/').lowercase(Locale.ROOT) }.toSet()
        val order = TvAccounts.TYPES.map { it.id }
        val offered = (found ?: emptyList()).filter { it.address.trimEnd('/').lowercase(Locale.ROOT) !in added }.sortedWith(compareBy({ order.indexOf(it.type) }, { it.name }))
        // (none, or all of them added already)
        if (!looking && offered.isEmpty()) network.addView(ctx.tvText(getString(R.string.rd_tv_nothing_here), 12f, ctx.tvColor(R.color.rd_fg_a3)), gap(ctx, 4f))
        for (f in offered) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(ctx, R.drawable.rd_card_a3)
                setPadding(ctx.tvPx(14f), ctx.tvPx(8f), ctx.tvPx(14f), ctx.tvPx(8f))
            }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            // "Demo Jellyfin · Jellyfin", "HDHomeRun 1050DE00" (its type said by its name already)
            val label = if (f.name.startsWith(typeName(f.type), ignoreCase = true)) f.name else f.name + " · " + typeName(f.type)
            texts.addView(ctx.tvText(label, 14f, ctx.tvColor(R.color.rd_fg_1), medium = true, lines = 1))
            texts.addView(ctx.tvText(f.address, 12f, ctx.tvColor(R.color.rd_fg_a3), lines = 1).apply { textDirection = View.TEXT_DIRECTION_LTR })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ctx.tvButton(getString(R.string.rd_tv_add), accent = true) { newAccount(f.type, f.address, f.name) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(10f) })
            network.addView(row, gap(ctx, 8f))
        }
        body.addView(network, gap(ctx, 10f))
        // the accounts added
        for (s in sources.filter { it.kind == "account" }) body.addView(accountCard(ctx, s), gap(ctx, 10f))
        // the types
        heading(ctx, getString(R.string.rd_tv_add_account))
        for (t in TvAccounts.TYPES) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(ctx, R.drawable.rd_card_a3)
                setPadding(ctx.tvPx(14f), ctx.tvPx(10f), ctx.tvPx(14f), ctx.tvPx(10f))
            }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(ctx.tvText(typeName(t.id), 14f, ctx.tvColor(R.color.rd_fg_1), medium = true))
            texts.addView(ctx.tvText(typeDetail(t.id), 12f, ctx.tvColor(R.color.rd_fg_a3), lines = 2), gap(ctx, 2f))
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ctx.tvButton(getString(R.string.rd_tv_add)) { newAccount(t.id, "", "") }.apply { contentDescription = getString(R.string.rd_tv_add) + ", " + typeName(t.id) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.tvPx(10f) })
            body.addView(row, gap(ctx, 8f))
        }
    }

    private fun accountCard(ctx: Context, s: SourceStatus): View {
        val type = TvAccounts.type(s.accountType)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.rd_card_a3)
            setPadding(ctx.tvPx(14f), ctx.tvPx(12f), ctx.tvPx(14f), ctx.tvPx(12f))
            alpha = if (s.enabled) 1f else 0.6f
        }
        card.addView(ctx.tvText(s.name.ifEmpty { typeName(s.accountType) }, 15f, ctx.tvColor(R.color.rd_fg_1), medium = true))
        card.addView(ctx.tvText(android.text.BidiFormatter.getInstance().unicodeWrap(typeName(s.accountType)) + " · " + if (type?.guideOnly == true) getString(R.string.rd_tv_guide_for_sources)
            else resources.getQuantityString(R.plurals.rd_tv_n_channels, s.channels, s.channels) + " · " + resources.getQuantityString(R.plurals.rd_tv_guide_n, s.withGuide, s.withGuide),
            12f, ctx.tvColor(R.color.rd_fg_a3), lines = 2), gap(ctx, 4f))
        val state = when {
            s.checking -> getString(R.string.rd_tv_signing_in)
            s.refused -> getString(R.string.rd_os_login_refused_check)
            s.check != null -> checkText(s.check)
            s.busy -> getString(R.string.rd_tv_updating_channels)
            s.error.isNotEmpty() -> getString(R.string.rd_tv_could_not_load, s.name, s.error)
            s.updated > 0 -> getString(R.string.rd_tv_updated_at, TvTexts.day(ctx, s.updated), TvTexts.time(ctx, s.updated))
            else -> ""
        }
        val bad = s.refused || s.check?.works == false && s.check.state != TvCheckState.CODE
        if (state.isNotEmpty()) card.addView(ctx.tvText(state, 12f, ctx.tvColor(if (bad) R.color.rd_warning else R.color.rd_fg_a2), lines = 2), gap(ctx, 6f))
        val buttons = TvFlow(ctx, ctx.tvPx(6f), ctx.tvPx(6f))
        fun add(v: View) = buttons.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        add(ctx.tvButton(getString(if (s.enabled) R.string.rd_tv_turn_off else R.string.rd_tv_turn_on)) { LiveTv.setSourceEnabled(s.id, !s.enabled) })
        add(ctx.tvButton(getString(R.string.rd_tv_update)) { LiveTv.refresh(listOf(s.id)) }.usable(s.enabled && !s.busy))
        val cfg = LiveTv.source(s.id)
        add(ctx.tvButton(getString(if (type?.signsIn == true && (cfg?.username?.isNotEmpty() == true || cfg?.pin?.isNotEmpty() == true)) R.string.rd_os_sign_in else R.string.rd_os_test)) {
            cfg?.let { LiveTv.checkAccount(it, { p -> view?.post { if (page == "accounts") build() } }) { view?.post { if (page == "accounts") build() } } }
        }.usable(!s.checking))
        add(ctx.tvButton(getString(R.string.rd_tv_edit)) { editAccount(s.id) })
        if (cfg != null && (cfg.password.isNotEmpty() || cfg.pin.isNotEmpty() || cfg.key.isNotEmpty() || cfg.code.isNotEmpty() || cfg.token.isNotEmpty()))
            add(ctx.tvButton(getString(R.string.rd_os_sign_out)) { LiveTv.signOut(s.id) })
        add(ctx.tvButton(getString(R.string.rd_tv_remove)) { LiveTv.removeSource(s.id) })
        card.addView(buttons, gap(ctx, 10f))
        return card
    }

    private fun newAccount(type: String, address: String, name: String) {
        aType = type
        aFields.clear()
        if (address.isNotEmpty()) aFields["address"] = address
        if (name.isNotEmpty()) aFields["name"] = name
        aCheck = null
        aChecking = false
        editing = ""
        error = ""
        page = "account"
        build()
    }

    private fun editAccount(id: String) {
        val s = LiveTv.source(id) ?: return
        aType = s.accountType
        aFields.clear()
        aFields["name"] = s.name
        aFields["address"] = s.server
        aFields["username"] = s.username
        aFields["url"] = s.url
        aFields["guide"] = s.guideUrl
        aCheck = null
        aChecking = false
        editing = id
        error = ""
        page = "account"
        build()
    }

    /** the account as typed (the address made whole: a scheme, the type's port) */
    private fun accountConfig(): SourceConfig {
        val s = (if (editing.isNotEmpty()) LiveTv.source(editing) else null) ?: SourceConfig()
        s.kind = "account"
        s.accountType = aType
        s.server = TvAccounts.address(aFields["address"] ?: "", aType)
        s.username = (aFields["username"] ?: "").trim()
        s.password = aFields["password"] ?: ""
        s.pin = (aFields["pin"] ?: "").trim()
        s.key = (aFields["key"] ?: "").trim()
        s.code = (aFields["code"] ?: "").trim()
        s.url = (aFields["url"] ?: "").trim()
        s.guideUrl = (aFields["guide"] ?: "").trim()
        s.name = (aFields["name"] ?: "").trim().ifEmpty { typeName(aType) + if (s.server.isNotEmpty()) " · " + TvAccounts.hostOf(s.server) else "" }
        return s
    }

    private fun missing(): Boolean {
        val t = TvAccounts.type(aType) ?: return true
        return t.fields.any { f -> !f.optional && (aFields[f.name] ?: "").isBlank() && !(editing.isNotEmpty() && f.secret) }
    }

    private fun buildAccountForm(ctx: Context) {
        val t = TvAccounts.type(aType) ?: run { page = "accounts"; build(); return }
        heading(ctx, typeName(t.id), description = typeDetail(t.id))
        heading(ctx, getString(R.string.rd_tv_name), getString(R.string.rd_tv_optional))
        body.addView(ctx.tvField(getString(R.string.rd_tv_name_hint)).apply { setText(aFields["name"] ?: ""); watch(this) { aFields["name"] = it }; contentDescription = getString(R.string.rd_tv_name) }, gap(ctx, 6f))
        for (f in t.fields) {
            val label = fieldLabel(f.name)
            heading(ctx, label, when {
                f.optional -> getString(R.string.rd_tv_optional)
                editing.isNotEmpty() && f.secret -> getString(R.string.rd_tv_password_unchanged)
                else -> ""
            })
            val hint = if (f.name == "address") getString(R.string.rd_tv_example, "192.168.1.20" + (t.ports.firstOrNull()?.takeIf { it != 80 }?.let { ":$it" } ?: "")) else ""
            val field = ctx.tvField(hint, password = f.secret).apply {
                setText(aFields[f.name] ?: "")
                watch(this) { aFields[f.name] = it }
                contentDescription = label
                if (f.name == "address" || f.name == "url" || f.name == "guide" || f.secret) textDirection = View.TEXT_DIRECTION_LTR
            }
            body.addView(field, gap(ctx, 6f))
            // the type's usual ports, in a tap
            if (f.name == "address" && t.ports.size > 1 || (f.name == "address" && t.ports.firstOrNull()?.let { it != 80 } == true)) {
                val ports = TvFlow(ctx, ctx.tvPx(6f), ctx.tvPx(6f))
                for (p in t.ports) ports.addView(ctx.tvButton(getString(R.string.rd_tv_port, p.toString())) {
                    val host = (aFields["address"] ?: "").trim().replace(Regex("""^[a-z]+://""", RegexOption.IGNORE_CASE), "").substringBefore('/').substringBefore(':').ifEmpty { "192.168.1.20" }
                    aFields["address"] = (if (p == 8920 || p == 443) "https://" else "http://") + host + ":" + p
                    build()
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                body.addView(ports, gap(ctx, 6f))
            }
        }
        val state = when {
            error.isNotEmpty() -> error
            aChecking && aCheck?.state == TvCheckState.CODE -> checkText(aCheck!!)
            aChecking -> getString(R.string.rd_tv_signing_in)
            aCheck != null -> checkText(aCheck!!)
            else -> ""
        }
        if (state.isNotEmpty()) body.addView(ctx.tvText(state, 13f, ctx.tvColor(if (error.isNotEmpty() || aCheck?.works == false && aCheck?.state != TvCheckState.CODE) R.color.rd_warning else R.color.rd_fg_2), lines = 0), gap(ctx, 12f))
        body.addView(ctx.tvText(getString(R.string.rd_tv_secrets_help), 12f, ctx.tvColor(R.color.rd_fg_a4), lines = 0), gap(ctx, 14f))
        footer.addView(ctx.tvButton(getString(R.string.rd_tv_back)) { page = "accounts"; editing = ""; build() }, wrap().apply { marginStart = ctx.tvPx(8f) })
        footer.addView(ctx.tvButton(getString(if (t.signsIn) R.string.rd_os_sign_in else R.string.rd_os_test)) {
            if (missing()) { error = getString(R.string.rd_tv_fill_fields); build(); return@tvButton }
            error = ""
            aChecking = true
            aCheck = null
            build()
            LiveTv.checkAccount(accountConfig(), { p -> view?.post { aCheck = p; if (page == "account") build() } }) { r ->
                view?.post { aChecking = false; aCheck = r; if (page == "account") build() }
            }
        }.usable(!aChecking), wrap().apply { marginStart = ctx.tvPx(8f) })
        footer.addView(ctx.tvButton(getString(if (editing.isNotEmpty()) R.string.rd_tv_save else R.string.rd_tv_add_source), accent = true) {
            if (missing()) { error = getString(R.string.rd_tv_fill_fields); build(); return@tvButton }
            val s = accountConfig()
            // a token the check took is kept with it
            aCheck?.takeIf { it.token.isNotEmpty() }?.let { s.token = it.token; s.userId = it.userId; s.tokenUntil = it.tokenUntil }
            LiveTv.saveSource(s)
            editing = ""
            page = "accounts"
            build()
        }, wrap().apply { marginStart = ctx.tvPx(8f) })
    }

    /* ---- the guides of every source ---- */

    private fun buildGuides(ctx: Context, sources: List<SourceStatus>) {
        // (the catalog's names of its guides: "iptv-epg.org: United Kingdom" rather than a host)
        loadCatalog()
        heading(ctx, getString(R.string.rd_tv_guides), description = getString(if (sources.isNotEmpty()) R.string.rd_tv_guides_help else R.string.rd_tv_add_source_first))
        for (s in sources) {
            val type = TvAccounts.type(s.accountType)
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(ctx, R.drawable.rd_card_a3)
                setPadding(ctx.tvPx(14f), ctx.tvPx(12f), ctx.tvPx(14f), ctx.tvPx(12f))
                alpha = if (s.enabled) 1f else 0.6f
            }
            card.addView(ctx.tvText(s.name, 15f, ctx.tvColor(R.color.rd_fg_1), medium = true))
            card.addView(ctx.tvText(if (type?.guideOnly == true) getString(R.string.rd_tv_guide_for_sources)
                else resources.getQuantityString(R.plurals.rd_tv_n_channels, s.channels, s.channels) + " · " + resources.getQuantityString(R.plurals.rd_tv_guide_n, s.withGuide, s.withGuide),
                12f, ctx.tvColor(R.color.rd_fg_a3)), gap(ctx, 4f))
            autoLine(ctx, s)?.let { card.addView(it, gap(ctx, 6f)) }
            if (s.kind != "account") {
                val acts = TvFlow(ctx, ctx.tvPx(6f), ctx.tvPx(6f))
                if (s.autoFound > 0) acts.addView(ctx.tvButton(getString(R.string.rd_tv_remove_found_guide)) { LiveTv.removeAutoGuide(s.id) }, wrap())
                else acts.addView(ctx.tvButton(getString(R.string.rd_tv_find_guide)) { LiveTv.autoGuide(s.id) }.usable(s.enabled && !s.autoBusy && s.channels > 0), wrap())
                card.addView(acts, gap(ctx, 8f))
            }
            for (g in s.guides) card.addView(guideRow(ctx, s, g), gap(ctx, 8f))
            body.addView(card, gap(ctx, 10f))
        }
    }

    private fun guideRow(ctx: Context, s: SourceStatus, g: org.videolan.vlc.livetv.GuideStatus): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.tvPx(10f), ctx.tvPx(8f), ctx.tvPx(10f), ctx.tvPx(8f))
            background = ContextCompat.getDrawable(ctx, R.drawable.rd_card_a3)
            alpha = if (g.on) 1f else 0.6f
        }
        val name = when {
            g.kind == "account" -> s.name
            g.kind == "playlist" -> org.videolan.vlc.livetv.TvNet.hostOf(g.url)
            else -> catalog?.firstOrNull { it.optString("kind") == "epg" && it.optString("url") == g.url }?.optString("name")?.ifEmpty { null }
                ?: org.videolan.vlc.livetv.TvNet.hostOf(g.url)
        }
        val kind = getString(when (g.kind) {
            "playlist" -> R.string.rd_tv_from_playlist
            "found" -> R.string.rd_tv_found_for_channels
            "account" -> R.string.rd_tv_from_account
            else -> R.string.rd_tv_added_by_you
        })
        row.addView(ctx.tvText(android.text.BidiFormatter.getInstance().unicodeWrap(name) + " · " + kind, 13f, ctx.tvColor(R.color.rd_fg_2), medium = true, lines = 1))
        val state = when {
            s.busy && s.stage == "guide" -> getString(R.string.rd_tv_updating_guide, "%.1f".format(s.received / 1048576.0))
            g.error.isNotEmpty() && g.channels == 0 -> getString(R.string.rd_tv_could_not_load, name, g.error)
            g.updated > 0 -> getString(R.string.rd_tv_updated_at, TvTexts.day(ctx, g.updated), TvTexts.time(ctx, g.updated)) +
                if (g.channels > 0) " · " + resources.getQuantityString(R.plurals.rd_tv_guide_n, g.channels, g.channels) else ""
            else -> ""
        }
        if (state.isNotEmpty()) row.addView(ctx.tvText(state, 12f, ctx.tvColor(if (g.error.isNotEmpty() && g.channels == 0) R.color.rd_warning else R.color.rd_fg_a3), lines = 2), gap(ctx, 4f))
        val acts = TvFlow(ctx, ctx.tvPx(6f), ctx.tvPx(6f))
        acts.addView(ctx.tvButton(getString(if (g.on) R.string.rd_tv_turn_off else R.string.rd_tv_turn_on)) { LiveTv.setGuideOn(s.id, g.url, !g.on) }, wrap())
        acts.addView(ctx.tvButton(getString(R.string.rd_tv_update)) { LiveTv.refreshGuides(s.id) }.usable(g.on && s.enabled && !s.busy), wrap())
        if (g.kind == "added") acts.addView(ctx.tvButton(getString(R.string.rd_tv_edit)) { editGuide(ctx, s.id, g.url) }, wrap())
        if (g.kind == "added" || g.kind == "found") acts.addView(ctx.tvButton(getString(R.string.rd_tv_remove)) { LiveTv.removeGuide(s.id, g.url) }, wrap())
        row.addView(acts, gap(ctx, 6f))
        return row
    }

    private fun editGuide(ctx: Context, sourceId: String, url: String) {
        val field = ctx.tvField(getString(R.string.rd_tv_guide_hint)).apply { setText(url); textDirection = View.TEXT_DIRECTION_LTR }
        val box = FrameLayout(ctx).apply { setPadding(ctx.tvPx(20f), ctx.tvPx(8f), ctx.tvPx(20f), 0); addView(field) }
        val d = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.rd_tv_guide_link)
            .setView(box)
            .setPositiveButton(R.string.rd_tv_save) { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() && it != url }?.let { LiveTv.replaceGuide(sourceId, url, it) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
        org.videolan.vlc.gui.helpers.RdTvFocusRing.attach(d.window)
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
            view?.post { catalog = list; catalogLoading = false; if (page == "catalog" || page == "guides") build() }
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
        // the order: for you (the country, the languages), A-Z, the most channels
        filters.addView(ctx.tvButton(getString(when (cSort) { "you" -> R.string.rd_tv_for_you; "name" -> R.string.rd_tv_sort_az; else -> R.string.rd_tv_sort_most })) {
            cSort = when (cSort) { "you" -> "name"; "name" -> "channels"; else -> "you" }; build()
        })
        body.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(filters) }, gap(ctx, 10f))
        // what the sources can do (a row of its own)
        val features = TvSegmented(ctx) { v -> cFeature = v; refreshCatalogList(ctx) }
        features.setOptions(listOf(getString(R.string.rd_tv_recommended) to "rec", getString(R.string.rd_tv_all) to "", getString(R.string.rd_tv_guide) to "epg", getString(R.string.rd_tv_catchup) to "catchup",
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
    }

    /** the catalog's list drawn again (an entry added, its health), the remote's focus kept on what it was on */
    private fun refreshCatalogList(ctx: Context) {
        val root = view
        val kept = pendingFocus ?: root?.let { focusKey(it) }
        refreshCatalogListNow(ctx)
        if (root != null && kept != null) {
            pendingFocus = kept
            root.post { pendingFocus = null; refocus(root, kept) }
        }
    }

    private fun refreshCatalogListNow(ctx: Context) {
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
        // the user's country and languages (the interface's, the subtitles'), what the app saw of each entry
        val cc = TvCatalogRules.userCountry(org.videolan.vlc.util.RdCompat.primaryLocale(ctx.resources.configuration))
        val langs = (listOf(org.videolan.vlc.util.RdCompat.primaryLocale(ctx.resources.configuration).language.let { if (it == "iw") "he" else it }) +
            org.videolan.vlc.util.OnlineSubtitles.wantedLanguages(ctx).map { it.substringBefore('-') }).toSet()
        val health = LiveTv.catalogHealth()
        val dead = health.filterValues { it.notAnswering }.keys
        val shown = all.filter { o ->
            val isGuide = o.optString("kind") == "epg"
            if (cFeature == "rec") return@filter TvCatalogRules.recommended(o, cc, langs, o.optString("id") in dead) &&
                (q.isEmpty() || o.optString("name").lowercase().contains(q) || o.optString("description").lowercase().contains(q))
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
        }.let { l -> TvCatalogRules.order(l, cSort, cFeature == "rec", cc, langs, dead) }
        if (shown.isEmpty()) {
            list.addView(ctx.tvText(getString(R.string.rd_tv_catalog_no_match), 14f, ctx.tvColor(R.color.rd_fg_a3)), gap(ctx, 16f))
            return
        }
        // the first ones, then more as the list is scrolled to its end (275 cards at once are slow to build)
        val count = minOf(shown.size, catalogShown)
        for (o in shown.take(count)) list.addView(catalogCard(ctx, o, sources, guidesGiven, health[o.optString("id")]), gap(ctx, 10f))
        if (count < shown.size) list.addView(ctx.tvButton(getString(R.string.rd_tv_later)) { catalogShown = count + 60; refreshCatalogList(ctx) }.apply { text = "+ ${shown.size - count}" }, gap(ctx, 10f))
    }

    private fun catalogCard(ctx: Context, o: JSONObject, sources: List<SourceStatus>, guidesGiven: Set<String>, health: CatalogHealth?): View {
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
        // what the app saw of it: works (its channels then), or not answering (failed twice)
        if (health?.notAnswering == true) badge(ctx.tvBadge(getString(R.string.rd_tv_not_answering)))
        else if (health?.ok == true) badge(ctx.tvBadge(getString(R.string.rd_tv_works) + if (!isGuide && health.channels > 0) " · " + health.channels else "", accent = true))
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
        button.usable(!added && (!isGuide || sources.isNotEmpty()))
        button.tag = "fk:cat:$id"
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
