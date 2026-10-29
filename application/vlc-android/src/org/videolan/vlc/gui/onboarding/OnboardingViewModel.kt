package org.videolan.vlc.gui.onboarding

import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModel
import org.videolan.resources.AndroidDevices

class OnboardingViewModel : ViewModel() {
    var permissionAlreadyAsked: Boolean = false
    var notificationPermissionAlreadyAsked: Boolean = false
    var scanStorages = true
    // VLC Redesign: the media folder was chosen or removed on its step (else a debug build starts with none)
    var folderChosen = false
    // opened again from Settings: the library stays as it is unless the folder step changes it
    var rerun = false
    var rerunFolders: List<String> = emptyList()
    // the steps shown, for Back
    val history = ArrayList<FragmentName>()
    var permissionType: PermissionType = PermissionType.ALL

    var theme = if (AndroidDevices.canUseSystemNightMode()) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM else AppCompatDelegate.MODE_NIGHT_AUTO
    var currentFragment = FragmentName.WELCOME
}

enum class PermissionType {
    NONE, MEDIA, ALL
}
