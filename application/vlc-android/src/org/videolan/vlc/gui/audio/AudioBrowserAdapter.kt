/*
 * *************************************************************************
 *  BaseAdapter.java
 * **************************************************************************
 *  Copyright © 2016-2017 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.audio

import android.annotation.TargetApi
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.MotionEventCompat
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.paging.PagedList
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.medialibrary.interfaces.media.Artist
import org.videolan.medialibrary.interfaces.media.Genre
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.media.MediaLibraryItem.FLAG_SELECTED
import org.videolan.resources.AppContextProvider
import org.videolan.resources.UPDATE_FAVORITE_STATE
import org.videolan.resources.UPDATE_REORDER
import org.videolan.resources.UPDATE_SELECTION
import org.videolan.resources.interfaces.FocusListener
import org.videolan.tools.MultiSelectAdapter
import org.videolan.tools.MultiSelectHelper
import org.videolan.tools.Settings
import org.videolan.vlc.BR
import org.videolan.vlc.R
import org.videolan.tools.dp
import org.videolan.vlc.databinding.AudioBrowserCardItemBinding
import org.videolan.vlc.databinding.AudioBrowserItemBinding
import org.videolan.vlc.gui.helpers.MARQUEE_ACTION
import org.videolan.vlc.gui.helpers.MarqueeViewHolder
import org.videolan.vlc.gui.helpers.SelectorViewHolder
import org.videolan.vlc.gui.helpers.enableMarqueeEffect
import org.videolan.vlc.gui.helpers.getAudioIconDrawable
import org.videolan.vlc.gui.view.FastScroller
import org.videolan.vlc.gui.view.MiniVisualizer
import org.videolan.vlc.interfaces.IEventsHandler
import org.videolan.vlc.interfaces.IListEventsHandler
import org.videolan.vlc.interfaces.SwipeDragHelperAdapter
import org.videolan.vlc.util.LifecycleAwareScheduler
import org.videolan.vlc.util.isOTG
import org.videolan.vlc.util.isSD
import org.videolan.vlc.util.isSchemeSMB
import org.videolan.vlc.viewmodels.PlaylistModel

private const val SHOW_IN_LIST = -1

open class AudioBrowserAdapter @JvmOverloads constructor(
        type: Int,
        protected val eventsHandler: IEventsHandler<MediaLibraryItem>,
        protected val listEventsHandler: IListEventsHandler? = null,
        protected val reorderable: Boolean = false,
        internal var cardSize: Int = SHOW_IN_LIST
) : PagedListAdapter<MediaLibraryItem,
        AudioBrowserAdapter.AbstractMediaItemViewHolder<ViewDataBinding>>(DIFF_CALLBACK),
        FastScroller.SeparatedAdapter, MultiSelectAdapter<MediaLibraryItem>, SwipeDragHelperAdapter
{
    protected var listImageWidth: Int
    /** VLC Redesign: the items' type (a playlist's card is the design's wide card in the TV layout) */
    val rdType = type
    val multiSelectHelper: MultiSelectHelper<MediaLibraryItem> = MultiSelectHelper(this, UPDATE_SELECTION)
    protected val defaultCover: BitmapDrawable?
    private val defaultCoverCard: BitmapDrawable?
    private var focusNext = -1
    private var focusListener: FocusListener? = null
    lateinit var inflater: LayoutInflater
    private var scheduler: LifecycleAwareScheduler? = null
    var stopReorder = false
    var areSectionsEnabled = true
    private var currentPlayingVisu: MiniVisualizer? = null
    private var model: PlaylistModel? = null

    var currentMedia:MediaWrapper? = null
        set(media) {
            if (media == currentMedia) return
            val former = currentMedia
            field = media
            if (former != null) currentList?.indexOf(former)?.let {
                notifyItemChanged(it)
            }
            if (media != null) {
                currentList?.indexOf(media)?.let {
                    notifyItemChanged(it)
                }
            }
            playbackStateChanged(former, currentMedia)
        }
    protected fun inflaterInitialized() = ::inflater.isInitialized

    open fun playbackStateChanged(former: MediaWrapper?, currentMedia: MediaWrapper?) {}

    val isEmpty: Boolean
        get() = currentList.isNullOrEmpty()

    init {
        val ctx = when (eventsHandler) {
            is Context -> eventsHandler
            is Fragment -> eventsHandler.requireContext()
            else -> AppContextProvider.appContext
        }
        listImageWidth = ctx.resources.getDimension(R.dimen.audio_browser_item_size).toInt()
        defaultCover = getAudioIconDrawable(ctx, type, false)
        defaultCoverCard = if (org.videolan.vlc.gui.tablet.RdTvWindow.isOn(ctx)) rdTvCover(ctx, type) else getAudioIconDrawable(ctx, type, true)
    }

    /** The TV layout's card without a picture: the design's icon (48, #8F8F8F) on #1E1E1E (shell.css .sh-card-thumb.ph) */
    private fun rdTvCover(ctx: Context, type: Int): BitmapDrawable? {
        val icon = when (type) {
            MediaLibraryItem.TYPE_PLAYLIST -> R.drawable.rd_tvd_playlist
            else -> R.drawable.rd_tvd_songs
        }
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, icon)?.mutate() ?: return null
        val size = 48.dp
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        d.setTint(0xFF8F8F8F.toInt())
        d.setBounds(0, 0, size, size)
        d.draw(android.graphics.Canvas(bmp))
        return BitmapDrawable(ctx.resources, bmp)
    }

    @Suppress("UNCHECKED_CAST")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AbstractMediaItemViewHolder<ViewDataBinding> {
        if (!::inflater.isInitialized) {
            inflater = LayoutInflater.from(parent.context)
        }
        return if (displayInCard()) {
            val binding = AudioBrowserCardItemBinding.inflate(inflater, parent, false)
            MediaItemCardViewHolder(binding) as AbstractMediaItemViewHolder<ViewDataBinding>
        } else {
            val binding = AudioBrowserItemBinding.inflate(inflater, parent, false)
            MediaItemViewHolder(binding) as AbstractMediaItemViewHolder<ViewDataBinding>
        }
    }

    private fun displayInCard() = cardSize != SHOW_IN_LIST

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (Settings.listTitleEllipsize == 4) scheduler = enableMarqueeEffect(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        scheduler?.cancelAction("")
        currentMedia = null
        currentPlayingVisu = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun setCurrentlyPlaying(playing: Boolean) {
        if (playing) currentPlayingVisu?.start() else currentPlayingVisu?.stop()
    }

    fun setModel(model: PlaylistModel) {
        this.model = model
    }

    /**
     * VLC Redesign: the entry shown in the list + detail pane (a wide window), marked in the list as the desktop
     * marks the chosen row: the hover grey under it and the accent bar on its start side.
     */
    var rdDetailItem: MediaLibraryItem? = null
        set(value) {
            val old = field
            field = value
            // the same entry again changes nothing: notifying would wake the list's change observers, and the
            // Playlists page answers them by setting the entry again (an endless loop that froze the tablet)
            if (old === value || (old != null && value != null && old.id == value.id && old.itemType == value.itemType)) return
            for (i in 0 until itemCount) {
                val it = getItem(i) ?: continue
                if ((old != null && it.id == old.id && it.itemType == old.itemType) || (value != null && it.id == value.id && it.itemType == value.itemType)) notifyItemChanged(i, UPDATE_DETAIL)
            }
        }

    private fun rdMarkDetail(view: View, item: MediaLibraryItem?) {
        val marked = item != null && rdDetailItem?.let { it.id == item.id && it.itemType == item.itemType } == true
        // the row's own foreground (its ripple) is kept: the mark is layered over it, and taken off again
        @Suppress("UNCHECKED_CAST")
        val kept = view.getTag(R.id.rd_detail_marked) as? Array<android.graphics.drawable.Drawable?>
        if (marked && kept == null) {
            val original = view.foreground
            val mark = androidx.core.content.ContextCompat.getDrawable(view.context, R.drawable.rd_list_detail_marked)
            view.setTag(R.id.rd_detail_marked, arrayOf(original))
            view.foreground = if (original != null) android.graphics.drawable.LayerDrawable(arrayOf(mark, original)) else mark
        } else if (!marked && kept != null) {
            view.foreground = kept[0]
            view.setTag(R.id.rd_detail_marked, null)
        }
        view.isActivated = marked
    }


    override fun onBindViewHolder(holder: AbstractMediaItemViewHolder<ViewDataBinding>, position: Int) {
        if (position >= itemCount) return
        val item = getItem(position)
        holder.setItem(item)
        if (item is Artist) item.description = holder.binding.root.context.resources.getQuantityString(R.plurals.albums_quantity, item.albumsCount, item.albumsCount)
        if (item is Genre) item.description = holder.binding.root.context.resources.getQuantityString(R.plurals.track_quantity, item.tracksCount, item.tracksCount)
        val isSelected = multiSelectHelper.isSelected(position)
        holder.selectView(isSelected)
        if (item is MediaWrapper) {
            holder.binding.setVariable(BR.isNetwork, item.uri.scheme.isSchemeSMB())
            holder.binding.setVariable(BR.isOTG, item.uri.isOTG())
            holder.binding.setVariable(BR.isSD, item.uri.isSD())
            holder.binding.setVariable(BR.isPresent, item.isPresent)
        } else holder.binding.setVariable(BR.isPresent, true)
        val miniVisualizer: MiniVisualizer = holder.getMiniVisu()
        if (currentMedia == item) {
            if (model?.playing != false) miniVisualizer.start() else miniVisualizer.stop()
            miniVisualizer.visibility = View.VISIBLE
            holder.changePlayingVisibility(true)
            currentPlayingVisu = miniVisualizer
        } else {
            miniVisualizer.stop()
            holder.changePlayingVisibility(false)
            miniVisualizer.visibility = View.INVISIBLE
        }
        item?.let { holder.binding.setVariable(BR.isFavorite, it.isFavorite) }
        holder.binding.setVariable(BR.inSelection,multiSelectHelper.inActionMode)
        rdMarkDetail(holder.itemView, item)
        holder.binding.invalidateAll()
        holder.binding.executePendingBindings()
        rdTvRow(holder.binding)
        if (position == focusNext) {
            holder.binding.root.requestFocus()
            focusNext = -1
        }
    }

    override fun onBindViewHolder(holder: AbstractMediaItemViewHolder<ViewDataBinding>, position: Int, payloads: List<Any>) {
        if (payloads.isNullOrEmpty())
            onBindViewHolder(holder, position)
        else {
            val payload = payloads[0]
            if (payload is MediaLibraryItem) {
                val isSelected = payload.hasStateFlags(FLAG_SELECTED)
                holder.selectView(isSelected)
            } else if (payload is Int) {
                when (payload) {
                    UPDATE_SELECTION -> {
                        val isSelected = multiSelectHelper.isSelected(position)
                        holder.selectView(isSelected)
                        rdTvRow(holder.binding)
                    }
                    UPDATE_REORDER -> {
                       holder.binding.invalidateAll()
                    }
                    UPDATE_FAVORITE_STATE -> getItem(position)?.let { holder.binding.setVariable(BR.isFavorite, it.isFavorite) }
                    UPDATE_DETAIL -> rdMarkDetail(holder.itemView, getItem(position))
                }
            }
        }
    }

    /** The TV layout's row (layout-w1600dp/audio_browser_item): its second line, menu and move buttons stay hidden */
    private fun rdTvRow(binding: ViewDataBinding) {
        if (!org.videolan.vlc.gui.tablet.RdTvWindow.isOn(binding.root.context)) return
        if (binding is org.videolan.vlc.databinding.AudioAlbumTrackItemBinding) {
            // an album's track: the design's row too (32 high, the name 14 in #8F8F8F after its number)
            binding.subtitle.visibility = View.GONE
            binding.itemMore.visibility = View.GONE
            binding.root.minimumHeight = 32.dp
            binding.root.background = null
            binding.title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 14f)
            binding.title.setTextColor(0xFF8F8F8F.toInt())
            binding.title.typeface = androidx.core.content.res.ResourcesCompat.getFont(binding.root.context, R.font.inter)
            (binding.title.layoutParams as ViewGroup.MarginLayoutParams).apply { topMargin = 0; bottomMargin = 0 }
            binding.trackNumber.setTextColor(0xFF6B6B6B.toInt())
            return
        }
        if (binding !is AudioBrowserItemBinding) return
        binding.subtitle.visibility = View.GONE
        binding.itemMore.visibility = View.GONE
        binding.itemMove.visibility = View.GONE
    }

    override fun onViewRecycled(h: AbstractMediaItemViewHolder<ViewDataBinding>) {
        scheduler?.cancelAction(MARQUEE_ACTION)
        h.recycle()
        super.onViewRecycled(h)
    }

    private fun isPositionValid(position: Int): Boolean {
        return position in 0 until itemCount
    }

    override fun getItemId(position: Int): Long {
        if (!isPositionValid(position)) return -1
        val item = getItem(position)
        return item?.id ?: -1
    }

    override fun getItem(position: Int): MediaLibraryItem? {
        return if (position in 0 until itemCount) super.getItem(position) else null
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return item?.itemType ?: MediaLibraryItem.TYPE_MEDIA
    }

    fun clear() {
        //        getDataset().clear();
    }


    override fun onCurrentListChanged(previousList: PagedList<MediaLibraryItem>?, currentList: PagedList<MediaLibraryItem>?) {
        eventsHandler.onUpdateFinished(this@AudioBrowserAdapter)
    }

    override fun hasSections(): Boolean {
        return areSectionsEnabled
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int) {
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun onItemMoved(dragFrom: Int, dragTo: Int) {
        listEventsHandler!!.onMove(dragFrom, dragTo)
        preventNextAnim = true
    }

    override fun onItemDismiss(position: Int) {
        val item = getItem(position)
        listEventsHandler!!.onRemove(position, item!!)
    }


    fun setOnFocusChangeListener(focusListener: FocusListener?) {
        this.focusListener = focusListener
    }

    @TargetApi(Build.VERSION_CODES.M)
    inner class MediaItemViewHolder(binding: AudioBrowserItemBinding) : AbstractMediaItemViewHolder<AudioBrowserItemBinding>(binding) {
        var onTouchListener: View.OnTouchListener

        override val titleView: TextView? = binding.title

        init {
            binding.holder = this
            defaultCover?.let { binding.cover = it }
            if (AndroidUtil.isMarshMallowOrLater)
                itemView.setOnContextClickListener { v ->
                    onMoreClick(v)
                    true
                }

            onTouchListener = object : View.OnTouchListener {
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    if (listEventsHandler == null) {
                        return false
                    }
                    if (multiSelectHelper.getSelectionCount() != 0) {
                        return false
                    }
                    if (MotionEventCompat.getActionMasked(event) == MotionEvent.ACTION_DOWN) {
                        listEventsHandler.onStartDrag(this@MediaItemViewHolder)
                        return true
                    }
                    return false
                }
            }
            binding.imageWidth = listImageWidth
        }

        override fun selectView(selected: Boolean) {
            binding.setVariable(BR.selected, selected)
            binding.itemMore.visibility = if (multiSelectHelper.inActionMode) View.INVISIBLE else View.VISIBLE
        }

        override fun setItem(item: MediaLibraryItem?) {
            binding.item = item
        }

        override fun recycle() {
            binding.cover = if (cardSize == SHOW_IN_LIST && defaultCover != null) defaultCover else null
            binding.mediaCover.resetFade()
            binding.title.isSelected = false
        }

        override fun getMiniVisu() = binding.playing

        override fun changePlayingVisibility(isCurrent: Boolean) {
            binding.mediaCover.visibility = if (isCurrent) View.INVISIBLE else View.VISIBLE
        }

    }

    @TargetApi(Build.VERSION_CODES.M)
    inner class MediaItemCardViewHolder(binding: AudioBrowserCardItemBinding) : AbstractMediaItemViewHolder<AudioBrowserCardItemBinding>(binding) {

        override val titleView = binding.title

        init {
            binding.holder = this
            binding.scaleType = ImageView.ScaleType.CENTER_INSIDE
            defaultCoverCard?.let { binding.cover = it }
            if (AndroidUtil.isMarshMallowOrLater)
                itemView.setOnContextClickListener { v ->
                    onMoreClick(v)
                    true
                }
            binding.imageWidth = cardSize
            binding.container.layoutParams.width = cardSize
            if (org.videolan.vlc.gui.tablet.RdTvWindow.isOn(binding.root.context)) rdTvCard()
        }

        /**
         * The TV layout at the design's size (shell.css .sh-card): the picture radius 8 (316 x 178 for a playlist,
         * 200 x 200 for the others), no frame, play button nor menu button (the remote's long OK / Menu key), the
         * name 20 under it (Inter 16, #969696) and its line 10 under that (#6B6B6B).
         */
        private fun rdTvCard() {
            val c = binding.container
            c.background = null
            c.foreground = null
            c.minHeight = 0
            binding.mediaCoverContainer.apply {
                radius = 8.dp.toFloat()
                useCompatPadding = false
                cardElevation = 0f
                setCardBackgroundColor(0xFF1E1E1E.toInt())
                binding.mediaCover.setBackgroundColor(0xFF1E1E1E.toInt())
                (layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams).dimensionRatio =
                        if (rdType == MediaLibraryItem.TYPE_PLAYLIST) "316:178" else "1"
            }
            binding.mainActionButton.visibility = View.GONE
            binding.mainActionButton.alpha = 0f
            binding.itemMore.alpha = 0f
            binding.itemMore.isFocusable = false
            val inter = androidx.core.content.res.ResourcesCompat.getFont(c.context, R.font.inter)
            for ((t, top, color) in listOf(Triple(binding.title, 20, 0xFF969696.toInt()), Triple(binding.subtitle, 10, 0xFF6B6B6B.toInt()))) {
                t.typeface = inter
                t.includeFontPadding = false
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
                t.setTextColor(color)
                (t.layoutParams as ViewGroup.MarginLayoutParams).apply { topMargin = top.dp; marginStart = 0; marginEnd = 0; bottomMargin = 0 }
            }
            (binding.subtitle.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = 0
        }

        override fun selectView(selected: Boolean) {
            super.selectView(selected)
            binding.setVariable(BR.selected, selected)
            binding.itemMore.visibility = if (multiSelectHelper.inActionMode) View.INVISIBLE else View.VISIBLE
            binding.mainActionButton.visibility = if (multiSelectHelper.inActionMode || org.videolan.vlc.gui.tablet.RdTvWindow.isOn(binding.root.context)) View.GONE else View.VISIBLE
        }

        override fun setItem(item: MediaLibraryItem?) {
            binding.item = item
        }

        override fun recycle() {
            defaultCoverCard?.let { binding.cover = it }
            binding.mediaCover.resetFade()
            binding.title.isSelected = false
        }

        override fun getMiniVisu() = binding.playing

        override fun changePlayingVisibility(isCurrent: Boolean) { }

    }

    @TargetApi(Build.VERSION_CODES.M)
    abstract inner class AbstractMediaItemViewHolder<T : ViewDataBinding>(binding: T) : SelectorViewHolder<T>(binding), MarqueeViewHolder {

        val canBeReordered: Boolean
            get() = reorderable && !stopReorder

        fun onClick(v: View) {
            getItem(layoutPosition)?.let { eventsHandler.onClick(v, layoutPosition, it) }
        }

        fun onMoreClick(v: View) {
            getItem(layoutPosition)?.let { eventsHandler.onCtxClick(v, layoutPosition, it) }
        }

        fun onLongClick(v: View): Boolean {
            return getItem(layoutPosition)?.let { eventsHandler.onLongClick(v, layoutPosition, it) } == true
        }

        fun onImageClick(v: View) {
            getItem(layoutPosition)?.let { eventsHandler.onImageClick(v, layoutPosition, it) }
        }

        fun onMainActionClick(v: View) {
            getItem(layoutPosition)?.let { eventsHandler.onMainActionClick(v, layoutPosition, it) }
        }


        override fun isSelected() = multiSelectHelper.isSelected(layoutPosition)

        abstract fun setItem(item: MediaLibraryItem?)

        abstract fun getMiniVisu():MiniVisualizer

        abstract fun recycle()
        abstract fun changePlayingVisibility(isCurrent: Boolean)

    }

    companion object {

        private const val TAG = "VLC/AudioBrowserAdapter"
        private const val UPDATE_PAYLOAD = 1
        private const val UPDATE_DETAIL = 0x2D7A
        /**
         * Awful hack to workaround the [PagedListAdapter] not keeping track of notifyItemMoved operations
         */
        private var preventNextAnim: Boolean = false

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<MediaLibraryItem>() {
            override fun areItemsTheSame(
                    oldMedia: MediaLibraryItem, newMedia: MediaLibraryItem): Boolean {
                return if (preventNextAnim) {
                    true
                } else if (oldMedia is MediaWrapper && newMedia is MediaWrapper && oldMedia.isPresent != newMedia.isPresent) {
                    false
                } else oldMedia === newMedia || oldMedia.title == newMedia.title && oldMedia.itemType == newMedia.itemType && oldMedia.tracksCount == newMedia.tracksCount && oldMedia.equals(newMedia)
            }

            override fun areContentsTheSame(
                    oldMedia: MediaLibraryItem, newMedia: MediaLibraryItem): Boolean {
                return false
            }

            override fun getChangePayload(oldItem: MediaLibraryItem, newItem: MediaLibraryItem): Any {
                preventNextAnim = false
                when {
                    oldItem.isFavorite != newItem.isFavorite  -> return UPDATE_FAVORITE_STATE
                }
                return UPDATE_PAYLOAD
            }
        }
    }
}

