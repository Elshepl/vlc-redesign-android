package org.videolan.vlc.gui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import org.videolan.resources.AppContextProvider
import org.videolan.tools.KEY_SET_LOCALE
import org.videolan.tools.LocaleUtils
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.BuildConfig
import org.videolan.vlc.R

/**
 * VLC Redesign: WelcomeStep.qml: the logo, "Welcome to VLC!", the text; and the interface language, chosen here
 * on Android (the desktop's installer asks it): the onboarding is drawn again in it at once.
 */
class OnboardingWelcomeFragment : OnboardingFragment() {
    private lateinit var titleView: TextView

    override fun getDefaultViewForTalkback() = titleView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.onboarding_welcome, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleView = view.findViewById(R.id.welcome_title)
        val ctx = requireContext()
        val settings = Settings.getInstance(ctx)
        val locales = LocaleUtils.getLocalesUsedInProject(BuildConfig.TRANSLATION_ARRAY, getString(R.string.device_default))
        val current = settings.getString(KEY_SET_LOCALE, "") ?: ""
        val index = locales.localeEntryValues.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 0
        val value = view.findViewById<TextView>(R.id.language_value)
        value.text = locales.localeEntries[index]
        val well = view.findViewById<View>(R.id.language_well)
        well.contentDescription = getString(R.string.rd_interface_language) + ", " + locales.localeEntries[index]
        well.setOnClickListener {
            val popup = ListPopupWindow(ctx)
            popup.anchorView = well
            popup.setAdapter(ArrayAdapter(ctx, R.layout.rd_pref_choice, android.R.id.text1, locales.localeEntries))
            popup.setBackgroundDrawable(ContextCompat.getDrawable(ctx, R.drawable.rd_menu_background))
            popup.isModal = true
            popup.height = (resources.displayMetrics.heightPixels * 0.5).toInt()
            popup.setOnItemClickListener { _, _, position, _ ->
                popup.dismiss()
                val chosen = locales.localeEntryValues[position]
                if (chosen != current) {
                    settings.putSingle(KEY_SET_LOCALE, chosen)
                    AppContextProvider.setLocale(chosen)
                    requireActivity().recreate()
                }
            }
            popup.show()
            popup.listView?.setSelection(index)
        }
    }

    companion object {
        fun newInstance(): OnboardingWelcomeFragment {
            return OnboardingWelcomeFragment()
        }
    }
}
