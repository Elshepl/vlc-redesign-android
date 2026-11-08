/*
 * VLC Redesign: subtitles from the internet for the video playing, as the desktop's subtitles panel finds them
 * (OnlineSubtitles): "Identifying the video…", "Looking for subtitles…", then what it was taken for and the
 * subtitles found, by language (the user's first; in each, those made for this very file first), each with its
 * service. A row downloads that subtitle, adds it to the video, shows it and keeps it for the file; the sheet then
 * closes. "The video is another one: search by its title" finds the right title, kept for the file. Without the
 * user's own OpenSubtitles.com key, a line says where to get one, free.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.dialogs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.tools.dp
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.gui.helpers.rdAccent
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.util.OnlineSubtitles
import org.videolan.vlc.util.OnlineSubtitles.Problem
import org.videolan.vlc.util.RdToast

class RdOnlineSubtitlesSheet : VLCBottomSheetDialogFragment() {

    private lateinit var status: TextView
    private lateinit var rows: LinearLayout
    private lateinit var busy: ProgressBar
    private lateinit var again: ImageView
    private lateinit var another: TextView
    private lateinit var titleBox: View
    private lateinit var titleField: EditText
    private lateinit var keyHint: TextView
    private var job: Job? = null
    private var media: OnlineSubtitles.Media? = null
    private var loading: String? = null

    override fun getDefaultState() = BottomSheetBehavior.STATE_EXPANDED

    override fun needToManageOrientation() = true

    override fun allowRemote() = true

    override fun initialFocusedView(): View = again

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.dialog_rd_online_subtitles, container, false)
        status = root.findViewById(R.id.rd_os_status)
        rows = root.findViewById(R.id.rd_os_rows)
        busy = root.findViewById(R.id.rd_os_busy)
        again = root.findViewById(R.id.rd_os_again)
        another = root.findViewById(R.id.rd_os_another)
        titleBox = root.findViewById(R.id.rd_os_title_box)
        titleField = root.findViewById(R.id.rd_os_title_field)
        keyHint = root.findViewById(R.id.rd_os_key_hint)
        again.setOnClickListener { search(null, identify = true) }
        another.setOnClickListener {
            another.isVisible = false
            titleBox.isVisible = true
            titleField.setText(media?.title ?: "")
            titleField.requestFocus()
        }
        val find = { findTitles(titleField.text.toString()) }
        root.findViewById<View>(R.id.rd_os_title_go).setOnClickListener { find() }
        titleField.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                find(); true
            } else false
        }
        keyHint.isVisible = OnlineSubtitles.oscomKey(requireContext()).isEmpty()
        keyHint.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OnlineSubtitles.OSCOM_KEY_PAGE)))
            } catch (e: Exception) {
                // (no browser, a TV: the address is in the text)
            }
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        search(null, identify = true)
    }

    private fun service(): PlaybackService? = (activity as? VideoPlayerActivity)?.service ?: PlaybackService.instance

    private fun setBusy(on: Boolean, text: String) {
        busy.isVisible = on
        again.isEnabled = !on
        status.text = text
    }

    private fun search(match: OnlineSubtitles.Match?, identify: Boolean) {
        val mw = service()?.currentMediaWrapper ?: return
        val ctx = requireContext().applicationContext
        job?.cancel()
        rows.removeAllViews()
        setBusy(true, getString(R.string.rd_os_identifying))
        job = lifecycleScope.launch {
            val m = media ?: withContext(Dispatchers.IO) { OnlineSubtitles.mediaOf(mw.uri, mw.title) }.also { media = it }
            val found = if (identify) withContext(Dispatchers.IO) { OnlineSubtitles.identify(ctx, m) } else match
            setBusy(true, getString(R.string.rd_os_looking))
            show(OnlineSubtitles.search(ctx, m, found))
        }
    }

    private fun show(outcome: OnlineSubtitles.Outcome) {
        val ctx = requireContext()
        busy.isVisible = false
        again.isEnabled = true
        rows.removeAllViews()
        val title = outcome.matchTitle
        if (outcome.results.isEmpty()) {
            status.text = when {
                !outcome.anyAnswered -> problemText(ctx, outcome.error ?: Problem.Offline)
                outcome.match == null && outcome.media.title.isEmpty() -> getString(R.string.rd_os_err_unidentified)
                outcome.match == null -> getString(R.string.rd_os_err_not_found_title, outcome.media.title)
                else -> getString(R.string.rd_os_err_none_for, title)
            }
            if (outcome.match == null) another.performClick()
            return
        }
        status.text = getString(R.string.rd_os_found, title.ifEmpty { outcome.media.title }, outcome.results.size.toString())
        var language: String? = null
        var inLanguage = 0
        for (ranked in outcome.results) {
            val r = ranked.result
            if (r.language != language) {
                language = r.language
                inLanguage = 0
                rows.addView(header(ctx, OnlineSubtitles.nativeName(r.language).ifEmpty { getString(R.string.rd_os_unknown_language) }))
            }
            // (the best of each language: a long list of one helps no one)
            if (++inLanguage > 25) continue
            rows.addView(row(ctx, ranked))
        }
    }

    private fun header(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = ResourcesCompat.getFont(ctx, R.font.inter_medium)
        setTextColor(ctx.rdAccent())
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding(20.dp, 14.dp, 20.dp, 4.dp)
        isFocusable = false
    }

    private fun row(ctx: Context, ranked: OnlineSubtitles.Ranked): View {
        val r = ranked.result
        val view = LayoutInflater.from(ctx).inflate(R.layout.item_rd_online_subtitle, rows, false)
        view.findViewById<TextView>(R.id.rd_os_row_release).text = r.release.ifEmpty { getString(R.string.subtitles) }
        val detail = view.findViewById<TextView>(R.id.rd_os_row_detail)
        detail.text = detailOf(ctx, ranked)
        view.contentDescription = "${r.release}, ${detail.text}"
        view.setOnClickListener {
            if (loading != null) return@setOnClickListener
            load(ranked, detail, view.findViewById(R.id.rd_os_row_check))
        }
        return view
    }

    private fun detailOf(ctx: Context, ranked: OnlineSubtitles.Ranked): String {
        val r = ranked.result
        val parts = ArrayList<String>()
        parts.add(OnlineSubtitles.providers.firstOrNull { it.id == r.provider }?.name ?: r.provider)
        if (r.exact) parts.add(ctx.getString(R.string.rd_os_for_file))
        if (ranked.hearingImpaired) parts.add(ctx.getString(R.string.rd_os_hi))
        if (r.foreignOnly) parts.add(ctx.getString(R.string.rd_os_foreign))
        if (r.downloads >= 1000) parts.add(ctx.getString(R.string.rd_os_downloads_k, ((r.downloads + 500) / 1000).toString()))
        else if (r.downloads > 0) parts.add(ctx.getString(R.string.rd_os_downloads, r.downloads.toString()))
        return parts.joinToString(" · ")
    }

    private fun load(ranked: OnlineSubtitles.Ranked, detail: TextView, check: ImageView) {
        val m = media ?: return
        val ctx = requireContext().applicationContext
        val activity = activity as? VideoPlayerActivity
        loading = ranked.result.id
        detail.text = getString(R.string.rd_os_downloading)
        lifecycleScope.launch {
            val (file, problem) = OnlineSubtitles.download(ctx, ranked.result, m)
            loading = null
            if (file == null) {
                detail.text = problemText(ctx, problem ?: Problem.Unpack)
                detail.setTextColor(ctx.rdAccent())
                return@launch
            }
            check.isVisible = true
            activity?.rdAddOnlineSubtitle(file, ranked.result, select = true)
            RdToast.makeText(ctx, loadedText(ctx, ranked.result), RdToast.LENGTH_SHORT).show()
            dismissAllowingStateLoss()
        }
    }

    private fun findTitles(text: String) {
        if (text.isBlank()) return
        job?.cancel()
        rows.removeAllViews()
        setBusy(true, getString(R.string.rd_os_searching))
        job = lifecycleScope.launch {
            val titles = withContext(Dispatchers.IO) { OnlineSubtitles.findTitles(text) }
            busy.isVisible = false
            again.isEnabled = true
            val ctx = requireContext()
            if (titles.isEmpty()) {
                status.text = getString(R.string.rd_os_err_not_found_title, text.trim())
                return@launch
            }
            status.text = getString(R.string.rd_os_another)
            for ((match, detail) in titles) {
                val view = LayoutInflater.from(ctx).inflate(R.layout.item_rd_online_subtitle, rows, false)
                view.findViewById<TextView>(R.id.rd_os_row_release).text = if (match.year > 0) "${match.title} (${match.year})" else match.title
                view.findViewById<TextView>(R.id.rd_os_row_detail).text = detail
                view.setOnClickListener {
                    media?.let { OnlineSubtitles.rememberMatch(ctx, it, match) }
                    titleBox.isVisible = false
                    another.isVisible = true
                    search(match, identify = false)
                }
                rows.addView(view)
            }
            rows.getChildAt(0)?.requestFocus()
        }
    }

    override fun onDestroyView() {
        job?.cancel()
        super.onDestroyView()
    }

    companion object {
        const val TAG = "rd_online_subtitles"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) RdOnlineSubtitlesSheet().show(manager, TAG)
        }

        /** "עברית · Downloaded from OpenSubtitles" */
        fun loadedText(ctx: Context, r: OnlineSubtitles.Result) = OnlineSubtitles.nativeName(r.language) + " · " +
            ctx.getString(R.string.rd_os_downloaded_from, OnlineSubtitles.providers.firstOrNull { it.id == r.provider }?.name ?: r.provider)

        fun problemText(ctx: Context, p: Problem): String = when (p) {
            is Problem.NoAnswer -> ctx.getString(R.string.rd_os_err_no_answer, p.service)
            is Problem.Busy -> ctx.getString(R.string.rd_os_err_busy, p.service)
            is Problem.Status -> ctx.getString(R.string.rd_os_err_status, p.service, p.code.toString())
            is Problem.KeyRefused -> ctx.getString(R.string.rd_os_key_refused, p.service)
            is Problem.Quota -> ctx.getString(R.string.rd_os_quota, p.service)
            Problem.LoginFailed -> ctx.getString(R.string.rd_os_login_failed)
            Problem.Rar -> ctx.getString(R.string.rd_os_err_rar)
            Problem.Unpack -> ctx.getString(R.string.rd_os_err_unpack)
            Problem.NoSubtitleInArchive -> ctx.getString(R.string.rd_os_err_no_sub)
            Problem.Vip -> ctx.getString(R.string.rd_os_err_vip)
            Problem.Empty -> ctx.getString(R.string.rd_os_err_empty)
            Problem.Save -> ctx.getString(R.string.rd_os_err_save)
            Problem.Offline -> ctx.getString(R.string.rd_os_err_offline)
            is Problem.Download -> ctx.getString(R.string.rd_os_err_download, p.code.toString())
        }
    }
}
