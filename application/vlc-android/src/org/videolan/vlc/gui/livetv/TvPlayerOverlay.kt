/*
 * VLC Redesign, Live TV over the video, as a set-top box (TvPlayerOverlay.qml): the banner of the channel at
 * every switch (its number, its name, what is on with its progress and the time left, what is next), the
 * guide of the channel browsed in it (Left, Right, its arrows, a swipe across it), the channel number typed,
 * the channels up and down (a swipe across the video, D-pad Up / Down and the remote's channel keys), the
 * previous one. The TV app's information bar: the whole width at the bottom, right on the player's controls when
 * they show, three columns (the channel; the programme; the time, the marks and the keys); the dark theme always.
 *
 * It is laid over the video player by an activity lifecycle callback and gets the keys and the touches before
 * the player (a wrapper of its window's callback): the player itself is left as it is.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.livetv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.tools.Settings
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.R
import org.videolan.vlc.gui.video.VideoPlayerActivity
import org.videolan.vlc.livetv.Entry
import org.videolan.vlc.livetv.KEY_IPTV_BANNER
import org.videolan.vlc.livetv.KEY_IPTV_DESCRIPTIONS
import org.videolan.vlc.livetv.KEY_IPTV_NEXT
import org.videolan.vlc.livetv.KEY_IPTV_PROGRESS
import org.videolan.vlc.livetv.LiveTv
import org.videolan.vlc.livetv.TvChannel
import org.videolan.vlc.livetv.TvPlayback
import java.util.WeakHashMap
import kotlin.math.abs

/** Lays the overlay over every video player while the add-on is there. */
object TvPlayerHook : Application.ActivityLifecycleCallbacks {
    private val overlays = WeakHashMap<Activity, TvPlayerOverlay>()

    fun install(app: Application) {
        LiveTv.init(app)
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        if (activity !is VideoPlayerActivity || overlays.containsKey(activity)) return
        if (!LiveTv.enabled) return
        overlays[activity] = TvPlayerOverlay(activity).also { it.attach() }
    }
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        overlays.remove(activity)?.detach()
    }
}

/** The colours over the video (the dark theme's, whatever the theme). */
private object Dark {
    const val FG1 = 0xFFEAEAEA.toInt()
    const val FG2 = 0xFFC6C6C6.toInt()
    const val A1 = 0xFF969696.toInt()
    const val A2 = 0xFF8F8F8F.toInt()
    const val A3 = 0xFF6B6B6B.toInt()
    const val TRACK = 0xFF373737.toInt()
    const val CARD = 0xEB191919.toInt()
    // the information bar (the TV app's livetv.css)
    const val WHITE = 0xFFFFFFFF.toInt()
    const val A0 = 0xFFA6A6A6.toInt()
    const val BAR = 0xFF1B1B1B.toInt()
    const val LINE = 0xFF333333.toInt()
    const val DIVIDER = 0xFF262626.toInt()
    const val KEY_EDGE = 0xFF404040.toInt()
    const val ON_ACCENT = 0xFF0E0E0E.toInt()
    const val SHADE = 0x8C000000.toInt()
}

@SuppressLint("ViewConstructor")
class TvPlayerOverlay(private val activity: VideoPlayerActivity) {

    private val ctx = activity
    private val settings = Settings.getInstance(activity)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var banner: LinearLayout
    private lateinit var numberBox: TextView
    private var original: Window.Callback? = null
    private var jobs = ArrayList<Job>()

    // the banner's views, the TV app's information bar: the channel, the programme, the side
    private val semibold = ResourcesCompat.getFont(ctx, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
    private val bBody = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; background = ColorDrawable(Dark.BAR) }
    private val bHead = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
    private val bLogo = TvLogoView(ctx, 64f, dark = true)
    private val bNumber = ctx.tvText("", 20f, Dark.FG1).apply { typeface = semibold }
    private val bName = ctx.tvText("", 16f, Dark.FG1, lines = 2).apply { typeface = semibold }
    private val bFav = ImageView(ctx).apply { setImageResource(R.drawable.rd_tv_favorite_on) }
    private val bGroup = ctx.tvText("", 13f, Dark.A2)
    private val bWhere = pill(12f).apply { setPadding(ctx.tvPx(8f), 0, ctx.tvPx(8f), 0) }
    private val bTitle = ctx.tvText("", 20f, Dark.WHITE).apply { typeface = semibold }
    private val bProgressRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bTime = ctx.tvText("", 15f, Dark.FG1).apply { typeface = semibold }
    // as on the TV, it fills from the right in a right-to-left language (the seek bar stays left to right)
    private val bProgress = TvProgress(ctx, Dark.LINE, ctx.tvAccent, ctx.tvPx(360f), mirror = true)
    private val bLeft = ctx.tvText("", 14f, Dark.A0)
    private val bEpisode = ctx.tvText("", 14f, Dark.A0)
    private val bDesc = ctx.tvText("", 14f, Dark.A2, lines = 2).apply { setLineSpacing(maxOf(0f, ctx.tvDp(20f) - paint.getFontMetricsInt(null)), 1f) }
    private val bNextRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bNextKey = pill(11f).apply {
        setTextColor(Dark.FG2)
        background = rounded(0, ctx.tvDp(4f), ctx.tvPx(1f), Dark.KEY_EDGE)
        setPadding(ctx.tvPx(7f), 0, ctx.tvPx(7f), 0)
        text = ctx.getString(R.string.rd_tv_next_mark)
    }
    private val bNextText = ctx.tvText("", 14f, Dark.A0)
    private val bEnd = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
    private val bDivider = View(ctx).apply { setBackgroundColor(Dark.DIVIDER) }
    private val bInfo = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    /** the banner in one row (null: not laid out yet), or stacked on a phone held upright */
    private var stacked: Boolean? = null
    private val bClock = ctx.tvText("", 26f, Dark.FG1).apply { typeface = semibold; fontFeatureSettings = "tnum" }
    private val bMarks = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bPrev = ctx.tvIconButton(R.drawable.rd_ic_arrow_left, ctx.getString(R.string.rd_tv_programme_before), Dark.A2) { browse(-1) }
    private val bNext = ctx.tvIconButton(R.drawable.rd_ic_arrow_right, ctx.getString(R.string.rd_tv_programme_after), Dark.A2) { browse(1) }
    private val bAction = ctx.tvButton("", accent = true) { action() }
    private val bKeyBrowse = ctx.tvText("", 12f, Dark.A3)
    private val bKeyZap = ctx.tvText("▲ ▼  " + ctx.getString(R.string.rd_tv_channels), 12f, Dark.A3)
    // the remote's keys on a TV; the arrows to touch on a phone or a tablet
    private val keys = Settings.device.isTv

    /** a mark of the bar (Live, Earlier, Next): small capitals in a rounded well */
    private fun pill(sp: Float) = ctx.tvText("", sp, Dark.FG1).apply {
        typeface = semibold
        isAllCaps = true
        letterSpacing = 0.04f
        gravity = Gravity.CENTER
    }

    /** Browsing the guide of the channel in the banner: 0 is what is on, -1 the programme before, +1 the next... */
    private var browse = 0
    private var browseList: List<Entry> = emptyList()
    private var browseIndexNow = -1
    private var typed = ""
    private var bannerShown = false

    private val hideBanner = Runnable { showBanner(false) }
    private val backToNow = Runnable { if (browse != 0) { browse = 0; bind() } }
    private val commitNumber = Runnable { commit() }

    fun attach() {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        root = FrameLayout(ctx).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // over the player's own panels and bars
            elevation = ctx.tvDp(30f)
        }
        buildBanner()
        numberBox = ctx.tvText("", 40f, Dark.FG1, medium = true).apply {
            background = rounded(Dark.CARD, ctx.tvDp(10f))
            setPadding(ctx.tvPx(20f), ctx.tvPx(10f), ctx.tvPx(20f), ctx.tvPx(10f))
            visibility = View.GONE
        }
        root.addView(numberBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(ctx.tvPx(32f), ctx.tvPx(48f), ctx.tvPx(32f), 0)
        })
        content.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // the keys and the touches before the player
        val w = activity.window
        original = w.callback
        w.callback = Callback(original!!)
        jobs.add(activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TvPlayback.switches.collect { if (it > 0) onSwitch() } }
                launch { LiveTv.messages.collect { m -> if (m != null && SystemClock.elapsedRealtime() - m.first < 3000 && active()) message(m.second) } }
                // the progress live, what is on when it changes
                launch { while (true) { delay(1000); if (bannerShown) { if (activity.isShowingDialog || panelFocused()) showBanner(false) else { place(); bindTimes() } } } }
            }
        })
        PlaybackService.instance?.addCallback(serviceCallback)
        if (active()) onSwitch()
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        jobs.forEach { it.cancel() }
        jobs.clear()
        PlaybackService.instance?.removeCallback(serviceCallback)
        original?.let { if (activity.window.callback is Callback) activity.window.callback = it }
        original = null
        if (::root.isInitialized) (root.parent as? ViewGroup)?.removeView(root)
    }

    /** A channel plays in this player now (not another media of its queue). */
    private fun active(): Boolean {
        val uri = PlaybackService.instance?.currentMediaWrapper?.uri?.toString()
        val on = LiveTv.enabled && TvPlayback.isCurrent(uri)
        return on
    }

    private fun channel(): TvChannel? = TvPlayback.channel()

    /* Subtitles are not turned on by themselves for TV: a track of the stream stays a choice of the user */
    private var switchTime = 0L
    private val serviceCallback = object : PlaybackService.Callback {
        override fun update() {}
        override fun onMediaEvent(event: IMedia.Event) {}
        override fun onMediaPlayerEvent(event: MediaPlayer.Event) {
            if (event.type == MediaPlayer.Event.ESSelected && event.esChangedType == IMedia.Track.Type.Text &&
                SystemClock.elapsedRealtime() - switchTime < 15_000 && active()) {
                val service = PlaybackService.instance ?: return
                if (service.spuTrack != "-1") handler.post { service.setSpuTrack("-1") }
            }
        }
    }

    private fun onSwitch() {
        switchTime = SystemClock.elapsedRealtime()
        PlaybackService.instance?.let { s -> s.removeCallback(serviceCallback); s.addCallback(serviceCallback) }
        browse = 0
        if (settings.getString(KEY_IPTV_BANNER, "0") == "3") return
        handler.postDelayed({ if (active()) { bind(); showBanner(true) } }, 150)
    }

    /* ---- the banner ---- */

    private fun buildBanner() {
        // like a cable box's bar: the whole width, flush with the bottom or right on the player's bar (no gap), a
        // thin line at its top and a soft shade above it over the picture; the columns mirror in right to left
        banner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            alpha = 0f
        }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        banner.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, Dark.SHADE))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(match, ctx.tvPx(90f)))
        banner.addView(View(ctx).apply { setBackgroundColor(Dark.LINE) }, LinearLayout.LayoutParams(match, maxOf(1, ctx.tvPx(1f))))
        bBody.isClickable = true
        bBody.minimumHeight = ctx.tvPx(106f)
        banner.addView(bBody, LinearLayout.LayoutParams(match, wrap))
        // the channel: its logo big, its number and name (a star for a favorite), its group
        bHead.setPadding(ctx.tvPx(18f), ctx.tvPx(13f), ctx.tvPx(14f), ctx.tvPx(13f))
        bHead.addView(bLogo, LinearLayout.LayoutParams(ctx.tvPx(64f), ctx.tvPx(64f)))
        val who = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ctx.tvPx(6f), 0, 0) }
        val nameLine = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameLine.addView(bNumber, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = ctx.tvPx(8f) })
        // the name as long as it is, the star right after it (place() keeps it to the column)
        nameLine.addView(bName, LinearLayout.LayoutParams(wrap, wrap))
        nameLine.addView(bFav, LinearLayout.LayoutParams(ctx.tvPx(16f), ctx.tvPx(16f)).apply { marginStart = ctx.tvPx(6f) })
        who.addView(nameLine, LinearLayout.LayoutParams(match, wrap))
        who.addView(bGroup, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ctx.tvPx(4f) })
        bHead.addView(who, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = ctx.tvPx(12f) })
        bBody.addView(bHead, LinearLayout.LayoutParams(ctx.tvPx(260f), match))
        bBody.addView(bDivider, LinearLayout.LayoutParams(maxOf(1, ctx.tvPx(1f)), match))
        // the programme: its state, title, hours, how far it is, its story, what comes next
        val info = bInfo.apply { setPadding(ctx.tvPx(20f), ctx.tvPx(13f), ctx.tvPx(20f), ctx.tvPx(13f)) }
        val titleRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ctx.tvPx(26f) }
        bWhere.background = rounded(Dark.TRACK, ctx.tvDp(5f))
        titleRow.addView(bWhere, LinearLayout.LayoutParams(wrap, ctx.tvPx(22f)).apply { marginEnd = ctx.tvPx(12f) })
        titleRow.addView(bTitle, LinearLayout.LayoutParams(0, wrap, 1f))
        info.addView(titleRow, LinearLayout.LayoutParams(match, wrap))
        bProgressRow.addView(bTime, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = ctx.tvPx(14f) })
        // the gaps around the bar belong to its neighbours
        bProgressRow.addView(bProgress, LinearLayout.LayoutParams(0, ctx.tvPx(5f), 1f))
        bProgressRow.addView(bLeft, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = ctx.tvPx(14f) })
        info.addView(bProgressRow, LinearLayout.LayoutParams(match, ctx.tvPx(20f)).apply { topMargin = ctx.tvPx(7f) })
        info.addView(bEpisode, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ctx.tvPx(10f) })
        info.addView(bDesc, LinearLayout.LayoutParams(match, wrap).apply { topMargin = ctx.tvPx(6f) })
        bNextRow.addView(bNextKey, LinearLayout.LayoutParams(wrap, ctx.tvPx(20f)).apply { marginEnd = ctx.tvPx(10f) })
        bNextRow.addView(bNextText, LinearLayout.LayoutParams(0, wrap, 1f))
        info.addView(bNextRow, LinearLayout.LayoutParams(match, ctx.tvPx(20f)).apply { topMargin = ctx.tvPx(10f) })
        bBody.addView(info, LinearLayout.LayoutParams(0, wrap, 1f))
        // the side: the time now, the archive, what OK does here, the remote's keys (or the arrows to touch)
        bEnd.setPadding(0, ctx.tvPx(12f), ctx.tvPx(18f), ctx.tvPx(12f))
        bEnd.minimumWidth = ctx.tvPx(150f)
        bEnd.addView(bClock, LinearLayout.LayoutParams(wrap, wrap))
        bEnd.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        bAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        bAction.minHeight = ctx.tvPx(28f)
        for (b in arrayOf(bPrev, bNext)) bMarks.addView(b, LinearLayout.LayoutParams(ctx.tvPx(36f), ctx.tvPx(36f)).apply { marginStart = ctx.tvPx(4f) })
        bMarks.addView(bAction, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.tvPx(32f)).apply { marginStart = ctx.tvPx(8f) })
        bEnd.addView(bMarks, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = ctx.tvPx(8f); bottomMargin = ctx.tvPx(8f) })
        bEnd.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
        for (k in arrayOf(bKeyBrowse, bKeyZap)) {
            k.visibility = if (keys) View.VISIBLE else View.GONE
            bEnd.addView(k, LinearLayout.LayoutParams(wrap, wrap))
        }
        bBody.addView(bEnd, LinearLayout.LayoutParams(wrap, match))
        // a swipe across the banner: the programmes before and after (the time from left to right)
        val swipe = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || abs(e2.x - e1.x) < ctx.tvDp(40f) || abs(vx) < abs(vy)) return false
                browse(if (e2.x < e1.x) 1 else -1)
                return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean { schedule(); return true }
        })
        bBody.setOnTouchListener { _, ev -> swipe.onTouchEvent(ev); true }
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /** the whole width, flush with the bottom of the screen, or right on the player's bar when it shows */
    private fun place() {
        val w = root.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        // the top of the player's bar (its rows at the bottom of its overlay) when it shows
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val rootBottom = loc[1] + root.height
        var top = rootBottom
        val hud = activity.findViewById<ViewGroup>(R.id.progress_overlay)
        if (activity.isShowing && hud != null && hud.isShown) for (i in 0 until hud.childCount) {
            val child = hud.getChildAt(i)
            if (child.visibility != View.VISIBLE || child.height <= 0 || child.height > root.height / 2) continue
            child.getLocationOnScreen(loc)
            top = minOf(top, loc[1])
        }
        val lp = banner.layoutParams as FrameLayout.LayoutParams
        val bottom = if (top < rootBottom) rootBottom - top else navigationInset()
        if (lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom
            banner.layoutParams = lp
        }
        // a phone held upright: the bar stacked (the channel, then the programme across the whole width, then the
        // time and the arrows in a row), its logo smaller; in one row a long name widens the channel's column (up
        // to a third of the screen), then takes two lines
        val narrow = w < ctx.tvPx(600f)
        stack(narrow)
        val logo = ctx.tvPx(if (narrow) 44f else 64f)
        val number = if (bNumber.text.isEmpty()) 0 else bNumber.paint.measureText(bNumber.text.toString()).toInt() + ctx.tvPx(8f)
        val star = if (bFav.visibility == View.VISIBLE) ctx.tvPx(22f) else 0
        val around = bHead.paddingLeft + bHead.paddingRight + logo + ctx.tvPx(12f) + number + star
        val name = bName.paint.measureText(bName.text.toString()).toInt() + ctx.tvPx(2f)
        val headW = if (narrow) ViewGroup.LayoutParams.MATCH_PARENT else (around + name).coerceIn(ctx.tvPx(260f), maxOf(ctx.tvPx(260f), w / 3))
        val cut = androidx.core.view.ViewCompat.getRootWindowInsets(root)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        val pad = ctx.tvPx(if (narrow) 4f else 24f)
        val left = pad + (cut?.left ?: 0)
        val right = pad + (cut?.right ?: 0)
        if (bBody.paddingLeft != left || bBody.paddingRight != right) bBody.setPadding(left, 0, right, ctx.tvPx(6f))
        (bHead.layoutParams as LinearLayout.LayoutParams).let { if (it.width != headW) { it.width = headW; bHead.layoutParams = it } }
        (bLogo.layoutParams as LinearLayout.LayoutParams).let { if (it.width != logo) { it.width = logo; it.height = logo; bLogo.layoutParams = it } }
        // the name as long as the column lets it, the star right after it
        bName.maxWidth = maxOf(ctx.tvPx(40f), (if (narrow) w - left - right else headW) - around)
    }

    /** One row (the channel | the programme | the time and the arrows), or stacked on a narrow screen, where the
     *  programme had a few letters between the channel's column and the side (a phone held upright). */
    private fun stack(on: Boolean) {
        if (stacked == on) return
        stacked = on
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val line = maxOf(1, ctx.tvPx(1f))
        bBody.orientation = if (on) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        bHead.layoutParams = LinearLayout.LayoutParams(if (on) match else ctx.tvPx(260f), if (on) wrap else match)
        bHead.setPadding(ctx.tvPx(18f), ctx.tvPx(if (on) 10f else 13f), ctx.tvPx(14f), ctx.tvPx(if (on) 4f else 13f))
        bDivider.layoutParams = if (on) LinearLayout.LayoutParams(match, line).apply { marginStart = ctx.tvPx(18f); marginEnd = ctx.tvPx(18f) }
                                else LinearLayout.LayoutParams(line, match)
        bInfo.layoutParams = if (on) LinearLayout.LayoutParams(match, wrap) else LinearLayout.LayoutParams(0, wrap, 1f)
        bInfo.setPadding(ctx.tvPx(if (on) 18f else 20f), ctx.tvPx(if (on) 10f else 13f), ctx.tvPx(if (on) 18f else 20f), ctx.tvPx(if (on) 4f else 13f))
        // the side becomes a row: the time at the start, the arrows (and OK's action) at the end
        bEnd.orientation = if (on) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        bEnd.gravity = if (on) Gravity.CENTER_VERTICAL else Gravity.END
        bEnd.minimumWidth = if (on) 0 else ctx.tvPx(150f)
        bEnd.setPadding(ctx.tvPx(if (on) 18f else 0f), ctx.tvPx(if (on) 0f else 12f), ctx.tvPx(18f), ctx.tvPx(if (on) 4f else 12f))
        bEnd.layoutParams = LinearLayout.LayoutParams(if (on) match else wrap, if (on) wrap else match)
        bClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (on) 20f else 26f)
        // (its children: the time, a gap, the arrows, a gap, the remote's keys: in a row only the first gap grows)
        bEnd.getChildAt(3)?.let { gap -> (gap.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = if (on) 0f else 1f; gap.layoutParams = it } }
        (bMarks.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = ctx.tvPx(if (on) 0f else 8f)
            it.bottomMargin = ctx.tvPx(if (on) 0f else 8f)
            bMarks.layoutParams = it
        }
    }

    private fun navigationInset(): Int {
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(root) ?: return 0
        return if (activity.isShowing) 0 else insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
    }

    private fun showBanner(on: Boolean) {
        handler.removeCallbacks(hideBanner)
        if (on) {
            place()
            if (!bannerShown) {
                bannerShown = true
                banner.visibility = View.VISIBLE
                banner.translationY = ctx.tvDp(8f)
                banner.animate().alpha(1f).translationY(0f).setDuration(180).start()
            }
            schedule()
        } else if (bannerShown) {
            bannerShown = false
            browse = 0
            banner.animate().alpha(0f).translationY(ctx.tvDp(8f)).setDuration(180).withEndAction { if (!bannerShown) banner.visibility = View.GONE }.start()
        }
    }

    /** hides by itself; browsing goes back to what is on after a while */
    private fun schedule() {
        handler.removeCallbacks(hideBanner)
        handler.removeCallbacks(backToNow)
        handler.postDelayed(hideBanner, if (browse != 0) 10_000 else 5_000)
        if (browse != 0) handler.postDelayed(backToNow, 9_000)
    }

    private fun browse(step: Int) {
        val c = channel() ?: return
        if (!bannerShown) { bind(); showBanner(true); return }
        refreshBrowseList(c)
        if (browseList.isEmpty()) return
        val target = browse + step
        val i = browseIndexNow + target
        if (i < 0 || i >= browseList.size) return
        browse = target
        bind()
        schedule()
    }

    private fun refreshBrowseList(c: TvChannel) {
        val n = System.currentTimeMillis() / 1000
        browseList = c.between(n - 2 * 86400, n + 2 * 86400)
        browseIndexNow = browseList.indexOfFirst { it.start <= n && n < it.stop }.let { if (it >= 0) it else browseList.indexOfFirst { e -> e.start > n } }
    }

    /** the programme the banner shows: the one browsed to, or what is on */
    private fun shownEntry(c: TvChannel): Entry? {
        refreshBrowseList(c)
        if (browseIndexNow < 0) return null
        return browseList.getOrNull(browseIndexNow + browse)
    }

    private fun level() = settings.getString(KEY_IPTV_BANNER, "0")?.toIntOrNull() ?: 0

    private var badge: View? = null

    private fun bind() {
        val c = channel() ?: return
        val full = level() <= 1
        val catchup = TvPlayback.catchup
        val archive = catchup != null && browse == 0
        // the channel
        bLogo.set(c.logo, c.name)
        bNumber.text = if (c.number > 0) c.number.toString() else ""
        bNumber.visibility = if (c.number > 0) View.VISIBLE else View.GONE
        bName.text = c.name
        bFav.visibility = if (LiveTv.isFavorite(c.key)) View.VISIBLE else View.GONE
        bGroup.text = c.group
        bGroup.visibility = if (c.group.isNotEmpty()) View.VISIBLE else View.GONE
        // the programme: the archive played, one before or after (browsed to), or what is on
        val e = if (archive) null else shownEntry(c)
        val live = !archive && browse == 0 && e != null && full
        bWhere.text = when {
            archive -> ctx.getString(R.string.rd_tv_archive)
            browse != 0 -> ctx.getString(if (browse > 0) R.string.rd_tv_later else R.string.rd_tv_earlier) + if (abs(browse) > 1) " " + abs(browse) else ""
            live -> ctx.getString(R.string.rd_tv_live_mark)
            else -> ""
        }
        bWhere.visibility = if (bWhere.text.isEmpty()) View.GONE else View.VISIBLE
        bWhere.background = rounded(if (live) ctx.tvAccent else Dark.TRACK, ctx.tvDp(5f))
        bWhere.setTextColor(if (live) Dark.ON_ACCENT else Dark.FG1)
        bTitle.text = when {
            archive -> catchup!!.title.ifEmpty { ctx.getString(R.string.rd_tv_programme) }
            !full -> c.name
            e != null -> e.title
            c.hasGuide -> ctx.getString(R.string.rd_tv_loading_guide)
            else -> ctx.getString(R.string.rd_tv_no_guide_channel)
        }
        // with Settings > TV: its episode and kind, two lines of its story
        val descOn = settings.getBoolean(KEY_IPTV_DESCRIPTIONS, true) && full && e != null
        val episode = if (descOn) listOf(e!!.subtitle, e.episode, e.category).filter { it.isNotEmpty() }.joinToString("  ·  ") else ""
        bEpisode.text = episode
        bEpisode.visibility = if (episode.isNotEmpty()) View.VISIBLE else View.GONE
        val desc = if (descOn && e!!.descLength > 0) LiveTv.description(c, e).replace(Regex("\\s+"), " ") else ""
        bDesc.text = desc
        bDesc.visibility = if (desc.isNotEmpty()) View.VISIBLE else View.GONE
        // the side: the archive of the channel, the arrows or the remote's keys of the guide
        badge?.let { bMarks.removeView(it) }
        badge = null
        if (!archive && c.catchupDays > 0) badge = ctx.tvBadge(ctx.resources.getQuantityString(R.plurals.rd_tv_days_short, c.catchupDays, c.catchupDays), R.drawable.rd_ic_recent, dark = true).apply {
            contentDescription = ctx.resources.getQuantityString(R.plurals.rd_tv_catchup_tip, c.catchupDays, c.catchupDays)
            bMarks.addView(this, 0)
        }
        val guide = full && c.hasGuide && catchup == null
        bPrev.visibility = if (guide && !keys) View.VISIBLE else View.GONE
        bNext.visibility = bPrev.visibility
        bKeyBrowse.text = "◀ ▶  " + ctx.getString(R.string.rd_tv_programmes)
        bKeyBrowse.visibility = if (guide && keys) View.VISIBLE else View.GONE
        bindTimes()
        bindAction(c, e)
        place()
    }

    private fun bindTimes() {
        val c = channel() ?: return
        val n = System.currentTimeMillis() / 1000
        bClock.text = TvTexts.time(ctx, n)
        val level = level()
        val catchup = TvPlayback.catchup
        val archive = catchup != null && browse == 0
        val e = if (archive) null else shownEntry(c)
        val start = if (archive) catchup!!.start else e?.start ?: 0
        val stop = if (archive) catchup!!.stop else e?.stop ?: 0
        // its hours, how far it is (what is on), the time left or until it starts
        bProgressRow.visibility = if (level <= 1 && start > 0) View.VISIBLE else View.GONE
        bTime.text = if (start > 0) TvTexts.span(ctx, start, stop) else ""
        val onNow = start in 1..n && n < stop
        bProgress.visibility = if (onNow && browse == 0 && !archive && settings.getBoolean(KEY_IPTV_PROGRESS, true)) View.VISIBLE else View.GONE
        if (onNow) bProgress.fraction = (n - start).toFloat() / maxOf(1L, stop - start)
        (bLeft.layoutParams as LinearLayout.LayoutParams).let { lp ->
            val gap = if (bProgress.visibility == View.VISIBLE) ctx.tvPx(14f) else 0
            if (lp.marginStart != gap) { lp.marginStart = gap; bLeft.layoutParams = lp }
        }
        bLeft.text = when {
            start <= 0 -> ""
            archive -> ctx.getString(R.string.rd_tv_from_archive, TvTexts.day(ctx, catchup!!.start))
            start > n -> ctx.getString(R.string.rd_tv_in, TvTexts.duration(ctx, start - n))
            stop <= n -> ctx.getString(R.string.rd_tv_ended_ago, TvTexts.duration(ctx, n - stop))
            browse == 0 -> ctx.getString(R.string.rd_tv_left, TvTexts.duration(ctx, stop - n))
            else -> ctx.getString(R.string.rd_tv_on_now)
        }
        // what comes after the programme shown
        val next = when {
            archive || level != 0 || !settings.getBoolean(KEY_IPTV_NEXT, true) -> null
            browse == 0 -> c.next(n)
            else -> browseList.getOrNull(browseIndexNow + browse + 1)
        }
        bNextText.text = if (next != null) TvTexts.time(ctx, next.start) + "  " + next.title else ""
        bNextRow.visibility = if (next != null && next.title.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** the action of the programme shown: from its start (the archive), Watch a past one, Remind Me of one to come */
    private fun bindAction(c: TvChannel, e: Entry?) {
        val n = System.currentTimeMillis() / 1000
        val text = when {
            TvPlayback.catchup != null && browse == 0 -> ctx.getString(R.string.rd_tv_back_to_live)
            browse == 0 -> if (c.catchupDays > 0 && e != null) ctx.getString(R.string.rd_tv_watch_from_the_start) else ""
            e == null -> ""
            e.stop <= n -> if (LiveTv.isArchived(c, e, n)) ctx.getString(R.string.rd_tv_watch) else ""
            e.start > n -> ctx.getString(if (LiveTv.hasReminder(c.key, e.start)) R.string.rd_tv_reminder_set else R.string.rd_tv_remind_me)
            else -> ctx.getString(R.string.rd_tv_watch)
        }
        bAction.text = text
        bAction.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun action() {
        val c = channel() ?: return
        val n = System.currentTimeMillis() / 1000
        val e = shownEntry(c)
        when {
            TvPlayback.catchup != null && browse == 0 -> TvPlayback.backToLive(ctx)
            browse == 0 -> TvPlayback.playFromStart(ctx)
            e == null -> {}
            e.stop <= n -> TvPlayback.playCatchup(ctx, c.key, e.start, e.stop, e.title)
            e.start > n -> { LiveTv.toggleReminder(c.key, e.start, e.title); TvReminders.schedule(ctx); bind() }
            else -> TvPlayback.play(ctx, c.key)
        }
        schedule()
    }

    private fun message(text: String) {
        bTitle.text = text
        if (!bannerShown) showBanner(true)
    }

    /* ---- the number typed ---- */

    private fun digit(d: Int) {
        if (typed.length >= 4) typed = ""
        typed += d.toString()
        numberBox.text = typed
        numberBox.visibility = View.VISIBLE
        handler.removeCallbacks(commitNumber)
        handler.postDelayed(commitNumber, 2000)
    }

    private fun backspace(): Boolean {
        if (typed.isEmpty()) return false
        typed = typed.dropLast(1)
        numberBox.text = typed
        if (typed.isEmpty()) { numberBox.visibility = View.GONE; handler.removeCallbacks(commitNumber) }
        return true
    }

    private fun commit() {
        handler.removeCallbacks(commitNumber)
        val n = typed.toIntOrNull()
        typed = ""
        numberBox.visibility = View.GONE
        if (n != null) TvPlayback.playNumber(ctx, n)
    }

    /* ---- the keys of the TV, before the player's ---- */

    /** A panel of the player (its menu, the tracks, the queue) has the focus: the D-pad is its own there. */
    private fun panelFocused(): Boolean {
        val f = activity.currentFocus ?: return false
        if (!f.isShown || f === activity.window.decorView || f.id == R.id.player_root) return false
        // the player's bar is not a panel: Up and Down still change the channel
        val hud = activity.findViewById<View>(R.id.progress_overlay)
        var v: View? = f
        while (v != null) {
            if (v === hud || v === root) return false
            v = v.parent as? View
        }
        return true
    }

    private fun onKey(event: KeyEvent): Boolean {
        if (!active()) return false
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        // the keys of the player's own menus and dialogs stay theirs
        if (activity.isShowingDialog) return false
        if (digit >= 0) { if (down) digit(digit); return true }
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) TvPlayback.zap(ctx, 1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) TvPlayback.zap(ctx, -1); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL -> { if (down) TvPlayback.previousChannel(ctx); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { if (down) { if (bannerShown) showBanner(false) else { bind(); showBanner(true) } }; return true }
            KeyEvent.KEYCODE_DEL -> return down && backspace()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (typed.isNotEmpty()) { if (down) commit(); return true }
                // the banner too, with the player's controls
                if (down && !bannerShown) { bind(); showBanner(true) }
                return false
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // a TV box: Up and Down change the channel (a control of the player with the focus keeps them)
                if (activity.isLocked || panelFocused()) return false
                if (down) TvPlayback.zap(ctx, if (code == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // the banner shown: Left and Right go through the guide of the channel (they seek elsewhere)
                if (!bannerShown || TvPlayback.catchup != null || !(channel()?.hasGuide ?: false)) return false
                if (down) browse(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (typed.isNotEmpty()) { if (down) { typed = ""; numberBox.visibility = View.GONE; handler.removeCallbacks(commitNumber) }; return true }
                return false
            }
        }
        return false
    }

    /* ---- the touches: a swipe across the video changes the channel; a tap shows the banner ---- */

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2f
    private var downX = 0f
    private var downY = 0f
    private var axis = 0          // 0 undecided, 1 across (ours), 2 down (the player's)
    private val held = ArrayList<MotionEvent>()
    private var tracking = false

    private fun inBanner(ev: MotionEvent): Boolean {
        if (!bannerShown || banner.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        // the bar itself: a touch on the shade above it is the video's
        bBody.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + bBody.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + bBody.height
    }

    private fun onTouch(ev: MotionEvent, pass: (MotionEvent) -> Boolean): Boolean {
        if (!active() || TvPlayback.catchup != null || activity.isLocked || ev.pointerCount > 1) {
            if (tracking) flush(pass)
            return pass(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = !inBanner(ev)
                axis = 0
                downX = ev.rawX
                downY = ev.rawY
                return pass(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return pass(ev)
                if (axis == 0) {
                    val dx = abs(ev.rawX - downX)
                    val dy = abs(ev.rawY - downY)
                    if (dx < slop && dy < slop) {
                        held.add(MotionEvent.obtain(ev))
                        return true
                    }
                    axis = if (dx > dy * 1.5f) 1 else 2
                    if (axis == 1) {
                        // the player let go of the touch: nothing seeks
                        clearHeld()
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        pass(cancel)
                        cancel.recycle()
                        return true
                    }
                    flush(pass)
                }
                return if (axis == 1) true else pass(ev)
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return pass(ev)
                tracking = false
                if (axis == 1) {
                    val dx = ev.rawX - downX
                    if (abs(dx) > ctx.tvDp(72f)) TvPlayback.zap(ctx, if (dx < 0) 1 else -1)
                    return true
                }
                val wasTap = axis == 0
                flush(pass)
                val r = pass(ev)
                if (wasTap && settings.getString(KEY_IPTV_BANNER, "0") != "3") handler.postDelayed({ if (active()) { if (activity.isShowing) { bind(); showBanner(true) } else showBanner(false) } }, 400)
                return r
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasOurs = axis == 1
                tracking = false
                clearHeld()
                return if (wasOurs) true else pass(ev)
            }
        }
        return pass(ev)
    }

    private fun flush(pass: (MotionEvent) -> Boolean) {
        for (m in held) { pass(m); m.recycle() }
        held.clear()
    }

    private fun clearHeld() {
        for (m in held) m.recycle()
        held.clear()
    }

    private inner class Callback(private val wrapped: Window.Callback) : Window.Callback by wrapped {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || wrapped.dispatchKeyEvent(event)
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = onTouch(event) { wrapped.dispatchTouchEvent(it) }
    }
}
