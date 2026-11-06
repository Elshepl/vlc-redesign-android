/*
 * HomeFragment.kt
 *
 * VLC Redesign: the Home page of the library (Figma 6261:15078, desktop docs/wiki/Library.md "Home").
 * Continue Watching, Listen Again, Frequently Visited Folders, Favorites, New Videos and New Music, the newest
 * first, one row of cards each; a section with nothing in it is hidden.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package org.videolan.vlc.gui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.PLAYBACK_HISTORY
import org.videolan.tools.Settings
import org.videolan.tools.retrieveParent
import org.videolan.vlc.R
import org.videolan.tools.dp
import org.videolan.vlc.gui.tablet.RdTvWindow
import org.videolan.vlc.databinding.RdHomeCardBinding
import org.videolan.vlc.gui.browser.KEY_MEDIA
import org.videolan.vlc.gui.dialogs.CtxActionReceiver
import org.videolan.vlc.gui.dialogs.SavePlaylistDialog
import org.videolan.vlc.gui.dialogs.showContext
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.UiTools.addToPlaylist
import org.videolan.vlc.gui.helpers.getAudioIconDrawable
import org.videolan.vlc.gui.helpers.loadImage
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.util.ContextOption
import org.videolan.vlc.util.ContextOption.*
import org.videolan.vlc.util.FlagSet
import org.videolan.vlc.gui.livetv.TvNavigation
import org.videolan.vlc.util.Pins
import org.videolan.vlc.util.share
import org.videolan.vlc.util.showParentFolder

private const val MAX_CARDS = 20

class HomeFragment : BaseFragment(), CtxActionReceiver, Medialibrary.HistoryCb, Medialibrary.MediaCb,
        Medialibrary.OnMedialibraryReadyListener {

    /** One card: a media, or a folder (a directory media) of Frequently Visited Folders. */
    private class Card(val media: MediaWrapper, val folder: Boolean = false, val playCount: Long = 0L, val pin: Pins.Pin? = null)

    private enum class Kind { VIDEO, AUDIO, FOLDER, MIXED }

    private inner class Section(root: View, titleRes: Int, val kind: Kind, onMore: (() -> Unit)?) {
        val view: View = root
        val list: RecyclerView = root.findViewById(R.id.rd_section_list)
        val adapter = CardAdapter(this)
        var cards: List<Card> = emptyList()

        init {
            root.findViewById<TextView>(R.id.rd_section_title).setText(titleRes)
            val header = root.findViewById<View>(R.id.rd_section_header)
            if (onMore == null) {
                root.findViewById<View>(R.id.rd_section_chevron).visibility = View.GONE
                header.isClickable = false
                header.background = null
            } else header.setOnClickListener { onMore() }
            list.layoutManager = LinearLayoutManager(root.context, LinearLayoutManager.HORIZONTAL, false)
            list.adapter = adapter
            if (RdTvWindow.isOn(root.context)) {
                // the design's section (.sh-sec): the title 20 in FG/2 at 28 with its chevron 24, the cards 21 under
                // it, 76 between a row and the next title
                (header.layoutParams as ViewGroup.MarginLayoutParams).apply { height = 24.dp; topMargin = 0; marginStart = 24.dp }
                header.setPaddingRelative(4.dp, 0, 4.dp, 0)
                root.findViewById<TextView>(R.id.rd_section_title).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f); setTextColor(RdTvWindow.FG_2); includeFontPadding = false
                }
                root.findViewById<View>(R.id.rd_section_chevron).layoutParams = (root.findViewById<View>(R.id.rd_section_chevron).layoutParams as ViewGroup.MarginLayoutParams).apply { width = 24.dp; height = 24.dp; marginStart = 6.dp }
                (list.layoutParams as ViewGroup.MarginLayoutParams).topMargin = 21.dp
                list.setPaddingRelative(28.dp, 0, 0, 0)
                (root.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = 76.dp
            }
        }

        private var signature = ""

        fun update(newCards: List<Card>) {
            // the library reports every thumbnail it makes as a change: redraw only when the cards changed
            val newSignature = newCards.joinToString("|") { c ->
                val m = c.media
                "${m.id}:${m.uri}:${m.title}:${m.displayTime}:${m.seen}:${m.isFavorite}:${m.artworkMrl}"
            }
            if (newSignature == signature && view.visibility == (if (newCards.isEmpty()) View.GONE else View.VISIBLE)) return
            signature = newSignature
            cards = newCards
            adapter.notifyDataSetChanged()
            view.visibility = if (newCards.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private inner class CardHolder(val binding: RdHomeCardBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class CardAdapter(val section: Section) : RecyclerView.Adapter<CardHolder>() {
        override fun getItemCount() = section.cards.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                CardHolder(RdHomeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val card = section.cards[position]
            val media = card.media
            val b = holder.binding
            val res = b.root.resources
            val video = !card.folder && media.type == MediaWrapper.TYPE_VIDEO
            // a 16:9 picture for a video, a square one for a song or a folder
            val tv = RdTvWindow.isOn(b.root.context)
            val width = if (tv) (if (video) 316.dp else 200.dp) else res.getDimensionPixelSize(if (video) R.dimen.rd_home_video_width else R.dimen.rd_home_square_width)
            val height = if (tv && video) 178.dp else if (video) width * 9 / 16 else width
            if (tv) {
                // the design's card (.sh-card): no frame around it, 20 to the next; the picture radius 8; the name
                // 16 in FG/2, 20 under the picture; the artist 16 in FG/A1, 10 under the name
                (b.root.layoutParams as ViewGroup.MarginLayoutParams).apply { marginStart = 0; marginEnd = 20.dp }
                b.root.setPadding(0, 0, 0, 0)
                b.rdCoverCard.radius = 8.dp.toFloat()
                for ((t, top) in listOf(b.rdTitle to 20, b.rdSubtitle to 10)) {
                    t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
                    t.includeFontPadding = false
                    t.setLineSpacing(0f, 1f)
                    (t.layoutParams as ViewGroup.MarginLayoutParams).topMargin = top.dp
                }
            }
            b.rdCoverCard.layoutParams = b.rdCoverCard.layoutParams.apply { this.width = width; this.height = height }
            b.rdTitle.layoutParams = b.rdTitle.layoutParams.apply { this.width = width }
            b.rdSubtitle.layoutParams = b.rdSubtitle.layoutParams.apply { this.width = width }
            b.isFolder = card.folder
            b.media = media
            b.title = media.title
            b.subtitle = when {
                card.folder -> null
                media.type == MediaWrapper.TYPE_AUDIO -> media.artistName
                else -> null
            }
            b.seen = video && media.seen > 0L && section.kind != Kind.VIDEO
            val progress = if (video && media.length > 0L && media.displayTime > 0L) (media.displayTime * 1000L / media.length).toInt().coerceIn(0, 1000) else 0
            b.progress = progress
            b.rdProgress.layoutParams = b.rdProgress.layoutParams.apply { this.width = width * progress / 1000 }
            b.scaleType = ImageView.ScaleType.CENTER_CROP
            b.cover = if (card.folder) null else if (card.pin != null) UiTools.getDefaultPlaylistDrawable(b.root.context)
                    else if (video) UiTools.getDefaultVideoDrawable(b.root.context)
                    else getAudioIconDrawable(b.root.context, MediaWrapper.TYPE_AUDIO, true)
            if (!card.folder) b.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.executePendingBindings()
            if (tv) {
                // the design's folder glyph (shell.css .sh-card-thumb.ph: 48 in #8F8F8F)
                b.rdFolderGlyph.setImageResource(R.drawable.rd_tvd_folder)
                b.rdFolderGlyph.setColorFilter(RdTvWindow.ICON)
                b.rdFolderGlyph.layoutParams = b.rdFolderGlyph.layoutParams.also { it.width = 48.dp; it.height = 48.dp }
                b.rdTitle.setTextColor(if (b.seen == true) 0xFF6B6B6B.toInt() else RdTvWindow.FG_2)
                b.rdSubtitle.setTextColor(0xFF6B6B6B.toInt())
            }
            if (!card.folder && card.pin == null) loadImage(b.rdCover, media, width, card = true)
            b.root.contentDescription = media.title
            b.root.setOnClickListener { onCardClick(section, holder.bindingAdapterPosition) }
            b.root.setOnLongClickListener {
                onCardLongClick(section, holder.bindingAdapterPosition)
                true
            }
        }
    }

    private lateinit var sections: List<Section>
    private lateinit var pinned: Section
    private lateinit var continueWatching: Section
    private lateinit var listenAgain: Section
    private lateinit var folders: Section
    private lateinit var favorites: Section
    private lateinit var newVideos: Section
    private lateinit var newMusic: Section
    private lateinit var emptyView: View
    private var loadJob: Job? = null
    private var ctxSection: Section? = null

    override fun getTitle() = getString(R.string.home)

    override fun hasFAB() = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            inflater.inflate(R.layout.rd_home_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyView = view.findViewById(R.id.rd_home_empty)
        // the design's Home: the first title 35 under the header's line (.sh-home)
        if (RdTvWindow.isOn(view.context)) view.findViewById<View>(R.id.rd_home_scroll).let { it.setPadding(it.paddingLeft, 36.dp, it.paddingRight, 40.dp) }
        continueWatching = Section(view.findViewById(R.id.rd_home_continue), R.string.rd_continue_watching, Kind.VIDEO) { openSecondary(SecondaryActivity.HISTORY) }
        listenAgain = Section(view.findViewById(R.id.rd_home_listen), R.string.rd_listen_again, Kind.AUDIO) { openSecondary(SecondaryActivity.HISTORY) }
        folders = Section(view.findViewById(R.id.rd_home_folders), R.string.rd_frequent_folders, Kind.FOLDER) { selectTab(R.id.nav_directories) }
        favorites = Section(view.findViewById(R.id.rd_home_favorites), R.string.favorites, Kind.MIXED, null)
        newVideos = Section(view.findViewById(R.id.rd_home_new_videos), R.string.rd_new_videos, Kind.MIXED) { selectTab(R.id.nav_video) }
        newMusic = Section(view.findViewById(R.id.rd_home_new_music), R.string.rd_new_music, Kind.MIXED) { selectTab(R.id.nav_audio) }
        pinned = Section(view.findViewById(R.id.rd_home_pinned), R.string.rd_pinned, Kind.FOLDER, null)
        sections = listOf(pinned, continueWatching, listenAgain, folders, favorites, newVideos, newMusic)
        // a folder or a playlist pinned or unpinned anywhere
        lifecycleScope.launch { Pins.pins(view.context).collect { if (it != null) refresh() } }
        // moved or hidden in Edit Sidebar
        lifecycleScope.launch { Pins.layout.drop(1).collect { refresh() } }
    }

    /** The pins, on Home only when the window has no sidebar to show them; in Edit Sidebar's order, the hidden left out */
    private fun pinnedCards(context: android.content.Context): List<Card> {
        if (context.resources.getBoolean(R.bool.rd_tablet_sidebar)) return emptyList()
        return TvNavigation.shownPins(context).map { pin ->
            val uri = if (pin.kind == Pins.FOLDER) Uri.parse(pin.id) else Uri.parse("${pin.kind}:${pin.id}")
            val media = MLServiceLocator.getAbstractMediaWrapper(uri).apply { title = pin.title; type = MediaWrapper.TYPE_DIR }
            Card(media, folder = pin.kind != Pins.PLAYLIST, pin = pin)
        }
    }

    override fun onStart() {
        super.onStart()
        (activity as? ContentActivity)?.setTabLayoutVisibility(false)
        Medialibrary.getInstance().addHistoryCb(this)
        Medialibrary.getInstance().addMediaCb(this)
        Medialibrary.getInstance().addOnMedialibraryReadyListener(this)
        refresh()
    }

    override fun onStop() {
        Medialibrary.getInstance().removeHistoryCb(this)
        Medialibrary.getInstance().removeMediaCb(this)
        Medialibrary.getInstance().removeOnMedialibraryReadyListener(this)
        super.onStop()
    }

    // The library calls these from its own threads, often in bursts while it scans: refresh once it settles.
    private var pendingRefresh: Job? = null
    private fun scheduleRefresh() {
        lifecycleScope.launch {
            pendingRefresh?.cancel()
            pendingRefresh = launch {
                delay(600L)
                refresh()
            }
        }
    }

    override fun onHistoryModified() = scheduleRefresh()
    override fun onMedialibraryReady() = scheduleRefresh()
    override fun onMedialibraryIdle() = scheduleRefresh()
    override fun onMediaAdded() = scheduleRefresh()
    override fun onMediaModified() = scheduleRefresh()
    override fun onMediaDeleted(id: LongArray?) = scheduleRefresh()
    override fun onMediaConvertedToExternal(id: LongArray?) = scheduleRefresh()

    private class HomeData(val continueWatching: List<Card>, val listenAgain: List<Card>, val folders: List<Card>,
                           val favorites: List<Card>, val newVideos: List<Card>, val newMusic: List<Card>)

    fun refresh() {
        val context = context ?: return
        val historyOn = Settings.getInstance(context).getBoolean(PLAYBACK_HISTORY, true)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                val ml = Medialibrary.getInstance()
                if (!ml.isInitiated) return@withContext null
                val history = if (historyOn) ml.history(Medialibrary.HISTORY_TYPE_LOCAL).toList() else emptyList()
                HomeData(
                        history.filter { it.type == MediaWrapper.TYPE_VIDEO }.take(MAX_CARDS).map { Card(it) },
                        history.filter { it.type == MediaWrapper.TYPE_AUDIO }.take(MAX_CARDS).map { Card(it) },
                        frequentFolders(history),
                        (ml.getVideos(Medialibrary.SORT_DEFAULT, false, false, true) + ml.getAudio(Medialibrary.SORT_DEFAULT, false, false, true))
                                .take(MAX_CARDS).map { Card(it) },
                        // newest first (the library's getRecentAudio() can come back empty: sort by insertion date)
                        ml.getPagedVideos(Medialibrary.SORT_INSERTIONDATE, true, false, false, MAX_CARDS, 0).map { Card(it) },
                        ml.getPagedAudio(Medialibrary.SORT_INSERTIONDATE, true, false, false, MAX_CARDS, 0).map { Card(it) })
            } ?: return@launch
            if (!isAdded) return@launch
            pinned.update(pinnedCards(context))
            continueWatching.update(data.continueWatching)
            listenAgain.update(data.listenAgain)
            folders.update(data.folders)
            favorites.update(data.favorites)
            newVideos.update(data.newVideos)
            newMusic.update(data.newMusic)
            emptyView.visibility = if (sections.all { it.cards.isEmpty() }) View.VISIBLE else View.GONE
        }
    }

    /** The folders of the media played, the most played first (local files only). */
    private fun frequentFolders(history: List<MediaWrapper>): List<Card> {
        val counts = LinkedHashMap<String, Pair<Uri, Long>>()
        for (media in history) {
            if (media.uri?.scheme != "file") continue
            val parent = media.uri.retrieveParent() ?: continue
            val key = parent.toString()
            val previous = counts[key]?.second ?: 0L
            counts[key] = parent to previous + maxOf(1L, media.seen)
        }
        return counts.values.sortedByDescending { it.second }.take(MAX_CARDS).map { (uri, count) ->
            val dir = MLServiceLocator.getAbstractMediaWrapper(uri).apply { type = MediaWrapper.TYPE_DIR }
            Card(dir, folder = true, playCount = count)
        }
    }

    private fun onCardClick(section: Section, position: Int) {
        val card = section.cards.getOrNull(position) ?: return
        val activity = activity ?: return
        card.pin?.let { Pins.open(activity, it); return }
        if (card.folder) {
            val intent = Intent(activity.applicationContext, SecondaryActivity::class.java)
            intent.putExtra(KEY_MEDIA, card.media)
            intent.putExtra("fragment", SecondaryActivity.FILE_BROWSER)
            startActivity(intent)
            return
        }
        when (section.kind) {
            // the songs play on, one after the other, as the desktop's Listen Again
            Kind.AUDIO -> MediaUtils.openList(activity, section.cards.map { it.media }, position)
            else -> MediaUtils.openMedia(activity, card.media)
        }
    }

    private fun onCardLongClick(section: Section, position: Int) {
        val card = section.cards.getOrNull(position) ?: return
        if (card.pin != null) {
            ctxSection = section
            showContext(requireActivity(), this, position, card.media, FlagSet(ContextOption::class.java).apply { add(ContextOption.CTX_UNPIN) })
            return
        }
        if (card.folder) return
        val media = card.media
        ctxSection = section
        val flags = FlagSet(ContextOption::class.java).apply {
            addAll(CTX_PLAY, CTX_APPEND, CTX_PLAY_NEXT, CTX_ADD_TO_PLAYLIST, CTX_INFORMATION, CTX_GO_TO_FOLDER, CTX_SHARE)
            add(if (media.isFavorite) CTX_FAV_REMOVE else CTX_FAV_ADD)
            if (media.type == MediaWrapper.TYPE_VIDEO) {
                add(CTX_PLAY_AS_AUDIO)
                add(if (media.seen > 0L) CTX_MARK_AS_UNPLAYED else CTX_MARK_AS_PLAYED)
            }
        }
        showContext(requireActivity(), this, position, media, flags)
    }

    override fun onCtxAction(position: Int, option: ContextOption) {
        val section = ctxSection ?: return
        section.cards.getOrNull(position)?.pin?.let { pin ->
            if (option == ContextOption.CTX_UNPIN) Pins.unpin(requireContext(), pin)
            return
        }
        val media = section.cards.getOrNull(position)?.media ?: return
        val activity = activity ?: return
        when (option) {
            CTX_PLAY -> onCardClick(section, position)
            CTX_PLAY_AS_AUDIO -> {
                media.addFlags(MediaWrapper.MEDIA_FORCE_AUDIO)
                MediaUtils.openMedia(activity, media)
            }
            CTX_APPEND -> MediaUtils.appendMedia(activity, media)
            CTX_PLAY_NEXT -> MediaUtils.insertNext(activity, media.tracks)
            CTX_ADD_TO_PLAYLIST -> activity.addToPlaylist(media.tracks, SavePlaylistDialog.KEY_NEW_TRACKS)
            CTX_INFORMATION -> showInfoDialog(media)
            CTX_GO_TO_FOLDER -> showParentFolder(media)
            CTX_SHARE -> lifecycleScope.launch { (activity as? AppCompatActivity)?.share(media) }
            CTX_FAV_ADD, CTX_FAV_REMOVE -> lifecycleScope.launch {
                withContext(Dispatchers.IO) { media.isFavorite = option == CTX_FAV_ADD }
                refresh()
            }
            CTX_MARK_AS_PLAYED, CTX_MARK_AS_UNPLAYED -> lifecycleScope.launch {
                withContext(Dispatchers.IO) { media.playCount = if (option == CTX_MARK_AS_PLAYED) 1L else 0L }
                refresh()
            }
            else -> {}
        }
    }

    private fun openSecondary(fragment: String) {
        val i = Intent(requireActivity(), SecondaryActivity::class.java)
        i.putExtra("fragment", fragment)
        requireActivity().startActivityForResult(i, SecondaryActivity.ACTIVITY_RESULT_SECONDARY)
    }

    private fun selectTab(id: Int) {
        (activity as? MainActivity)?.navigationView?.firstOrNull { it.visibility == View.VISIBLE }?.selectedItemId = id
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false
    override fun onDestroyActionMode(mode: ActionMode?) {}
}
