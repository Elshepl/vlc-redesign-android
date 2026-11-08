/*
 * VLC Redesign: Settings › Subtitles › Subtitle services, as on the desktop and the Samsung TV app: one row per
 * service (OnlineSubtitles.providers, in their order) with what it has and its switch; a service of a free key or
 * account also shows what the user gave it (its fields, drawn from what it declares: a key, a user name or an
 * email, a password), Test or Sign in (one request: "Key works", "Key refused", "Signed in as …", "Wrong user name
 * or password", "No answer, try again"), Clear or Sign out, and Get a free key or Create a free account (its page).
 * A service is turned off with its switch, or taken out of the list in its menu (Remove); Add a service brings it
 * back. A key or sign-in refused by a search, or a busy service, is said on its row; a service of one language says
 * which language turns it on. Turning on a service without its key or account opens its fields.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.preferences.rd

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.rdAccent
import org.videolan.vlc.util.KeyedSubtitles
import org.videolan.vlc.util.OnlineSubtitles
import org.videolan.vlc.util.OnlineSubtitles.Field
import org.videolan.vlc.util.OnlineSubtitles.Provider
import org.videolan.vlc.util.RdCompat
import org.videolan.vlc.util.SubtitleSecrets
import java.util.Locale

/** A service's row (its layout: rd_pref_service); drawn by RdSubtitleServices. */
class RdServicePreference(context: Context, val provider: Provider, private val host: RdSubtitleServices) : Preference(context) {
    init {
        layoutResource = R.layout.rd_pref_service
        isPersistent = false
        isSelectable = false
        isIconSpaceReserved = false
        key = KEY_PREFIX + provider.id
        title = provider.name
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // (the row holds the controls; it is not one itself)
        holder.itemView.isFocusable = false
        holder.itemView.isClickable = false
        host.bind(this, holder)
    }

    fun update() = notifyChanged()

    companion object {
        const val KEY_PREFIX = "rd_sub_service_row_"
    }
}

class RdSubtitleServices(private val page: PreferenceFragmentCompat) {
    private val app: Context = page.requireContext().applicationContext
    /** what each service's well shows (read off the main thread: the values are encrypted) */
    private val wells = HashMap<String, String>()
    /** the last check of a service: its text, whether it worked */
    private val results = HashMap<String, Pair<String, Boolean>>()

    fun setup() {
        page.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                SubtitleSecrets.preload(app)
                for (p in OnlineSubtitles.providers) if (p.keyed) wells[p.id] = wellText(p)
            }
            build()
        }
    }

    private fun group() = page.findPreference<PreferenceCategory>(CATEGORY)

    /** the rows of the services in the list, then Add a service when some were removed */
    fun build() {
        val ctx = page.context ?: return
        val group = group() ?: return
        for (i in group.preferenceCount - 1 downTo 0) if (group.getPreference(i) is RdServicePreference) group.removePreference(group.getPreference(i))
        page.findPreference<Preference>(KEY_HELP)?.order = 0
        val removed = OnlineSubtitles.removedServices(app)
        OnlineSubtitles.providers.filter { it.id !in removed }.forEachIndexed { i, p ->
            group.addPreference(RdServicePreference(ctx, p, this).apply {
                order = 10 + i
                summary = detail(ctx, p)
            })
        }
        page.findPreference<Preference>(KEY_ADD)?.apply {
            order = 1000
            isVisible = removed.isNotEmpty()
            summary = removed.mapNotNull { OnlineSubtitles.provider(it)?.name }.joinToString("  ·  ")
        }
    }

    /** the rows drawn again (a switch, a check, a state changed) */
    fun refresh() {
        val group = group() ?: return
        for (i in 0 until group.preferenceCount) (group.getPreference(i) as? RdServicePreference)?.update()
    }

    private fun row(id: String) = group()?.findPreference<RdServicePreference>(RdServicePreference.KEY_PREFIX + id)

    /** a row of the page tapped: true when it was one of the services' */
    fun click(key: String): Boolean {
        if (key != KEY_ADD) return false
        val ctx = page.context ?: return true
        val removed = OnlineSubtitles.removedServices(app).mapNotNull { OnlineSubtitles.provider(it) }
        if (removed.isEmpty()) return true
        val dialog = AlertDialog.Builder(page.requireActivity())
            .setTitle(R.string.rd_os_add_service)
            .setItems(removed.map { it.name + "\n" + detail(ctx, it) }.toTypedArray()) { _, which ->
                OnlineSubtitles.setRemoved(app, removed[which].id, false)
                build()
                page.scrollToPreference(RdServicePreference.KEY_PREFIX + removed[which].id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        org.videolan.vlc.gui.helpers.RdTvFocusRing.attach(dialog.window)
        return true
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A row

    fun bind(pref: RdServicePreference, holder: PreferenceViewHolder) {
        val ctx = page.context ?: return
        val p = pref.provider
        val has = OnlineSubtitles.hasCredentials(app, p)
        val on = OnlineSubtitles.switchOn(app, p.id) && has
        (holder.findViewById(R.id.rd_svc_switch) as? RdToggleView)?.isChecked = on
        holder.findViewById(R.id.rd_svc_main)?.setOnClickListener { switchClicked(p) }
        (holder.findViewById(android.R.id.summary) as? TextView)?.text = detail(ctx, p)
        // its note: a refused key or sign-in, busy, the language that turns it on
        (holder.findViewById(R.id.rd_svc_note) as? TextView)?.let { note ->
            val state = OnlineSubtitles.serviceState(app, p.id)
            val text = when (state) {
                OnlineSubtitles.ServiceState.KEY_REFUSED -> ctx.getString(R.string.rd_os_key_refused_check)
                OnlineSubtitles.ServiceState.LOGIN_REFUSED -> ctx.getString(R.string.rd_os_login_refused_check)
                OnlineSubtitles.ServiceState.BUSY -> ctx.getString(R.string.rd_os_busy_minute)
                null -> if (p.languages.isNotEmpty()) ctx.getString(R.string.rd_os_only_when, languageNames(ctx, p)) else ""
            }
            note.text = text
            note.isVisible = text.isNotEmpty()
            note.setTextColor(if (state != null) ctx.rdAccent() else ContextCompat.getColor(ctx, R.color.rd_fg_a3))
        }
        holder.findViewById(R.id.rd_svc_more)?.let { more ->
            more.contentDescription = ctx.getString(R.string.more_actions) + ", " + p.name
            more.setOnClickListener { menu(p, it) }
        }
        val account = holder.findViewById(R.id.rd_svc_account) ?: return
        account.isVisible = p.keyed
        if (!p.keyed) return
        (holder.findViewById(R.id.rd_svc_fields) as? TextView)?.let { well ->
            val shown = wells[p.id] ?: ""
            well.text = shown.ifEmpty { placeholder(ctx, p) }
            well.alpha = if (shown.isEmpty()) 0.6f else 1f
            well.contentDescription = p.name + ", " + well.text
            well.setOnClickListener { editCredentials(p, fromSwitch = false) }
        }
        (holder.findViewById(R.id.rd_svc_buttons) as? ViewGroup)?.let { box ->
            box.removeAllViews()
            val signIn = p.login && Field.KEY !in p.fields
            box.addView(button(ctx, ctx.getString(if (signIn) R.string.rd_os_sign_in else R.string.rd_os_test), enabled = has) { check(p) })
            if (p.fields.any { SubtitleSecrets.has(app, p.id, it.pref) })
                box.addView(button(ctx, ctx.getString(if (p.login && signIn) R.string.rd_os_sign_out else R.string.rd_os_clear), enabled = true) { clear(p) })
            box.addView(button(ctx, ctx.getString(if (signIn) R.string.rd_os_create_account else R.string.rd_os_get_key), enabled = true) { openPage(p) })
        }
        (holder.findViewById(R.id.rd_svc_result) as? TextView)?.let { result ->
            val r = results[p.id]
            result.isVisible = r != null
            result.text = r?.first ?: ""
            result.setTextColor(if (r?.second == false) ctx.rdAccent() else ContextCompat.getColor(ctx, R.color.rd_fg_a2))
        }
    }

    private fun button(ctx: Context, text: String, enabled: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text
        gravity = android.view.Gravity.CENTER
        minWidth = ctx.rdDp(72f).toInt()
        minHeight = ctx.rdDp(36f).toInt()
        setPadding(ctx.rdDp(14f).toInt(), 0, ctx.rdDp(14f).toInt(), 0)
        background = ContextCompat.getDrawable(ctx, R.drawable.rd_pref_button_bg)
        typeface = ResourcesCompat.getFont(ctx, R.font.inter_medium)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_2))
        isSingleLine = true
        isFocusable = true
        isClickable = true
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        setOnClickListener { onClick() }
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.rdDp(36f).toInt())
    }

    private fun menu(p: Provider, anchor: View) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, 1, 0, R.string.rd_remove)
        popup.setOnMenuItemClickListener {
            OnlineSubtitles.setRemoved(app, p.id, true)
            build()
            true
        }
        popup.show()
    }

    private fun switchClicked(p: Provider) {
        val has = OnlineSubtitles.hasCredentials(app, p)
        val on = OnlineSubtitles.switchOn(app, p.id) && has
        // on without its key or account: its fields first
        if (!on && !has) {
            editCredentials(p, fromSwitch = true)
            return
        }
        OnlineSubtitles.setServiceEnabled(app, p.id, !on)
        row(p.id)?.update()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Its key or account

    private fun label(ctx: Context, p: Provider, f: Field): String = when (f) {
        Field.KEY -> ctx.getString(if (p.token) R.string.rd_os_your_token else R.string.rd_os_your_key)
        Field.USERNAME -> ctx.getString(R.string.rd_os_username)
        Field.PASSWORD -> ctx.getString(R.string.rd_os_password)
        Field.EMAIL -> ctx.getString(R.string.rd_os_email)
    }

    /** "Your free key", "Username · Password": what the well asks for while it is empty */
    private fun placeholder(ctx: Context, p: Provider) = p.needs.ifEmpty { p.fields }.joinToString("  ·  ") { label(ctx, p, it) }

    /** "••••••••", "chen  ·  ••••••••" (off the main thread) */
    private fun wellText(p: Provider) = p.fields.mapNotNull { f ->
        if (!SubtitleSecrets.has(app, p.id, f.pref)) null
        else if (f == Field.KEY || f == Field.PASSWORD) "••••••••" else OnlineSubtitles.credential(app, p.id, f)
    }.filter { it.isNotEmpty() }.joinToString("  ·  ")

    private fun editCredentials(p: Provider, fromSwitch: Boolean) {
        page.lifecycleScope.launch {
            val values = withContext(Dispatchers.IO) { p.fields.associateWith { OnlineSubtitles.credential(app, p.id, it) } }
            if (page.isAdded) showFields(p, values, fromSwitch)
        }
    }

    private fun showFields(p: Provider, values: Map<Field, String>, fromSwitch: Boolean) {
        val activity = page.requireActivity()
        val ctx: Context = activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ctx.rdDp(20f).toInt()
            setPadding(pad, ctx.rdDp(8f).toInt(), pad, 0)
        }
        val edits = LinkedHashMap<Field, EditText>()
        p.fields.forEachIndexed { i, f ->
            val name = label(ctx, p, f).let { if (f in p.optional) ctx.getString(R.string.rd_os_optional, it) else it }
            box.addView(TextView(ctx).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(ctx, R.color.rd_fg_a3))
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(0, if (i > 0) ctx.rdDp(10f).toInt() else 0, 0, 0)
            })
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val secret = f == Field.KEY || f == Field.PASSWORD
            val edit = EditText(ctx).apply {
                setText(values[f] ?: "")
                hint = name
                isSingleLine = true
                inputType = when (f) {
                    // (no suggestions: a keyboard must not learn a key)
                    Field.KEY -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    Field.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    Field.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    Field.USERNAME -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
                textDirection = if (f == Field.USERNAME) View.TEXT_DIRECTION_FIRST_STRONG else View.TEXT_DIRECTION_LTR
                imeOptions = if (i == p.fields.size - 1) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                setSelection(text.length)
            }
            line.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (secret) line.addView(ImageView(ctx).apply {
                val pad = ctx.rdDp(10f).toInt()
                setPadding(pad, pad, pad, pad)
                setImageResource(R.drawable.ic_visible)
                imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.rd_fg_a2))
                background = ContextCompat.getDrawable(ctx, R.drawable.rd_pref_button_bg)
                isFocusable = true
                isClickable = true
                contentDescription = ctx.getString(R.string.rd_os_show_text) + ", " + name
                setOnClickListener {
                    val hidden = edit.transformationMethod != null
                    edit.transformationMethod = if (hidden) null else PasswordTransformationMethod.getInstance()
                    edit.setSelection(edit.text.length)
                    setImageResource(if (hidden) R.drawable.ic_invisible else R.drawable.ic_visible)
                    contentDescription = ctx.getString(if (hidden) R.string.rd_os_hide_text else R.string.rd_os_show_text) + ", " + name
                }
            }, LinearLayout.LayoutParams(ctx.rdDp(44f).toInt(), ctx.rdDp(44f).toInt()).apply { marginStart = ctx.rdDp(6f).toInt() })
            box.addView(line)
            edits[f] = edit
        }
        val signIn = p.login
        val dialog = AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(if (signIn) R.string.rd_os_sign_in else R.string.save) { _, _ -> save(p, values, edits.mapValues { it.value.text.toString() }, fromSwitch) }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(if (signIn && Field.KEY !in p.fields) R.string.rd_os_create_account else R.string.rd_os_get_key) { _, _ -> openPage(p) }
            .create()
        dialog.show()
        org.videolan.vlc.gui.helpers.RdTvFocusRing.attach(dialog.window)
        // the first field to fill has the focus
        (edits.values.firstOrNull { it.text.isEmpty() } ?: edits.values.firstOrNull())?.requestFocus()
    }

    private fun save(p: Provider, before: Map<Field, String>, typed: Map<Field, String>, fromSwitch: Boolean) {
        page.lifecycleScope.launch {
            val changed = withContext(Dispatchers.IO) {
                var changed = false
                for ((f, raw) in typed) {
                    val v = if (f == Field.PASSWORD) raw else raw.trim()
                    if (v != (before[f] ?: "")) {
                        SubtitleSecrets.set(app, p.id, f.pref, v)
                        changed = true
                    }
                }
                if (changed) OnlineSubtitles.setServiceState(app, p.id, null)
                wells[p.id] = wellText(p)
                changed
            }
            val has = OnlineSubtitles.hasCredentials(app, p)
            // turned on with its switch: on now that it has them
            if (fromSwitch && has) OnlineSubtitles.setServiceEnabled(app, p.id, true)
            results.remove(p.id)
            row(p.id)?.update()
            // checked at once: the user sees whether the key or the account works
            if (has && (changed || fromSwitch)) check(p)
        }
    }

    private fun check(p: Provider) {
        val ctx = page.context ?: return
        results[p.id] = Pair(ctx.getString(R.string.rd_os_testing), true)
        row(p.id)?.update()
        page.lifecycleScope.launch {
            val r = KeyedSubtitles.check(app, p.id)
            val c = page.context ?: return@launch
            results[p.id] = when (r.check) {
                KeyedSubtitles.Check.KEY_WORKS -> Pair(c.getString(R.string.rd_os_key_works), true)
                KeyedSubtitles.Check.SIGNED_IN -> Pair(c.getString(R.string.rd_os_signed_in_as, r.user), true)
                KeyedSubtitles.Check.KEY_REFUSED -> Pair(c.getString(R.string.rd_os_key_refused_short), false)
                KeyedSubtitles.Check.LOGIN_REFUSED -> Pair(c.getString(R.string.rd_os_wrong_login), false)
                KeyedSubtitles.Check.BUSY -> Pair(c.getString(R.string.rd_os_busy_minute), false)
                KeyedSubtitles.Check.NO_ANSWER -> Pair(c.getString(R.string.rd_os_no_answer_retry), false)
            }
            row(p.id)?.update()
        }
    }

    private fun clear(p: Provider) {
        page.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                SubtitleSecrets.clear(app, p.id)
                OnlineSubtitles.setServiceState(app, p.id, null)
                wells[p.id] = ""
            }
            results.remove(p.id)
            row(p.id)?.update()
        }
    }

    private fun openPage(p: Provider) {
        val activity = page.activity ?: return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.page)))
        } catch (e: ActivityNotFoundException) {
            // no browser (a TV): where to go, on another device
            val message = if (p.id == "opensubtitlescom") activity.getString(R.string.rd_os_key_help)
            else activity.getString(R.string.rd_os_open_elsewhere, p.page.removePrefix("https://"))
            val dialog = AlertDialog.Builder(activity).setTitle(p.name).setMessage(message).setPositiveButton(R.string.ok, null).show()
            org.videolan.vlc.gui.helpers.RdTvFocusRing.attach(dialog.window)
        } catch (e: Exception) {
        }
    }

    companion object {
        const val CATEGORY = "rd_sub_services"
        const val KEY_HELP = "rd_sub_services_help"
        const val KEY_ADD = "rd_sub_services_add"

        /** what a service has, in the interface's language */
        fun detail(ctx: Context, p: Provider): String = ctx.getString(when (p.id) {
            "opensubtitles" -> R.string.rd_os_svc_opensubtitles
            "opensubtitlescom" -> R.string.rd_os_svc_oscom
            "subsource" -> R.string.rd_os_svc_subsource
            "subdl" -> R.string.rd_os_svc_subdl
            "wizdom" -> R.string.rd_os_svc_wizdom
            "addic7ed" -> R.string.rd_os_svc_addic7ed
            "yify" -> R.string.rd_os_svc_yify
            "titlovi" -> R.string.rd_os_svc_titlovi
            "assrt" -> R.string.rd_os_svc_assrt
            "jimaku" -> R.string.rd_os_svc_jimaku
            "betaseries" -> R.string.rd_os_svc_betaseries
            "subsro" -> R.string.rd_os_svc_subsro
            "napisy24" -> R.string.rd_os_svc_napisy24
            "legendasnet" -> R.string.rd_os_svc_legendasnet
            else -> R.string.rd_os_services
        })

        /** "Hebrew", "Croatian / Serbian / …": the languages that turn a service on, in the interface's language */
        fun languageNames(ctx: Context, p: Provider): String {
            val ui = RdCompat.primaryLocale(ctx.resources.configuration)
            return p.languages.joinToString(" / ") { code ->
                val parts = code.split('-')
                Locale(if (parts[0] == "he") "iw" else parts[0], parts.getOrNull(1)?.takeIf { it.length == 2 } ?: "").getDisplayName(ui).ifEmpty { code }
            }
        }
    }
}
