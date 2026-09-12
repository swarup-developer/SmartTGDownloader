package com.swarupbaral.smarttgdownloader.update

import android.content.Context
import androidx.core.content.FileProvider
import com.swarupbaral.smarttgdownloader.BuildConfig
import com.swarupbaral.smarttgdownloader.data.AppPreferences
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Checks GitHub Releases for a newer Android APK and installs it.
 *
 * Release convention (matches .github/workflows/android-release.yml):
 * tag `v<version>-android` with an `*.apk` asset (unsigned assets are
 * skipped). versionCode is derived from the semver when the tag carries no
 * explicit build number.
 */
class UpdateManager(context: Context, private val preferences: AppPreferences) {

    sealed interface UpdateState {
        data object Idle : UpdateState

        data class Checking(val manual: Boolean) : UpdateState

        data class Available(
            val versionName: String,
            val versionCode: Long,
            val downloadUrl: String,
            val releaseUrl: String?,
            /** Raw release body (markdown) from GitHub; null when absent. */
            val releaseNotes: String?
        ) : UpdateState

        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateState {
            val fraction: Float
                get() = if (totalBytes > 0) {
                    (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                } else 0f
        }

        data class Ready(val apkFile: File, val versionName: String) : UpdateState

        data class UpToDate(val manual: Boolean) : UpdateState

        data class Failed(val manual: Boolean, val message: String) : UpdateState
    }

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersionName: String = BuildConfig.VERSION_NAME
    val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()

    /**
     * Code used for comparisons: derived from the semver with the same rule
     * applied to release tags, so both sides share one scale. (The manifest
     * versionCode is a small sequential number and must NOT be compared
     * against semver-derived codes.)
     */
    private val comparableVersionCode: Long = deriveVersionCode(BuildConfig.VERSION_NAME)

    /** One-shot check; safe to call repeatedly. Set [manual] for user-initiated checks. */
    suspend fun checkForUpdate(manual: Boolean = false) {
        // Respect the user's opt-out for automatic (silent) checks; manual
        // checks from Settings always run.
        if (!manual && !autoCheckEnabled) return
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.Ready) return
        _state.value = UpdateState.Checking(manual)
        try {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            _state.value = when {
                release == null ->
                    if (manual) UpdateState.Failed(manual, "No Android release found yet.")
                    else UpdateState.Idle

                isNewer(release.versionCode, release.versionName) -> {
                    val available = UpdateState.Available(
                        versionName = release.versionName,
                        versionCode = release.versionCode,
                        downloadUrl = release.apkUrl,
                        releaseUrl = release.htmlUrl,
                        releaseNotes = release.body
                    )
                    // Auto-checks stay silent for a version the user already
                    // dismissed; a manual check always re-shows it.
                    if (!manual && isDismissed(available)) UpdateState.Idle else available
                }

                else -> UpdateState.UpToDate(manual)
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(manual, e.message ?: "Update check failed.")
        }
    }

    /** Downloads the APK advertised by [UpdateState.Available]. */
    suspend fun downloadUpdate() {
        val available = _state.value as? UpdateState.Available ?: return
        try {
            val file = withContext(Dispatchers.IO) {
                downloadApk(available.downloadUrl, available.versionName)
            }
            _state.value = UpdateState.Ready(file, available.versionName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(manual = true, e.message ?: "Download failed.")
        }
    }

    /** Launches the system package installer for the downloaded APK. */
    fun install(context: Context) {
        val ready = _state.value as? UpdateState.Ready ?: return
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            ready.apkFile
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Whether silent launch-time checks run at all. Manual checks from
     * Settings ignore this switch. Backed by the shared [AppPreferences].
     */
    var autoCheckEnabled: Boolean
        get() = preferences.autoCheckEnabled.value
        set(value) = preferences.setAutoCheckEnabled(value)

    fun dismiss() {
        val current = _state.value
        if (current is UpdateState.Available) {
            // "Later" on a version: remember it so silent launch checks don't
            // re-prompt until a NEW version is published.
            preferences.recordDismissedUpdate(current.versionName, current.versionCode)
        }
        if (current !is UpdateState.Downloading) {
            _state.value = UpdateState.Idle
        }
    }

    /** True when [candidate] is the version the user previously dismissed. */
    private fun isDismissed(candidate: UpdateState.Available): Boolean {
        val dismissed = preferences.dismissedUpdate() ?: return false
        val (dismissedName, dismissedCode) = dismissed
        if (dismissedCode > 0 && candidate.versionCode > 0) {
            return candidate.versionCode == dismissedCode
        }
        return candidate.versionName == dismissedName
    }

    // ------------------------------------------------------------------
    // GitHub releases API (org.json — bundled with Android, no extra dep)
    // ------------------------------------------------------------------

    private data class ReleaseInfo(
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val htmlUrl: String?,
        val body: String?
    )

    private fun fetchLatestRelease(): ReleaseInfo? {
        val conn = URL("$RELEASES_API?per_page=10").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(body)
            for (i in 0 until releases.length()) {
                val obj = releases.getJSONObject(i)
                if (obj.optBoolean("draft") || obj.optBoolean("prerelease")) continue

                val assets = obj.optJSONArray("assets") ?: continue
                var apkUrl: String? = null
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true) &&
                        !name.contains("unsigned", ignoreCase = true)
                    ) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isNullOrEmpty()) continue

                val tag = obj.optString("tag_name")
                return ReleaseInfo(
                    versionName = tag.removePrefix("v").removeSuffix("-android"),
                    versionCode = parseVersionCode(tag),
                    apkUrl = apkUrl,
                    htmlUrl = obj.optString("html_url"),
                    body = obj.optString("body").takeIf { it.isNotBlank() }
                )
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Strictly-greater comparison. Semver-derived codes are authoritative on
     * both sides; the name comparison only runs for non-semver tags.
     */
    private fun isNewer(latestCode: Long, latestName: String): Boolean {
        if (latestCode > comparableVersionCode) return true
        if (latestCode > 0) return false
        return compareNames(latestName, currentVersionName) > 0
    }

    private fun compareNames(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    private fun parseVersionCode(tag: String): Long =
        // Convention: tags are v<major>.<minor>.<patch>-android (matching the
        // release workflow). Any other scheme falls back to the name compare.
        deriveVersionCode(tag.removePrefix("v").removeSuffix("-android"))

    private fun deriveVersionCode(name: String): Long {
        val parts = name.split('.')
        if (parts.size >= 3) {
            return (parts[0].toIntOrNull() ?: 0) * 1_000_000L +
                (parts[1].toIntOrNull() ?: 0) * 1_000L +
                (parts[2].toIntOrNull() ?: 0)
        }
        return 0
    }

    // ------------------------------------------------------------------
    // APK download
    // ------------------------------------------------------------------

    private fun downloadApk(url: String, versionName: String): File {
        val cacheDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val dest = File(cacheDir, "SmartTG-$versionName.apk")
        if (dest.exists()) dest.delete()

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Download failed (HTTP ${conn.responseCode}).")
            }
            val total = conn.contentLengthLong
            _state.value = UpdateState.Downloading(0, total)

            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastEmit = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        // Throttle UI emissions to every 256 KB.
                        if (downloaded - lastEmit >= 256 * 1024) {
                            _state.value = UpdateState.Downloading(downloaded, total)
                            lastEmit = downloaded
                        }
                    }
                    _state.value = UpdateState.Downloading(downloaded, if (total > 0) total else downloaded)
                }
            }
            return dest
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val RELEASES_API =
            "https://api.github.com/repos/swarup-developer/SmartTGDownloader/releases"
    }
}
