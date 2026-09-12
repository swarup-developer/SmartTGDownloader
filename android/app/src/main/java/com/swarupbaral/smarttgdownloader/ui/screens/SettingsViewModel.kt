package com.swarupbaral.smarttgdownloader.ui.screens

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swarupbaral.smarttgdownloader.AppContainer
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.TelegramRepository
import com.swarupbaral.smarttgdownloader.data.ThemeMode
import com.swarupbaral.smarttgdownloader.permissions.StoragePermissions
import com.swarupbaral.smarttgdownloader.update.UpdateManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    private val repository = AppContainer.repository

    val accountLabel: StateFlow<String> =
        repository.authState
            .map { state ->
                when (state) {
                    is TelegramRepository.AuthState.Ready -> state.userName
                    else -> "unknown account"
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "…")

    /** Update-check state; the global UpdatePrompt renders the result UI. */
    val updateState: StateFlow<UpdateManager.UpdateState> = AppContainer.updateManager.state

    /** Whether silent launch-time update checks are on (user preference). */
    val autoCheckEnabled: StateFlow<Boolean> = AppContainer.appPreferences.autoCheckEnabled

    fun setAutoCheckEnabled(value: Boolean) {
        AppContainer.appPreferences.setAutoCheckEnabled(value)
    }

    val currentVersionName: String
        get() = AppContainer.updateManager.currentVersionName

    fun checkForUpdates() {
        viewModelScope.launch { AppContainer.updateManager.checkForUpdate(manual = true) }
    }

    // --- Appearance & downloads (AppPreferences-backed) ---------------------

    val themeMode: StateFlow<ThemeMode> = AppContainer.appPreferences.themeMode

    fun setThemeMode(value: ThemeMode) {
        AppContainer.appPreferences.setThemeMode(value)
    }

    val dynamicColor: StateFlow<Boolean> = AppContainer.appPreferences.dynamicColor

    /** True on Android 12+ where Material You palettes are available. */
    val dynamicColorSupported: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun setDynamicColor(value: Boolean) {
        AppContainer.appPreferences.setDynamicColor(value)
    }

    val defaultCategory: StateFlow<String> = AppContainer.appPreferences.defaultCategory

    fun setDefaultCategory(value: String) {
        AppContainer.appPreferences.setDefaultCategory(value)
    }

    // --- Storage permission status ------------------------------------------

    /**
     * Mirrors StoragePermissions' live status: refreshed on every screen
     * resume (see SettingsScreen's lifecycle effect), so returning from the
     * system all-files page updates this immediately.
     */
    val storageGranted: StateFlow<Boolean> =
        StoragePermissions.status
            .map { it.allFilesGranted }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun logOut() {
        viewModelScope.launch {
            runCatching { repository.logOut() }
        }
    }
}
