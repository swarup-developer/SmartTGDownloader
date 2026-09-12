package com.swarupbaral.smarttgdownloader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the app picks its light/dark color scheme. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Small typed facade over SharedPreferences, shared by all app settings.
 *
 * UI binds to the exposed StateFlows; services read the same values through
 * the flow or the synchronous accessors. Add new settings here rather than
 * creating more SharedPreferences files.
 */
class AppPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // --- Updates -------------------------------------------------------------

    private val _autoCheckEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, true))

    /** Whether silent launch-time update checks run; manual checks ignore it. */
    val autoCheckEnabled: StateFlow<Boolean> = _autoCheckEnabled.asStateFlow()

    fun setAutoCheckEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK_ENABLED, value).apply()
        _autoCheckEnabled.value = value
    }

    /** Remembers an update version the user dismissed ("Later"). */
    fun recordDismissedUpdate(versionName: String, versionCode: Long) {
        prefs.edit()
            .putString(KEY_DISMISSED_VERSION, versionName)
            .putLong(KEY_DISMISSED_CODE, versionCode)
            .apply()
    }

    /** The last dismissed update as (versionName, versionCode), or null. */
    fun dismissedUpdate(): Pair<String, Long>? {
        val name = prefs.getString(KEY_DISMISSED_VERSION, null) ?: return null
        return name to prefs.getLong(KEY_DISMISSED_CODE, 0L)
    }

    // --- Appearance ----------------------------------------------------------

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME_MODE, null)
            ?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    )

    /** Light/dark/system color scheme selection. */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(value: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        _themeMode.value = value
    }

    private val _dynamicColor =
        MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC_COLOR, DEFAULT_DYNAMIC_COLOR))

    /**
     * Derive the palette from the user's wallpaper (Material You). Only has
     * an effect on Android 12+; older devices keep the static brand palette.
     */
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    fun setDynamicColor(value: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        _dynamicColor.value = value
    }

    // --- Downloads -------------------------------------------------------------

    private val _defaultCategory =
        MutableStateFlow(prefs.getString(KEY_DEFAULT_CATEGORY, null) ?: CATEGORY_ALL)

    /**
     * Category pre-selected in the results sheet after a scan ("All" selects
     * every matching file).
     */
    val defaultCategory: StateFlow<String> = _defaultCategory.asStateFlow()

    fun setDefaultCategory(value: String) {
        prefs.edit().putString(KEY_DEFAULT_CATEGORY, value).apply()
        _defaultCategory.value = value
    }

    companion object {
        private const val PREFS_FILE = "smarttg_prefs"
        private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
        private const val KEY_DISMISSED_CODE = "dismissed_code"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DEFAULT_CATEGORY = "default_category"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"

        private const val DEFAULT_DYNAMIC_COLOR = false

        /** Value of [defaultCategory] meaning "pre-select everything". */
        const val CATEGORY_ALL = "All"

        /** Categories offered by the scan/selection pipeline. */
        val DOWNLOAD_CATEGORIES = listOf(CATEGORY_ALL, "Audio", "Video", "Images", "Docs")
    }
}
