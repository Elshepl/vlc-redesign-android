/*
 * VLC Redesign: Settings › Subtitles › Languages to search, as on the desktop and the Samsung TV app: the subtitle
 * languages the services are asked for (OnlineSubtitles.searchLanguages), one row each with its switch, in the order
 * of the results (Move up, Move down in its menu, and Remove but for the preferred language); Add a language finds
 * one among every language the services know, in its own name and in the interface's. "Every other language too"
 * (off): the services are asked in no other language, a faster search. A service of one language (Wizdom: Hebrew)
 * is asked only when its language is on.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.preferences.rd

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import org.videolan.tools.KEY_SUBTITLE_PREFERRED_LANGUAGE
import org.videolan.vlc.R
import org.videolan.vlc.util.OnlineSubtitles
import org.videolan.vlc.util.OnlineSubtitles.SearchLanguage
import org.videolan.vlc.util.RdCompat
import java.util.Locale

/** A language's row (the layout of a service's row, without its account). */
class RdLanguagePreference(context: Context, val language: SearchLanguage, private val host: RdSubtitleLanguages) : Preference(context) {
    init {
        layoutResource = R.layout.rd_pref_service
        isPersistent = false
        isSelectable = false
        isIconSpaceReserved = false
        key = KEY_PREFIX + language.code
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.isFocusable = false
        holder.itemView.isClickable = false
        host.bind(this, holder)
    }

    companion object {
        const val KEY_PREFIX = "rd_sub_lang_row_"
    }
}

class RdSubtitleLanguages(private val page: PreferenceFragmentCompat) {
    private val app: Context = page.requireContext().applicationContext

    private fun group() = page.findPreference<PreferenceCategory>(CATEGORY)

    fun build() {
        val ctx = page.context ?: return
        val group = group() ?: return
        for (i in group.preferenceCount - 1 downTo 0) if (group.getPreference(i) is RdLanguagePreference) group.removePreference(group.getPreference(i))
        OnlineSubtitles.searchLanguages(app).forEachIndexed { i, l ->
            group.addPreference(RdLanguagePreference(ctx, l, this).apply {
                order = i
                title = OnlineSubtitles.nativeName(l.code)
            })
        }
        page.findPreference<Preference>(KEY_ADD)?.order = 1000
        page.findPreference<Preference>(OnlineSubtitles.KEY_EVERY_OTHER)?.order = 1001
    }

    /** a row of the page tapped: true when it was Add a language */
    fun click(key: String): Boolean {
        if (key != KEY_ADD) return false
        addLanguage()
        return true
    }

    /** the preferred or the second language changed: the list follows */
    fun changed(key: String) {
        if (key == KEY_SUBTITLE_PREFERRED_LANGUAGE || key == OnlineSubtitles.KEY_SECOND || key == OnlineSubtitles.KEY_LANGUAGES) build()
    }

    private fun uiLocale(ctx: Context) = RdCompat.primaryLocale(ctx.resources.configuration)

    /** "Hebrew", in the interface's language */
    private fun uiName(ctx: Context, code: String): String {
        val parts = code.split('-')
        return Locale(if (parts[0] == "he") "iw" else parts[0], parts.getOrNull(1)?.takeIf { it.length == 2 } ?: "").getDisplayName(uiLocale(ctx))
            .replaceFirstChar { it.titlecase(uiLocale(ctx)) }.ifEmpty { code }
    }

    fun bind(pref: RdLanguagePreference, holder: PreferenceViewHolder) {
        val ctx = page.context ?: return
        val l = pref.language
        val preferred = OnlineSubtitles.preferredLanguage(app)
        val second = OnlineSubtitles.normalize(org.videolan.tools.Settings.getInstance(app).getString(OnlineSubtitles.KEY_SECOND, null))
        val role = when (l.code) {
            preferred -> ctx.getString(R.string.rd_preferred_language)
            second -> ctx.getString(R.string.rd_os_second_short)
            else -> if (l.code in OnlineSubtitles.pickedLanguages(app)) ctx.getString(R.string.rd_os_picked_in_player) else ""
        }
        val name = uiName(ctx, l.code)
        val native = OnlineSubtitles.nativeName(l.code)
        (holder.findViewById(android.R.id.summary) as? TextView)?.let {
            it.text = listOf(if (name != native) name else "", role).filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
            it.isVisible = it.text.isNotEmpty()
        }
        holder.findViewById(R.id.rd_svc_note)?.isVisible = false
        holder.findViewById(R.id.rd_svc_account)?.isVisible = false
        (holder.findViewById(R.id.rd_svc_switch) as? RdToggleView)?.isChecked = l.on
        holder.findViewById(R.id.rd_svc_main)?.setOnClickListener { set(l.code) { it.copy(on = !it.on) } }
        holder.findViewById(R.id.rd_svc_more)?.let { more ->
            more.contentDescription = ctx.getString(R.string.more_actions) + ", " + native
            more.setOnClickListener { menu(l, preferred, it) }
            // (the preferred language alone in the list: nothing to move or remove, no menu)
            val all = OnlineSubtitles.searchLanguages(app)
            more.visibility = if (all.size > 1 || l.code != preferred) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun set(code: String, change: (SearchLanguage) -> SearchLanguage) {
        OnlineSubtitles.setSearchLanguages(app, OnlineSubtitles.searchLanguages(app).map { if (it.code == code) change(it) else it })
        build()
    }

    private fun menu(l: SearchLanguage, preferred: String, anchor: View) {
        val list = OnlineSubtitles.searchLanguages(app)
        val i = list.indexOfFirst { it.code == l.code }
        val popup = PopupMenu(anchor.context, anchor)
        if (i > 0) popup.menu.add(0, 1, 0, R.string.rd_os_move_up)
        if (i in 0 until list.size - 1) popup.menu.add(0, 2, 1, R.string.rd_os_move_down)
        if (l.code != preferred) popup.menu.add(0, 3, 2, R.string.rd_remove)
        if (popup.menu.size() == 0) return
        popup.setOnMenuItemClickListener { item ->
            val now = OnlineSubtitles.searchLanguages(app).toMutableList()
            val at = now.indexOfFirst { it.code == l.code }
            if (at < 0) return@setOnMenuItemClickListener true
            when (item.itemId) {
                1 -> if (at > 0) now.add(at - 1, now.removeAt(at))
                2 -> if (at < now.size - 1) now.add(at + 1, now.removeAt(at))
                3 -> now.removeAt(at)
            }
            OnlineSubtitles.setSearchLanguages(app, now)
            build()
            // the moved row keeps the focus (the TV remote: OK, then Up or Down again); a removed one's, the next
            if (item.itemId != 3) page.listView?.post { focusRow(l.code) }
            else page.rdFocusRow(now.getOrNull(at)?.let { RdLanguagePreference.KEY_PREFIX + it.code } ?: KEY_ADD)
            true
        }
        popup.show()
    }

    private fun focusRow(code: String) {
        page.scrollToPreference(RdLanguagePreference.KEY_PREFIX + code)
        page.listView?.post {
            val list = page.listView ?: return@post
            for (i in 0 until list.childCount) {
                val v = list.getChildAt(i)
                if (v.findViewById<TextView>(android.R.id.title)?.text == OnlineSubtitles.nativeName(code)) {
                    v.findViewById<View>(R.id.rd_svc_more)?.requestFocus()
                    break
                }
            }
        }
    }

    /** every language the services know, but those of the list: (code, "עברית · Hebrew") */
    private fun choices(ctx: Context): List<Pair<String, String>> {
        val have = OnlineSubtitles.searchLanguages(app).map { it.code }.toSet()
        val codes = LinkedHashSet<String>()
        // (the two-letter languages, and those the services name with three: Android's list has hundreds more)
        for (l in Locale.getISOLanguages()) {
            val code = OnlineSubtitles.normalize(l)
            if (code.length == 2 && OnlineSubtitles.openSubtitlesCode(code).isNotEmpty()) codes.add(code)
        }
        codes.addAll(org.videolan.vlc.util.KeyedSubtitles.languageCodes)
        codes.addAll(listOf("pt-BR", "zh-TW", "es-419"))
        return codes.filter { it !in have }.map { code ->
            val native = OnlineSubtitles.nativeName(code)
            val name = uiName(ctx, code)
            Pair(code, if (native.equals(name, true)) name else "$name  ·  $native")
        }.sortedBy { it.second.lowercase(uiLocale(ctx)) }
    }

    private fun addLanguage() {
        val activity = page.activity ?: return
        val ctx: Context = activity
        val all = choices(ctx)
        val shown = ArrayList(all)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ctx.rdDp(16f).toInt()
            setPadding(pad, ctx.rdDp(4f).toInt(), pad, 0)
        }
        val field = EditText(ctx).apply {
            hint = ctx.getString(R.string.rd_os_find_language)
            isSingleLine = true
        }
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, shown.map { it.second }.toMutableList())
        val list = ListView(ctx).apply { this.adapter = adapter }
        box.addView(field)
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.rdDp(320f).toInt()))
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.rd_os_add_language)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .create()
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val words = s?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
                shown.clear()
                shown.addAll(all.filter { words.isEmpty() || words in it.second.lowercase(Locale.getDefault()) || words == it.first.lowercase(Locale.ROOT) })
                adapter.clear()
                adapter.addAll(shown.map { it.second })
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val code = shown.getOrNull(position)?.first ?: return@setOnItemClickListener
            OnlineSubtitles.setSearchLanguages(app, OnlineSubtitles.searchLanguages(app) + SearchLanguage(code, true))
            dialog.dismiss()
            build()
            page.listView?.post { focusRow(code) }
        }
        dialog.show()
        org.videolan.vlc.gui.helpers.RdTvFocusRing.attach(dialog.window)
    }

    companion object {
        const val CATEGORY = "rd_sub_langs"
        const val KEY_ADD = "rd_sub_langs_add"
    }
}
