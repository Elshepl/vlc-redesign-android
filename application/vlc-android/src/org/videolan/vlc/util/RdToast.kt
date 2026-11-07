/*
 * VLC Redesign: the app's floating messages in the redesign's framed snackbar.
 *
 * Since Android 11 a text toast is drawn by the system: no app can give it the 1 px window border (#4C4A48) and
 * the panel colour of the redesign. RdToast keeps the part of android.widget.Toast the app uses (makeText, show,
 * cancel, setText, setGravity), so a file switches with `import org.videolan.vlc.util.RdToast as Toast`, and
 * shows the message as the snackbar of the activity in front (the theme frames it); when no activity of the app
 * is in front (a message of the playback service while another app is used), it is still a system toast.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.google.android.material.snackbar.Snackbar
import org.videolan.vlc.gui.BaseActivity
import java.lang.ref.WeakReference

class RdToast private constructor(context: Context, private var text: CharSequence, private val duration: Int) {
    /** the application's context: a toast kept by a long-lived owner (the playback service) holds no activity */
    private val context: Context = context.applicationContext ?: context
    /** weak, and dropped when it is dismissed: a snackbar holds its activity, which must go when it is destroyed */
    private var snack: WeakReference<Snackbar>? = null
    private var system: android.widget.Toast? = null

    fun setText(s: CharSequence) { text = s }
    fun setText(resId: Int) { text = context.getString(resId) }
    /** the snackbar sits at the bottom of the activity, as every snackbar of the app */
    @Suppress("UNUSED_PARAMETER")
    fun setGravity(gravity: Int, xOffset: Int, yOffset: Int) {}

    fun show() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { show() }; return }
        cancel()
        val activity = resumed?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        val anchor = activity?.let { (it as? BaseActivity)?.getSnackAnchorView() ?: it.findViewById<View>(android.R.id.content) }
        if (anchor != null) {
            val bar = Snackbar.make(anchor, text, if (duration == LENGTH_LONG) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
            bar.addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (snack?.get() === transientBottomBar) snack = null
                }
            })
            snack = WeakReference(bar)
            bar.show()
        } else {
            system = android.widget.Toast.makeText(context, text, duration).also { it.show() }
        }
    }

    fun cancel() {
        snack?.get()?.dismiss()
        snack = null
        system?.cancel()
        system = null
    }

    companion object {
        const val LENGTH_SHORT = android.widget.Toast.LENGTH_SHORT
        const val LENGTH_LONG = android.widget.Toast.LENGTH_LONG
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var resumed: WeakReference<Activity>? = null
        @Volatile private var registered = false

        // (nullable as the Java signatures the callers were written for)
        fun makeText(context: Context?, text: CharSequence?, duration: Int) = (context ?: org.videolan.resources.AppContextProvider.appContext).let { RdToast(it, text ?: "", duration) }
        fun makeText(context: Context?, resId: Int, duration: Int) = (context ?: org.videolan.resources.AppContextProvider.appContext).let { RdToast(it, it.getString(resId), duration) }

        /** Follows the activity in front; called once from the application's start */
        fun init(app: Application) {
            if (registered) return
            registered = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
                override fun onActivityPaused(activity: Activity) { if (resumed?.get() === activity) resumed = null }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }
}
