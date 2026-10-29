/*
 * VLC Redesign, UI tour probe (debug builds only, src/debug): what the tour driver (tools/autotour) asks the app
 * while it walks every screen. It dumps every window of the process with what a uiautomator dump cannot see
 * (ellipsized lines, the text's own rectangle, colours, the resource entry names, the scrolling parents), seeds
 * the test state (preferences, Live TV sources of the local mock server, a fake cast renderer that never plays)
 * and clicks a view by its id when no point on the screen can reach it.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 */
package org.videolan.vlc.autotour

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

private const val TAG = "VLC/AutoTour"

/** Started with the process (a provider runs before Application.onCreate returns): StrictMode, activities. */
class AutoTourProbe : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedRegistrationObjects()
                .detectActivityLeaks().detectFileUriExposure().penaltyLog().build())
        app.registerActivityLifecycleCallbacks(Tracker)
        Commands.startServer(app)
        return true
    }
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}

internal object Tracker : Application.ActivityLifecycleCallbacks {
    var resumed: WeakReference<Activity>? = null
    val alive = ArrayList<WeakReference<Activity>>()
    override fun onActivityCreated(a: Activity, b: Bundle?) { alive.add(WeakReference(a)) }
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityResumed(a: Activity) { resumed = WeakReference(a) }
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) { alive.removeAll { it.get() == null || it.get() === a } }
}

/**
 * adb shell am broadcast -n org.videolan.vlc.debug/org.videolan.vlc.autotour.AutoTourReceiver -a <action> [extras]
 * Actions: dump (--es out <file>), pref (--es key k --es type s|b|i --es value v), seedtv (--es json <file>),
 * renderer (--es name n), click (--es id entry [--ei index n]), finish. The answer: a line in the result data.
 */
class AutoTourReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val action = intent.action ?: return
        val ex = HashMap<String, String>()
        intent.extras?.keySet()?.forEach { k -> intent.extras?.get(k)?.let { ex[k] = it.toString() } }
        if (action in Commands.BACKGROUND) {
            val pending = goAsync()
            Thread { pending.resultData = Commands.run(ctx, action, ex); pending.finish() }.start()
        } else resultData = Commands.run(ctx, action, ex)
    }
}

/**
 * The commands, from the receiver or from the socket "vlc_autotour" (faster than am broadcast: the driver keeps
 * `adb forward tcp:N localabstract:vlc_autotour` and sends one line "action {json extras}", the answer comes back).
 */
internal object Commands {
    val BACKGROUND = setOf("seedtv")

    fun run(ctx: Context, action: String, ex: Map<String, String>): String = try {
        when (action) {
            "ping" -> "ok"
            "dump" -> {
                val json = Dumper(ctx).dump().toString()
                ex["out"]?.let { File(it).apply { parentFile?.mkdirs() }.writeText(json); "ok ${json.length}" } ?: json
            }
            "pref" -> {
                val key = ex["key"]!!
                val v = ex["value"]
                val e = org.videolan.tools.Settings.getInstance(ctx).edit()
                when (ex["type"] ?: "s") {
                    "b" -> e.putBoolean(key, v == "true")
                    "i" -> e.putInt(key, v!!.toInt())
                    "del" -> e.remove(key)
                    else -> e.putString(key, v)
                }
                e.commit()
                if (key == "set_locale") org.videolan.resources.AppContextProvider.setLocale(v)
                "ok"
            }
            "str" -> {
                val res = (Tracker.resumed?.get() ?: ctx).resources
                val id = res.getIdentifier(ex["key"], "string", ctx.packageName)
                if (id == 0) "" else res.getString(id)
            }
            "seedtv" -> seedTv(ctx, JSONArray(ex["spec"] ?: File(ex["json"]!!).readText()))
            "renderer" -> fakeRenderer(ex["name"] ?: "AutoTour Fake TV")
            "click" -> click(ex["id"]!!, ex["index"]?.toInt() ?: 0, ex["text"])
            "finish" -> {
                Tracker.alive.mapNotNull { it.get() }.forEach { it.finishAndRemoveTask() }
                "ok"
            }
            else -> "unknown $action"
        }
    } catch (e: Throwable) {
        Log.e(TAG, "command $action failed", e)
        "error $e"
    }

    private val main = Handler(Looper.getMainLooper())

    fun startServer(ctx: Context) {
        Thread({
            val server = try { android.net.LocalServerSocket("vlc_autotour") } catch (e: Exception) { Log.w(TAG, "no socket", e); return@Thread }
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { continue }
                Thread {
                    try {
                        val line = s.inputStream.bufferedReader(Charsets.UTF_8).readLine() ?: ""
                        val sp = line.indexOf(' ')
                        val action = if (sp < 0) line else line.substring(0, sp)
                        val ex = HashMap<String, String>()
                        if (sp > 0) JSONObject(line.substring(sp + 1)).let { o -> o.keys().forEach { k -> ex[k] = o.get(k).toString() } }
                        val answer: String = if (action in BACKGROUND) run(ctx, action, ex) else {
                            var r = "busy: the main thread did not answer in 20 s"
                            val latch = java.util.concurrent.CountDownLatch(1)
                            main.post { r = run(ctx, action, ex); latch.countDown() }
                            latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
                            r
                        }
                        s.outputStream.write(answer.toByteArray(Charsets.UTF_8))
                        s.outputStream.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "socket command failed", e)
                    } finally { try { s.close() } catch (e: Exception) { } }
                }.start()
            }
        }, "AutoTourSocket").apply { isDaemon = true }.start()
    }

    /** Live TV sources of the local mock server; the ones of an earlier run (same name) are replaced. */
    private fun seedTv(ctx: Context, spec: JSONArray): String {
        val tv = org.videolan.vlc.livetv.LiveTv
        tv.init(ctx)
        tv.start()
        val until = System.currentTimeMillis() + 8000
        while (!tv.state.value.loaded && System.currentTimeMillis() < until) Thread.sleep(200)
        val names = (0 until spec.length()).map { spec.getJSONObject(it).optString("name") }.toSet()
        tv.state.value.sources.filter { it.name in names }.forEach { tv.removeSource(it.id) }
        for (i in 0 until spec.length()) {
            val o = spec.getJSONObject(i)
            val c = org.videolan.vlc.livetv.SourceConfig()
            c.name = o.optString("name")
            c.kind = o.optString("kind", "m3u")
            c.url = o.optString("url")
            c.server = o.optString("server")
            c.username = o.optString("username")
            c.password = o.optString("password")
            o.optJSONArray("epg")?.let { a -> for (j in 0 until a.length()) c.epg.add(a.getString(j)) }
            tv.saveSource(c)
        }
        return "ok ${spec.length()}"
    }

    /**
     * A renderer the cast picker lists, made by reflection with no native item behind it: the tour only looks at
     * the picker and never chooses it (choosing it would hand libvlc a null renderer). Never a real device.
     */
    private fun fakeRenderer(name: String): String {
        val cls = org.videolan.libvlc.RendererItem::class.java
        val ctor = cls.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        ctor.isAccessible = true
        val flags = org.videolan.libvlc.RendererItem.LIBVLC_RENDERER_CAN_AUDIO or org.videolan.libvlc.RendererItem.LIBVLC_RENDERER_CAN_VIDEO
        val item = ctor.newInstance("autotour-fake", name, "chromecast", flags, 0L)
        Handler(Looper.getMainLooper()).post {
            val list = org.videolan.vlc.RendererDelegate.renderers
            if (list.getList().none { it.name == "autotour-fake" }) list.add(item)
        }
        return "ok"
    }

    private fun click(id: String, index: Int, text: String?): String {
        val found = ArrayList<View>()
        for (root in Dumper.roots().reversed()) {
            Dumper.walk(root) { v ->
                val name = Dumper.idName(v)
                if (v.isShown && name == id && (text == null || (v as? TextView)?.text?.toString()?.contains(text) == true)) found.add(v)
            }
            if (found.isNotEmpty()) break
        }
        val v = found.getOrNull(index) ?: return "missing $id"
        return if (v.performClick()) "ok" else "noclick $id"
    }
}

/** The app's strings by their text, in the shown language and in English: a text on screen gets its key. */
internal object Strings {
    private var forLocale = ""
    var cur: Map<String, String> = emptyMap()
    var en: Map<String, String> = emptyMap()

    private fun mapOf(res: android.content.res.Resources, fields: List<Pair<String, Int>>): Map<String, String> {
        val m = HashMap<String, String>(fields.size * 2)
        for ((name, id) in fields) try {
            val t = res.getString(id)
            if (t.length >= 2 && !m.containsKey(t)) m[t] = name
        } catch (e: Exception) { }
        return m
    }

    fun ensure(res: android.content.res.Resources, ctx: Context) {
        val tag = res.configuration.locales[0].toLanguageTag()
        if (tag == forLocale) return
        val fields = try {
            Class.forName("org.videolan.mobile.app.R\$string").fields.map { it.name to it.getInt(null) }
        } catch (e: Exception) { emptyList() }
        cur = mapOf(res, fields)
        val enCfg = Configuration(res.configuration).apply { setLocale(java.util.Locale.ENGLISH) }
        en = if (tag.startsWith("en")) cur else mapOf(ctx.createConfigurationContext(enCfg).resources, fields)
        forLocale = tag
    }
}

internal class Dumper(private val ctx: Context) {
    companion object {
        fun roots(): List<View> = if (Build.VERSION.SDK_INT >= 29)
            android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }
        else listOfNotNull(Tracker.resumed?.get()?.window?.decorView)

        fun walk(v: View, f: (View) -> Unit) {
            f(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), f)
        }

        fun idName(v: View): String = if (v.id == View.NO_ID || v.id == 0) "" else try {
            v.resources.getResourceEntryName(v.id)
        } catch (e: Exception) { "" }

        fun hex(c: Int) = String.format("#%08X", c)
    }

    private val density = ctx.resources.displayMetrics.density

    fun dump(): JSONObject {
        val root = JSONObject()
        val act = Tracker.resumed?.get()
        root.put("activity", act?.javaClass?.name ?: "")
        root.put("activities", JSONArray(Tracker.alive.mapNotNull { it.get()?.javaClass?.simpleName }))
        val frags = JSONArray()
        (act as? FragmentActivity)?.supportFragmentManager?.fragments?.forEach { addFragments(it, frags, 0) }
        root.put("fragments", frags)
        val cfg: Configuration = (act ?: ctx).resources.configuration
        Strings.ensure((act ?: ctx).resources, ctx)
        root.put("config", JSONObject().apply {
            put("locale", cfg.locales[0].toLanguageTag())
            put("night", (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            put("fontScale", cfg.fontScale.toDouble())
            put("w", cfg.screenWidthDp); put("h", cfg.screenHeightDp); put("sw", cfg.smallestScreenWidthDp)
            put("density", density.toDouble()); put("rtl", cfg.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        })
        val windows = JSONArray()
        roots().forEachIndexed { wi, r ->
            if (!r.isShown && r.visibility != View.VISIBLE) return@forEachIndexed
            val w = JSONObject()
            val lp = r.layoutParams as? WindowManager.LayoutParams
            w.put("title", lp?.title?.toString() ?: "")
            w.put("type", lp?.type ?: 0)
            w.put("root", r.javaClass.name)
            val loc = IntArray(2); r.getLocationOnScreen(loc)
            w.put("b", JSONArray(listOf(loc[0], loc[1], loc[0] + r.width, loc[1] + r.height)))
            val views = JSONArray()
            dumpView(r, -1, false, views)
            w.put("views", views)
            w.put("index", wi)
            windows.put(w)
        }
        root.put("windows", windows)
        return root
    }

    private fun addFragments(f: Fragment, out: JSONArray, depth: Int) {
        if (!f.isAdded) return
        out.put(JSONObject().put("name", f.javaClass.name).put("tag", f.tag ?: "").put("visible", f.isVisible).put("depth", depth))
        f.childFragmentManager.fragments.forEach { addFragments(it, out, depth + 1) }
    }

    /** A scrolling or paging parent (its children may sit outside the window by design); by class names. */
    private fun scrolls(v: View): Boolean {
        if (v is ScrollView || v is HorizontalScrollView || v is AbsListView) return true
        var c: Class<*>? = v.javaClass
        while (c != null && c != View::class.java) {
            val n = c.simpleName
            if (n == "RecyclerView" || n == "NestedScrollView" || n == "ViewPager" || n == "ViewPager2") return true
            c = c.superclass
        }
        return false
    }

    private fun dumpView(v: View, parent: Int, inScroll: Boolean, out: JSONArray) {
        if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return
        val me = out.length()
        val o = JSONObject()
        o.put("c", v.javaClass.simpleName)
        o.put("cls", v.javaClass.name)
        o.put("p", parent)
        val id = idName(v)
        if (id.isNotEmpty()) o.put("id", id)
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        o.put("b", JSONArray(listOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)))
        val vis = Rect()
        if (v.getGlobalVisibleRect(vis)) o.put("vis", JSONArray(listOf(vis.left, vis.top, vis.right, vis.bottom))) else o.put("hidden", true)
        if (v.alpha < 0.99f) o.put("alpha", v.alpha.toDouble())
        if (v.isClickable) o.put("clk", true)
        if (v.isLongClickable) o.put("lclk", true)
        if (!v.isEnabled) o.put("dis", true)
        if (v.isFocusable) o.put("foc", true)
        if (v.isSelected) o.put("sel", true)
        if (v.isActivated) o.put("act", true)
        if (inScroll) o.put("insc", true)
        val sc = scrolls(v)
        if (sc) o.put("scroll", true)
        v.contentDescription?.let { if (it.isNotEmpty()) { o.put("cd", it.toString()); key(it.toString(), o, "cdk") } }
        if (Build.VERSION.SDK_INT >= 26) v.tooltipText?.let { if (it.isNotEmpty()) o.put("tip", it.toString()) }
        if (v.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO ||
                v.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) o.put("ifa", "no")
        (v.background as? ColorDrawable)?.let { o.put("bg", hex(it.color)) }
        v.backgroundTintList?.let { o.put("bgt", hex(it.defaultColor)) }
        if (v is TextView) dumpText(v, o, loc)
        if (v is ImageView) {
            val d = v.drawable
            if (d != null) {
                o.put("img", if (d is BitmapDrawable) "bitmap" else d.javaClass.simpleName)
                v.imageTintList?.let { o.put("tint", hex(it.getColorForState(v.drawableState, it.defaultColor))) }
            } else o.put("img", "none")
        }
        if (v.javaClass.simpleName.contains("SurfaceView") || v.javaClass.simpleName.contains("TextureView")) o.put("media", true)
        out.put(o)
        if (v is ViewGroup) for (i in 0 until v.childCount) dumpView(v.getChildAt(i), me, inScroll || sc, out)
    }

    /** The string key of a text: in the shown language ("sk"), or only in English ("sk" + "en": untranslated). */
    private fun key(text: String, o: JSONObject, name: String) {
        Strings.cur[text]?.let { o.put(name, it); if (Strings.en[text] == it && Strings.cur !== Strings.en) o.put(name + "en", true); return }
        Strings.en[text]?.let { o.put(name, it); o.put(name + "en", true) }
    }

    private fun dumpText(t: TextView, o: JSONObject, loc: IntArray) {
        val text = t.text?.toString() ?: ""
        o.put("txt", text)
        key(text, o, "sk")
        t.hint?.let { if (it.isNotEmpty()) o.put("hint", it.toString()) }
        o.put("tc", hex(t.currentTextColor))
        o.put("tsp", (t.textSize / t.resources.displayMetrics.scaledDensity).toDouble())
        val l = t.layout ?: return
        val lines = l.lineCount
        o.put("lines", lines)
        if (t.maxLines in 1 until Int.MAX_VALUE) o.put("maxl", t.maxLines)
        var ell = 0
        var left = Float.MAX_VALUE; var right = 0f
        for (i in 0 until lines) {
            ell += l.getEllipsisCount(i)
            left = minOf(left, l.getLineLeft(i)); right = maxOf(right, l.getLineRight(i))
        }
        if (ell > 0) o.put("ell", ell)
        if (text.isNotEmpty() && lines > 0) {
            val x0 = loc[0] + t.totalPaddingLeft - t.scrollX
            val y0 = loc[1] + t.extendedPaddingTop - t.scrollY
            val shown = minOf(lines, if (t.maxLines > 0) t.maxLines else lines)
            o.put("tr", JSONArray(listOf((x0 + left).toInt(), y0, (x0 + right).toInt(), y0 + l.getLineBottom(shown - 1))))
            // the text wider than its room with no ellipsis (single line, horizontally scrolling): cut
            val room = t.width - t.totalPaddingLeft - t.totalPaddingRight
            if (ell == 0 && right - left > room + 1) o.put("cutw", (right - left - room).toInt())
            // the lines taller than the view: the last ones cut at the bottom
            val roomH = t.height - t.extendedPaddingTop - t.extendedPaddingBottom
            if (l.getLineBottom(shown - 1) > roomH + 2 && !t.canScrollVertically(1) && !t.canScrollVertically(-1)) o.put("cuth", l.getLineBottom(shown - 1) - roomH)
            if (lines > shown && ell == 0) o.put("cutlines", lines - shown)
        }
    }
}
