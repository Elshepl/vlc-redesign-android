package org.videolan.vlc.gui.audio

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.Tools
import org.videolan.medialibrary.interfaces.media.Album
import org.videolan.medialibrary.interfaces.media.Artist
import org.videolan.medialibrary.interfaces.media.Folder
import org.videolan.medialibrary.interfaces.media.Genre
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.Playlist
import org.videolan.medialibrary.interfaces.media.VideoGroup
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.media.MediaUtils

/**
 * VLC Redesign: the library's list + detail on a wide window (the desktop's MusicArtistsAlbums.qml and its genre
 * view, its album and playlist views): the list keeps the start side, the selected artist or genre shows here
 * with its albums as sections and their tracks, an album or a playlist as one list, Play all and Shuffle. Narrow windows keep opening the artist in its own screen.
 */
class RdLibraryDetailPane(private val root: View, private val scope: LifecycleCoroutineScope) {

    private val context: Context = root.context
    private val title = root.findViewById<TextView>(R.id.rd_detail_title)
    private val subtitle = root.findViewById<TextView>(R.id.rd_detail_subtitle)
    private val list = root.findViewById<RecyclerView>(R.id.rd_detail_list)
    private val adapter = RowAdapter()
    private var job: Job? = null
    private var tracks: List<MediaWrapper> = emptyList()
    var item: MediaLibraryItem? = null
        private set

    init {
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        root.findViewById<View>(R.id.rd_detail_play).setOnClickListener { play(0, false) }
        root.findViewById<View>(R.id.rd_detail_shuffle).setOnClickListener { play(0, true) }
    }

    fun show(selected: MediaLibraryItem) {
        if (selected.id == item?.id && selected.itemType == item?.itemType) return
        item = selected
        title.text = selected.title
        subtitle.text = ""
        adapter.set(emptyList())
        tracks = emptyList()
        job?.cancel()
        job = scope.launch {
            val tracks = withContext(Dispatchers.IO) {
                when (selected) {
                    is Artist -> selected.getTracks(org.videolan.medialibrary.interfaces.Medialibrary.SORT_ALBUM, false, false, false)
                    is Genre -> selected.getTracks(false, org.videolan.medialibrary.interfaces.Medialibrary.SORT_ALBUM, false, false, false)
                    is Folder -> selected.media(Folder.TYPE_FOLDER_VIDEO, org.videolan.medialibrary.interfaces.Medialibrary.SORT_FILENAME, false, false, false, selected.mediaCount(Folder.TYPE_FOLDER_VIDEO), 0)
                    is VideoGroup -> selected.media(org.videolan.medialibrary.interfaces.Medialibrary.SORT_DEFAULT, false, false, false, selected.mediaCount(), 0)
                    else -> selected.tracks
                } ?: emptyArray()
            }
            val rows = ArrayList<Row>()
            // an album or a playlist is one list (the desktop's album / playlist view); an artist or a genre has
            // its albums as sections
            val sections = selected is Artist || selected is Genre
            var album: String? = null
            tracks.forEachIndexed { index, media ->
                val name = media.albumName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.unknown_album)
                if (sections && name != album) {
                    album = name
                    rows.add(Row(null, name, -1))
                }
                rows.add(Row(media, if (selected is Playlist) listOfNotNull(media.title, media.artistName?.takeIf { it.isNotBlank() }).joinToString(" - ") else media.title, index))
            }
            val albums = rows.count { it.media == null }
            val total = tracks.sumOf { it.length.coerceAtLeast(0L) }
            subtitle.text = when (selected) {
                is Album -> listOfNotNull(selected.albumArtist?.takeIf { it.isNotBlank() },
                        selected.releaseYear.takeIf { it > 0 }?.toString(),
                        context.resources.getQuantityString(R.plurals.track_quantity, tracks.size, tracks.size),
                        Tools.millisToString(total).takeIf { total > 0 })
                is Folder, is VideoGroup -> listOfNotNull(context.resources.getQuantityString(R.plurals.videos_quantity, tracks.size, tracks.size),
                        Tools.millisToString(total).takeIf { total > 0 })
                is Playlist -> listOfNotNull(context.resources.getQuantityString(R.plurals.track_quantity, tracks.size, tracks.size),
                        Tools.millisToString(total).takeIf { total > 0 })
                else -> listOf(context.resources.getQuantityString(R.plurals.albums_quantity, albums, albums),
                        context.resources.getQuantityString(R.plurals.track_quantity, tracks.size, tracks.size))
            }.joinToString(" · ")
            this@RdLibraryDetailPane.tracks = tracks.toList()
            adapter.set(rows)
            list.scrollToPosition(0)
        }
    }

    private fun play(position: Int, shuffle: Boolean) {
        if (tracks.isNotEmpty()) MediaUtils.openList(context, tracks, position, shuffle)
    }

    fun clear() {
        job?.cancel()
        item = null
        tracks = emptyList()
        title.text = ""
        subtitle.text = ""
        adapter.set(emptyList())
    }

    private class Row(val media: MediaWrapper?, val text: String, val position: Int)

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun set(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position].media == null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inter = ResourcesCompat.getFont(context, R.font.inter)
            if (viewType == 0) {
                // an album: the desktop's section title (Inter 600, FG/1)
                val tv = TextView(context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPaddingRelative(24.dp, 20.dp, 24.dp, 6.dp)
                    typeface = inter
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(ContextCompat.getColor(context, R.color.rd_fg_1))
                    if (android.os.Build.VERSION.SDK_INT >= 28) typeface = android.graphics.Typeface.create(inter, 600, false)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            val row = LinearLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp)
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(24.dp, 0, 24.dp, 0)
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            }
            fun text(color: Int, weight: Float) = TextView(context).apply {
                typeface = inter
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(context, color))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(if (weight > 0) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            }
            row.addView(text(R.color.rd_fg_a1, 0f).apply { minWidth = 32.dp })
            row.addView(text(R.color.rd_fg_1, 1f))
            row.addView(text(R.color.rd_fg_a1, 0f).apply { setPaddingRelative(12.dp, 0, 0, 0); textDirection = View.TEXT_DIRECTION_LTR })
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            val v = holder.itemView
            if (r.media == null) {
                (v as TextView).text = r.text
                return
            }
            val row = v as LinearLayout
            val number = r.media.trackNumber
            // a playlist numbers its own order; an album or an artist the track numbers
            (row.getChildAt(0) as TextView).text = when {
                item is Playlist -> (r.position + 1).toString()
                number > 0 && r.media.type == MediaWrapper.TYPE_AUDIO -> number.toString()
                else -> ""
            }
            (row.getChildAt(1) as TextView).text = r.text
            (row.getChildAt(2) as TextView).text = if (r.media.length > 0) Tools.millisToString(r.media.length) else ""
            row.setOnClickListener { play(r.position, false) }
        }
    }

    companion object {
        /** The width (dp) of the list's own window from which the detail shows beside the list. */
        const val MIN_WIDTH_DP = 600

        fun inflate(parent: ViewGroup): View = LayoutInflater.from(parent.context).inflate(R.layout.rd_library_detail_pane, parent, false)
    }
}
