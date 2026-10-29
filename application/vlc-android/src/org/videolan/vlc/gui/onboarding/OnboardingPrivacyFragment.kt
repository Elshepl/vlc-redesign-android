package org.videolan.vlc.gui.onboarding

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import org.videolan.tools.KEY_SHOW_UPDATE
import org.videolan.tools.PLAYBACK_HISTORY
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R
import org.videolan.vlc.gui.preferences.rd.RdToggleView
import org.videolan.vlc.gui.preferences.rd.rdAttr
import org.videolan.vlc.gui.preferences.rd.rdDp

/**
 * VLC Redesign: the desktop's online choices step ("Choose what VLC does online by itself."), as it applies to
 * VLC for Android: it connects to nothing by itself, so the step says so and asks what is kept (the history) and,
 * in test builds, the daily update check. Under them, the credit of the interface's design, as About shows it.
 */
class OnboardingPrivacyFragment : OnboardingFragment() {
    private lateinit var titleView: TextView
    override fun getDefaultViewForTalkback() = titleView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.onboarding_privacy, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleView = view.findViewById(R.id.privacy_title)
        val rows = view.findViewById<LinearLayout>(R.id.privacy_rows)
        val settings = Settings.getInstance(requireContext())
        row(rows, getString(R.string.rd_save_recent), getString(R.string.rd_ob_history_help), settings.getBoolean(PLAYBACK_HISTORY, true)) {
            settings.putSingle(PLAYBACK_HISTORY, it)
        }
        // the update check only exists in test builds (AutoUpdate)
        if (BuildConfig.DEBUG) row(rows, getString(R.string.rd_check_updates), getString(R.string.rd_ob_updates_help), settings.getBoolean(KEY_SHOW_UPDATE, true)) {
            settings.putSingle(KEY_SHOW_UPDATE, it)
        }
        val accent = String.format("#%06X", 0xFFFFFF and requireContext().rdAttr(R.attr.colorPrimary))
        view.findViewById<TextView>(R.id.design_credit).text =
                HtmlCompat.fromHtml(getString(R.string.rd_about_designed_by, accent), HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun row(parent: LinearLayout, label: String, help: String, on: Boolean, changed: (Boolean) -> Unit) {
        val ctx = requireContext()
        val d = { v: Float -> ctx.rdDp(v).toInt() }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = d(56f)
            setPadding(0, d(6f), 0, d(6f))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(ctx, R.drawable.rd_settings_sidebar_item)
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = ResourcesCompat.getFont(ctx, R.font.inter)
            setTextColor(ContextCompat.getColor(ctx, R.color.rd_dark_fg_1))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        texts.addView(TextView(ctx).apply {
            text = help
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = ResourcesCompat.getFont(ctx, R.font.inter)
            setTextColor(ContextCompat.getColor(ctx, R.color.rd_dark_fg_a3))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d(12f) })
        val toggle = RdToggleView(ctx).apply { isChecked = on }
        row.addView(toggle)
        row.contentDescription = label
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isCheckable = true
                info.isChecked = toggle.isChecked
                info.className = android.widget.Switch::class.java.name
            }
        }
        row.setOnClickListener {
            toggle.toggle()
            changed(toggle.isChecked)
        }
        parent.addView(row)
    }

    companion object {
        fun newInstance() = OnboardingPrivacyFragment()
    }
}
