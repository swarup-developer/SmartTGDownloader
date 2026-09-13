package com.swarupbaral.smarttgdownloader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the app picks its light/dark color scheme. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Which connections downloads are allowed to use.
 *
 * [WIFI_ONLY] is enforced against the platform's "not metered" capability
 * rather than a literal Wi-Fi transport check, so ethernet and unmetered
 * hotspots count as allowed while a metered hotspot does not — the question
 * the user actually cares about is whether the transfer costs them data.
 */
/**
 * The last range the user applied in the download panel's range picker.
 *
 * [to] of 0 means "to the end of the list", which is what an untouched picker
 * means and is not the same as a remembered number.
 */
data class SavedRange(val from: Int = 1, val to: Int = 0) {

    /**
     * This range clamped onto a result list of [totalFiles] entries, so the
     * range picker can pre-fill values that are guaranteed valid for the list
     * actually on screen.
     *
     * A remembered range outlives the list it was chosen from, so carrying it
     * over unclamped would pre-fill something the picker then rejects on the
     * first tap — the range is clamped rather than dropped, because the user's
     * intent ("the newest few") usually still applies to the new list.
     *
     * [to] of 0 means "to the end of the list", which is not the same as a
     * remembered number and therefore follows the list rather than being
     * clamped from 0.
     *
     * The clamp is asymmetric on purpose: [from] is fitted first, then [to] is
     * fitted to `from..total`, so the returned pair is always ordered and in
     * range even for input like (100, 50) or a negative total.
     */
    fun seededFor(totalFiles: Int): SavedRange {
        val ceiling = totalFiles.coerceAtLeast(1)
        val first = from.coerceIn(1, ceiling)
        val last = (if (to <= 0) ceiling else to).coerceIn(first, ceiling)
        return SavedRange(from = first, to = last)
    }
}

enum class DownloadNetwork(val label: String, val detail: String) {
    WIFI_AND_MOBILE("Wi-Fi and mobile data", "Download on any connection"),
    WIFI_ONLY("Wi-Fi only", "Never spend mobile data")
}

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

    // --- Login -----------------------------------------------------------------

    private val _lastPhoneNumber =
        MutableStateFlow(prefs.getString(KEY_LAST_PHONE, null).orEmpty())

    /**
     * Last phone number the user submitted. Pre-fills the login form so a
     * restart mid-login does not leave the code step without a number.
     */
    val lastPhoneNumber: StateFlow<String> = _lastPhoneNumber.asStateFlow()

    fun setLastPhoneNumber(value: String) {
        prefs.edit().putString(KEY_LAST_PHONE, value).apply()
        _lastPhoneNumber.value = value
    }

    // --- Downloads -------------------------------------------------------------

    private val _downloadNetwork = MutableStateFlow(
        prefs.getString(KEY_DOWNLOAD_NETWORK, null)
            ?.let { stored -> runCatching { DownloadNetwork.valueOf(stored) }.getOrNull() }
            // Permissive by default: a download that silently refuses to start
            // on mobile data is far more confusing than one that runs.
            ?: DownloadNetwork.WIFI_AND_MOBILE
    )

    /** Which networks downloads may use. Checked before every download. */
    val downloadNetwork: StateFlow<DownloadNetwork> = _downloadNetwork.asStateFlow()

    fun setDownloadNetwork(value: DownloadNetwork) {
        prefs.edit().putString(KEY_DOWNLOAD_NETWORK, value.name).apply()
        _downloadNetwork.value = value
    }

    // --- Scan limit -----------------------------------------------------------

    private val _scanLimit = MutableStateFlow(prefs.getInt(KEY_SCAN_LIMIT, DEFAULT_SCAN_LIMIT))

    /**
     * How many matching files one analysis will collect before it stops; 0
     * means "walk the whole channel".
     *
     * A channel can hold tens of thousands of files, and walking it is O(history
     * length) in TDLib round trips. This bounds both the wait and the memory.
     * Collection runs newest-first, so a limit keeps the most recent files.
     */
    val scanLimit: StateFlow<Int> = _scanLimit.asStateFlow()

    fun setScanLimit(value: Int) {
        prefs.edit().putInt(KEY_SCAN_LIMIT, value).apply()
        _scanLimit.value = value
    }

    private val _lastRange = MutableStateFlow(
        SavedRange(
            from = prefs.getInt(KEY_RANGE_FROM, 1).coerceAtLeast(1),
            to = prefs.getInt(KEY_RANGE_TO, 0).coerceAtLeast(0)
        )
    )

    /**
     * The range the user last applied, so a repeated "newest 20" is one tap
     * instead of retyping two numbers on every analysis.
     */
    val lastRange: StateFlow<SavedRange> = _lastRange.asStateFlow()

    fun setLastRange(value: SavedRange) {
        prefs.edit()
            .putInt(KEY_RANGE_FROM, value.from)
            .putInt(KEY_RANGE_TO, value.to)
            .apply()
        _lastRange.value = value
    }

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

    private val _askBeforeAnalyzing =
        MutableStateFlow(prefs.getBoolean(KEY_ASK_BEFORE_ANALYZING, true))

    /**
     * Whether pressing Analyze asks which file type to look for.
     *
     * On by default: the scan collects one type, so which one is a decision the
     * user has to make before it starts. Turning it off is the "don't ask again"
     * answer to that question, and the type it then uses is [defaultCategory] —
     * the same value Settings shows, so the remembered answer and the setting
     * cannot drift apart. It is a preference rather than a one-way switch: the
     * question can always be asked again, from the dialog itself or from
     * Settings.
     */
    val askBeforeAnalyzing: StateFlow<Boolean> = _askBeforeAnalyzing.asStateFlow()

    fun setAskBeforeAnalyzing(value: Boolean) {
        prefs.edit().putBoolean(KEY_ASK_BEFORE_ANALYZING, value).apply()
        _askBeforeAnalyzing.value = value
    }

    private val _zipKeepsFiles = MutableStateFlow(prefs.getBoolean(KEY_ZIP_KEEPS_FILES, false))

    /**
     * Whether "Download ZIP" also keeps the separate files it packs.
     *
     * Off by default: the archive is what the action is for, and keeping both
     * costs twice the space. The choice sits beside the button rather than in
     * Settings because it belongs to that action, and it is remembered so it is
     * decided once rather than every batch.
     */
    val zipKeepsFiles: StateFlow<Boolean> = _zipKeepsFiles.asStateFlow()

    fun setZipKeepsFiles(value: Boolean) {
        prefs.edit().putBoolean(KEY_ZIP_KEEPS_FILES, value).apply()
        _zipKeepsFiles.value = value
    }

    // --- One-time migrations ---------------------------------------------------

    /**
     * True once files left in the pre-2.2.0-final `Download/SmartTG/` tree have
     * been moved into the per-channel layout.
     *
     * Only a pass that finished sets this, so a partial migration is retried on
     * the next launch rather than being forgotten and leaving files stranded in
     * a folder the app no longer writes to.
     */
    fun legacyMigrationDone(): Boolean = prefs.getBoolean(KEY_LEGACY_MIGRATION_DONE, false)

    fun setLegacyMigrationDone(value: Boolean) {
        prefs.edit().putBoolean(KEY_LEGACY_MIGRATION_DONE, value).apply()
    }

    companion object {
        private const val PREFS_FILE = "smarttg_prefs"
        private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
        private const val KEY_DISMISSED_VERSION = "dismissed_version"
        private const val KEY_DISMISSED_CODE = "dismissed_code"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DEFAULT_CATEGORY = "default_category"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_LAST_PHONE = "last_phone_number"
        private const val KEY_DOWNLOAD_NETWORK = "download_network"
        private const val KEY_SCAN_LIMIT = "scan_limit"
        private const val KEY_RANGE_FROM = "range_from"
        private const val KEY_RANGE_TO = "range_to"
        private const val KEY_LEGACY_MIGRATION_DONE = "legacy_migration_done"
        private const val KEY_ZIP_KEEPS_FILES = "zip_keeps_files"
        private const val KEY_ASK_BEFORE_ANALYZING = "ask_before_analyzing"

        private const val DEFAULT_DYNAMIC_COLOR = false

        /** Value of [defaultCategory] meaning "pre-select everything". */
        const val CATEGORY_ALL = "All"

        /**
         * The one and only set of filter options in the app.
         *
         * Settings renders these as the pre-selection filter, and every other
         * filter surface (Home, History) must show exactly this list — the
         * product rule is that no screen invents extra filter values.
         */
        val DOWNLOAD_CATEGORIES = listOf(CATEGORY_ALL, "Audio", "Video", "Images", "Docs")

        /** The concrete media categories, i.e. [DOWNLOAD_CATEGORIES] minus "All". */
        val MEDIA_CATEGORIES = DOWNLOAD_CATEGORIES.filter { it != CATEGORY_ALL }

        /** Selectable scan limits; 0 is rendered as "All" (no limit). */
        val SCAN_LIMIT_OPTIONS = listOf(100, 500, 1_000, 5_000, 0)

        /**
         * One-tap presets for the range picker, in the order shown. Results are
         * newest-first, so these all mean "the N most recent files".
         */
        val RANGE_PRESETS = listOf(10, 20, 50, 100)

        const val DEFAULT_SCAN_LIMIT = 500

        /** "500" / "5,000" / "All" for the settings chips. */
        fun scanLimitLabel(value: Int): String =
            if (value <= 0) "All" else "%,d".format(value)

        /**
         * The next scan-limit step above [current], or null when there is
         * nothing left to raise it to (already unlimited).
         *
         * Used by the "search deeper" action offered when a scan was cut short.
         */
        fun nextScanLimit(current: Int): Int? {
            if (current <= 0) return null
            return SCAN_LIMIT_OPTIONS.filter { it > 0 }.firstOrNull { it > current } ?: 0
        }
    }
}

/**
 * The type a scan uses when the analyze question is skipped.
 *
 * A remembered value this build does not recognise falls back to
 * [AppPreferences.CATEGORY_ALL] instead of being handed to the scan. That case
 * is not hypothetical: the preference outlives the build that wrote it, so a
 * type removed or renamed by a later version would arrive here as a value
 * nothing can match. The scan would then collect nothing and report an empty
 * channel — the most misleading possible answer, since it blames the channel for
 * what is really a stale setting. "All" is also the right fallback on its own
 * terms: it means every file, so nothing is silently excluded.
 */
fun rememberedAnalyzeCategory(saved: String?): String =
    saved?.trim()?.takeIf { it in AppPreferences.DOWNLOAD_CATEGORIES }
        ?: AppPreferences.CATEGORY_ALL
