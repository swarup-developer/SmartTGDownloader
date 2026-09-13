package com.swarupbaral.smarttgdownloader.ui.screens

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swarupbaral.smarttgdownloader.AppContainer
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import com.swarupbaral.smarttgdownloader.data.ChannelSettings
import com.swarupbaral.smarttgdownloader.data.DownloadNetwork
import com.swarupbaral.smarttgdownloader.data.TelegramRepository
import com.swarupbaral.smarttgdownloader.data.ThemeMode
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

    /**
     * The app's filter options. Shared with the Home and History screens, so
     * every filter control in the app edits one value, not three.
     */
    val defaultCategory: StateFlow<String> = AppContainer.appPreferences.defaultCategory

    fun setDefaultCategory(value: String) {
        AppContainer.appPreferences.setDefaultCategory(value)
    }

    // --- Scan limit ---------------------------------------------------------

    /**
     * Files one analysis collects before stopping; 0 means the whole channel.
     * Serves as the default for channels with nothing remembered.
     */
    val scanLimit: StateFlow<Int> = AppContainer.appPreferences.scanLimit

    fun setScanLimit(value: Int) {
        AppContainer.appPreferences.setScanLimit(value)
    }

    // --- Per-channel preferences --------------------------------------------

    /** Every channel with remembered preferences, most recently used first. */
    val channels: StateFlow<List<ChannelSettings>> = AppContainer.channelPreferences.channels

    fun forgetChannel(chatId: Long) {
        AppContainer.channelPreferences.forget(chatId)
    }

    fun forgetAllChannels() {
        AppContainer.channelPreferences.forgetAll()
    }

    // --- Download connection ------------------------------------------------

    /** Which networks downloads may use; enforced before every download. */
    val downloadNetwork: StateFlow<DownloadNetwork> = AppContainer.appPreferences.downloadNetwork

    fun setDownloadNetwork(value: DownloadNetwork) {
        AppContainer.appPreferences.setDownloadNetwork(value)
    }

    fun logOut() {
        viewModelScope.launch {
            runCatching { repository.logOut() }
        }
    }
}
