package org.videolan.vlc.gui.onboarding

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.activityViewModels
import org.videolan.resources.AndroidDevices
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.R
import org.videolan.vlc.gui.preferences.rd.rdAttr
import org.videolan.vlc.gui.preferences.rd.rdDp

/**
 * VLC Redesign: ThemeStep.qml. Light, Dark and System as the design's 100 dp tiles (radius 12; System half and
 * half), the chosen one in a 3 dp ring of the accent; the nine accent discs under them, the chosen one bigger and
 * ringed. Choosing an accent redraws the onboarding in it (its Next button, its rings) as on the desktop.
 */
class OnboardingThemeFragment : OnboardingFragment() {

    private val viewModel: OnboardingViewModel by activityViewModels()
    private lateinit var titleView: TextView
    private lateinit var themeDescription: TextView
    override fun getDefaultViewForTalkback() = titleView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.onboarding_theme, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleView = view.findViewById(R.id.theme_title)
        themeDescription = view.findViewById(R.id.themeDescription)
        buildTiles(view.findViewById(R.id.theme_tiles))
        buildAccents(view.findViewById(R.id.accent_discs))
        describe()
    }

    private val systemMode get() = if (AndroidDevices.canUseSystemNightMode()) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM else AppCompatDelegate.MODE_NIGHT_AUTO

    private fun buildTiles(box: LinearLayout) {
        val ctx = requireContext()
        val d = { v: Float -> ctx.rdDp(v).toInt() }
        box.removeAllViews()
        val themes = listOf(Triple(AppCompatDelegate.MODE_NIGHT_NO, R.string.rd_light, 0xFFAFAFAF.toInt()),
                Triple(AppCompatDelegate.MODE_NIGHT_YES, R.string.rd_dark, 0xFF212121.toInt()),
                Triple(systemMode, R.string.rd_system, 0))
        for ((mode, label, colour) in themes) {
            val chosen = viewModel.theme == mode
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = getString(label)
                isSelected = chosen
                setOnClickListener {
                    viewModel.theme = mode
                    buildTiles(box)
                    describe()
                }
            }
            val ring = FrameLayout(ctx).apply {
                background = ContextCompat.getDrawable(ctx, R.drawable.rd_onboarding_theme_ring)
                isSelected = chosen
                setPadding(d(9f), d(9f), d(9f), d(9f))
            }
            val tile = View(ctx).apply {
                background = if (colour != 0) GradientDrawable().apply { cornerRadius = ctx.rdDp(12f); setColor(colour) }
                else ContextCompat.getDrawable(ctx, R.drawable.rd_ic_theme_system)
                clipToOutline = true
            }
            ring.addView(tile, FrameLayout.LayoutParams(d(76f), d(76f)))
            column.addView(ring)
            column.addView(TextView(ctx).apply {
                text = getString(label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = ResourcesCompat.getFont(ctx, R.font.inter)
                setTextColor(ContextCompat.getColor(ctx, if (chosen) R.color.rd_dark_fg_1 else R.color.rd_dark_fg_a2))
                setPadding(0, d(10f), 0, 0)
            })
            box.addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = d(6f); marginEnd = d(6f)
            })
        }
    }

    private fun buildAccents(box: LinearLayout) {
        val ctx = requireContext()
        val d = { v: Float -> ctx.rdDp(v).toInt() }
        val settings = Settings.getInstance(ctx)
        val current = settings.getString("rd_accent", "orange") ?: "orange"
        val values = resources.getStringArray(R.array.rd_accent_values)
        val names = resources.getStringArray(R.array.rd_accent_entries)
        // the width the row may take: nine discs of 30 dp (36 for the chosen one) and their gaps
        val gap = d(if (resources.configuration.screenWidthDp < 400) 4f else 8f)
        values.forEachIndexed { i, v ->
            val chosen = v == current
            val colour = ContextCompat.getColor(ctx, resources.getIdentifier("rd_accent_${v}_main", "color", ctx.packageName))
            val disc = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colour)
                    if (chosen) setStroke(d(3f), ctx.rdAttr(R.attr.rd_accent_bright))
                }
                alpha = if (chosen) 1f else 0.62f
                contentDescription = getString(R.string.rd_accent_named, names[i])
                isSelected = chosen
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!chosen) {
                        settings.putSingle("rd_accent", v)
                        requireActivity().recreate()
                    }
                }
            }
            val size = d(if (chosen) 36f else 30f)
            box.addView(disc, LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = gap / 2; marginEnd = gap / 2
            })
        }
    }

    private fun describe() {
        themeDescription.setText(when (viewModel.theme) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.string.light_theme
            AppCompatDelegate.MODE_NIGHT_YES -> R.string.enable_black_theme
            else -> if (AndroidDevices.canUseSystemNightMode()) R.string.daynight_system_explanation else R.string.daynight_legacy_explanation
        })
    }

    companion object {
        fun newInstance() = OnboardingThemeFragment()
    }
}
