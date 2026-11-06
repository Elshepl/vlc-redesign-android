/*
 * VLC Redesign: New Playlist of the TV layout at the design's size (Figma 6259:14954, the desktop's NewPlaylistDialog):
 * a dialog of 713 x 589 (#191919, radius 10) with its bar of 50 (#090909: the title 14 at 19, the close button);
 * "Playlist Details" with its help and the name's field (537 x 33), the thumbnail's box of 108 beside it (the first
 * chosen file's picture, as the playlist shows it); the line; "Select Files" with its help and the dashed box of
 * 673 x 186 with its + (the TV's file picker, one file at a time; the chosen ones listed in the box); Cancel and Save
 * (dim until there is a name). Save makes the playlist with the chosen files.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.gui.tablet

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.AppScope
import org.videolan.tools.Settings
import org.videolan.tools.dp
import org.videolan.vlc.R
import org.videolan.vlc.gui.AudioPlayerContainerActivity
import org.videolan.vlc.gui.browser.EXTRA_MRL
import org.videolan.vlc.gui.browser.FilePickerActivity
import org.videolan.vlc.gui.browser.KEY_PICKER_TYPE
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.providers.PickerType

object RdTvNewPlaylistDialog {

    private const val CARD = 0xFF191919.toInt()
    private const val WELL = 0xFF090909.toInt()

    fun show(activity: AudioPlayerContainerActivity, onSaved: (() -> Unit)? = null) {
        if (activity.isFinishing || activity.isDestroyed) return
        val ctx = activity
        val inter = ResourcesCompat.getFont(ctx, R.font.inter)
        val dialog = Dialog(ctx)
        val chosen = mutableListOf<String>()
        val card = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = 10.dp.toFloat(); setColor(CARD) }
            clipToOutline = true
        }
        fun at(v: View, x: Int, y: Int, w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
                card.addView(v, FrameLayout.LayoutParams(if (w > 0) w.dp else w, if (h > 0) h.dp else h).apply { leftMargin = x.dp; topMargin = y.dp })
        fun text(size: Float, color: Int, x: Int, y: Int, value: CharSequence, w: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = TextView(ctx).apply {
            typeface = inter
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
            setTextColor(color)
            includeFontPadding = false
            text = value
            at(this, x, y, w)
        }
        fun focusBg(color: Int, radius: Int, focused: Int = 0xFF2A2A2A.toInt()) = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = radius.dp.toFloat(); setColor(focused) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = radius.dp.toFloat(); setColor(color) })
        }
        // the bar
        at(View(ctx).apply { setBackgroundColor(WELL) }, 0, 0, 713, 50)
        text(14f, 0xFF8F8F8F.toInt(), 19, 17, ctx.getString(R.string.rd_new_playlist))
        at(ImageView(ctx).apply {
            setImageResource(R.drawable.rd_ic_close)
            setColorFilter(0xFF8F8F8F.toInt())
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            contentDescription = ctx.getString(R.string.close)
            isFocusable = true
            isClickable = true
            background = focusBg(0, 4)
            setOnClickListener { dialog.dismiss() }
        }, 672, 9, 32, 32)
        // the playlist's details
        text(16f, 0xFFC6C6C6.toInt(), 20, 78, ctx.getString(R.string.rd_np_details))
        text(14f, 0xFF6B6B6B.toInt(), 20, 111, ctx.getString(R.string.rd_np_details_help), 540)
        val name = EditText(ctx).apply {
            typeface = inter
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(0xFF969696.toInt())
            setHintTextColor(0xFF404040.toInt())
            hint = ctx.getString(R.string.rd_np_hint)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(13.dp, 0, 13.dp, 0)
            includeFontPadding = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            background = GradientDrawable().apply { cornerRadius = 2.dp.toFloat(); setColor(WELL) }
        }
        at(name, 22, 154, 537, 33)
        // the thumbnail: the first chosen file's picture (a playlist shows its first media's)
        val thumb = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = 2.dp.toFloat(); setColor(WELL) }
            clipToOutline = true
        }
        val picture = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumb.addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val thumbPlus = ImageView(ctx).apply {
            setImageResource(R.drawable.rd_tvd_duo_add)
            setColorFilter(0xFF888888.toInt())
            setPadding(7.dp, 7.dp, 7.dp, 7.dp)
            background = GradientDrawable().apply { cornerRadius = 4.dp.toFloat(); setColor(0xFF161616.toInt()) }
        }
        thumb.addView(thumbPlus, FrameLayout.LayoutParams(30.dp, 30.dp, Gravity.CENTER))
        at(thumb, 583, 81, 108, 108)
        // the line
        at(View(ctx).apply { setBackgroundColor(0xFF1D1D1D.toInt()) }, 20, 210, 673, 2)
        // the files
        text(16f, 0xFFC6C6C6.toInt(), 20, 237, ctx.getString(R.string.rd_np_files))
        text(14f, 0xFF6B6B6B.toInt(), 20, 270, ctx.getString(R.string.rd_np_files_help))
        at(View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2.dp.toFloat()
                setColor(WELL)
                setStroke(2.dp, 0xFF2D2D2D.toInt(), 4.dp.toFloat(), 4.dp.toFloat())
            }
        }, 20, 309, 673, 186)
        val boxText = TextView(ctx).apply {
            typeface = inter
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(0xFF404040.toInt())
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            text = ctx.getString(R.string.rd_np_files_tv)
        }
        at(boxText, 40, 362, 633)
        val add = ImageView(ctx).apply {
            setImageResource(R.drawable.rd_tvd_duo_add)
            setColorFilter(0xFF8F8F8F.toInt())
            setPadding(30.dp, 7.dp, 30.dp, 7.dp)
            contentDescription = ctx.getString(R.string.rd_np_files)
            isFocusable = true
            isClickable = true
            background = focusBg(0xFF161616.toInt(), 4)
        }
        at(add, 312, 405, 84, 30)
        // Cancel and Save (.d-btn: 100 x 32, radius 6, Inter SemiBold 14)
        fun button(label: Int, x: Int, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
            typeface = android.graphics.Typeface.create(inter, android.graphics.Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(0xFF8F8F8F.toInt())
            gravity = Gravity.CENTER
            text = ctx.getString(label)
            isFocusable = true
            isClickable = true
            background = focusBg(color, 6)
            setOnClickListener { onClick() }
            at(this, x, 537, 100, 32)
        }
        button(R.string.cancel, 20, 0xFF1B1B1B.toInt()) { dialog.dismiss() }
        val save = button(R.string.save, 593, 0xFF1E1E1E.toInt()) {
            val title = name.text.toString().trim()
            if (title.isEmpty()) return@button
            val files = chosen.toList()
            dialog.dismiss()
            AppScope.launch(Dispatchers.IO) {
                val ml = Medialibrary.getInstance()
                val playlist = ml.createPlaylist(title, Settings.includeMissing, false) ?: return@launch
                for (mrl in files) (ml.getMedia(Uri.parse(mrl)) ?: ml.addMedia(mrl, -1L))?.let { playlist.append(it.id) }
                withContext(Dispatchers.Main) { onSaved?.invoke() }
            }
        }
        // Save is dim until there is a name (the design's disabled button)
        fun refresh() {
            val on = name.text.toString().isNotBlank()
            save.setTextColor(if (on) 0xFF8F8F8F.toInt() else 0xFF515151.toInt())
            save.isFocusable = on
        }
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()
        // the + : the TV's file picker (videos and music), one file at a time
        val key = "rd_tv_new_playlist_" + System.nanoTime()
        val pick = activity.activityResultRegistry.register(key, ActivityResultContracts.StartActivityForResult()) { result ->
            val mrl = result.data?.getStringExtra(EXTRA_MRL)
            if (result.resultCode != Activity.RESULT_OK || mrl.isNullOrEmpty() || mrl in chosen) return@register
            chosen.add(mrl)
            boxText.setTextColor(0xFF8F8F8F.toInt())
            boxText.text = ctx.getString(R.string.rd_np_chosen, chosen.joinToString(", ") { Uri.parse(it).lastPathSegment ?: it })
            if (chosen.size == 1) {
                thumbPlus.visibility = View.GONE
                activity.lifecycleScope.launch {
                    val mw = withContext(Dispatchers.IO) { Medialibrary.getInstance().getMedia(Uri.parse(mrl)) }
                    val bmp = withContext(Dispatchers.IO) {
                        mw?.artworkMrl?.takeIf { it.isNotEmpty() }?.let { AudioUtil.readCoverBitmap(Uri.decode(it), 108.dp) }
                    }
                    if (bmp != null) picture.setImageBitmap(bmp) else thumbPlus.visibility = View.VISIBLE
                }
            }
            add.post { add.requestFocus() }
        }
        add.setOnClickListener {
            // in the folder of the last chosen file (the browser opens at its intent's location)
            val intent = Intent(ctx, FilePickerActivity::class.java).putExtra(KEY_PICKER_TYPE, PickerType.MEDIA.ordinal)
            chosen.lastOrNull()?.substringBeforeLast('/', "")?.takeIf { it.contains("://") }?.let { intent.data = Uri.parse(it) }
            pick.launch(intent)
        }
        dialog.setOnDismissListener { pick.unregister() }
        dialog.setContentView(card, ViewGroup.LayoutParams(713.dp, 589.dp))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(713.dp, 589.dp)
            setDimAmount(0.4f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        org.videolan.vlc.gui.helpers.RdTvFocusRing.attach(dialog.window)
        dialog.show()
        name.requestFocus()
    }
}
