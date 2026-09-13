package com.swarupbaral.smarttgdownloader.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes finished downloads into the user's public **Download** folder.
 *
 * Why this exists: the app used to demand MANAGE_EXTERNAL_STORAGE and gate the
 * whole UI behind an all-files-access settings page. That is unnecessary —
 * MediaStore.Downloads lets an app create its own files under `Download/`
 * with **no storage permission at all** on Android 10 (API 29) and newer,
 * which is every supported device in practice.
 *
 * Layout: `Download/SmartTG/<channel>/<category>/<file>`
 *
 * The only exception is Android 8 and 9 (API 26–28), which predate scoped
 * storage and have no permission-free way to write to the shared Download
 * folder. There the legacy WRITE_EXTERNAL_STORAGE grant is used, and if the
 * user declines we fall back to the app's own external files directory rather
 * than failing the download.
 */
object DownloadsStore {

    /** Root folder this app owns inside Download/. */
    const val ROOT = "SmartTG"

    /**
     * A published file.
     *
     * [location] is what gets persisted in download history and handed to
     * `ACTION_VIEW`: a MediaStore `content://` URI on API 29+, an absolute
     * path on API 26–28.
     */
    data class SavedFile(
        val location: String,
        /** Human-readable place the file landed, for announcements and UI. */
        val displayPath: String,
        val bytes: Long
    )

    /** True when this API level needs the one-off legacy runtime grant. */
    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasLegacyPermission(context)

    fun hasLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    /** Relative folder inside Download/, e.g. `SmartTG/MyChannel/Video`. */
    fun folderFor(sourceTitle: String, category: String): String =
        "$ROOT/${sanitize(sourceTitle)}/${sanitize(category)}"

    /**
     * Copies [source] (TDLib's already-downloaded cache copy) into the public
     * Download folder. Never overwrites a different file: MediaStore and the
     * legacy path both resolve name collisions to a fresh name.
     */
    suspend fun save(
        context: Context,
        source: File,
        sourceTitle: String,
        category: String,
        fileName: String,
        mimeType: String = mimeFor(fileName, category)
    ): SavedFile = withContext(Dispatchers.IO) {
        val folder = folderFor(sourceTitle, category)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, source, folder, fileName, mimeType)
        } else {
            saveLegacy(context, source, folder, fileName)
        }
    }

    /** API 29+: MediaStore owns the file; no permission required. */
    private fun saveViaMediaStore(
        context: Context,
        source: File,
        folder: String,
        fileName: String,
        mimeType: String
    ): SavedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
            // Keep the row invisible until the bytes are fully written.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the file in Downloads.")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Could not open the Downloads file for writing.")
        } catch (t: Throwable) {
            // Roll the half-written row back so a failed attempt leaves no
            // broken file behind for the user to trip over.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return SavedFile(
            location = uri.toString(),
            displayPath = "Download/$folder/$fileName",
            bytes = source.length()
        )
    }

    /** API 26–28: the shared Download tree only, with the legacy grant. */
    private fun saveLegacy(
        context: Context,
        source: File,
        folder: String,
        fileName: String
    ): SavedFile {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            folder
        )
        if (hasLegacyPermission(context)) {
            val written = runCatching {
                publicDir.mkdirs()
                val target = uniqueTarget(publicDir, fileName)
                source.copyTo(target, overwrite = true)
                target
            }.getOrNull()
            if (written != null) {
                return SavedFile(written.absolutePath, written.absolutePath, written.length())
            }
        }

        // Declined or unwritable: keep the file rather than losing it, in the
        // app's own external directory. Still openable, just not shared.
        val fallbackDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            folder
        )
        fallbackDir.mkdirs()
        val target = uniqueTarget(fallbackDir, fileName)
        source.copyTo(target, overwrite = true)
        return SavedFile(target.absolutePath, target.absolutePath, target.length())
    }

    /** Appends ` (n)` until the name is free, mirroring MediaStore's behavior. */
    private fun uniqueTarget(dir: File, fileName: String): File {
        val first = File(dir, fileName)
        if (!first.exists()) return first
        val stem = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = File(
                dir,
                if (extension.isEmpty()) "$stem ($index)" else "$stem ($index).$extension"
            )
            if (!candidate.exists()) return candidate
            index++
        }
    }

    /** Best-effort MIME type from the file extension, then the category. */
    fun mimeFor(fileName: String, category: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
        }
        return when (category) {
            "Audio" -> "audio/*"
            "Video" -> "video/*"
            "Images" -> "image/*"
            else -> "application/octet-stream"
        }
    }

    /** True when a previously saved file can still be opened. */
    fun exists(context: Context, location: String): Boolean = runCatching {
        if (location.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(location))?.use { return true }
            false
        } else {
            val file = File(location)
            file.exists() && file.length() > 0
        }
    }.getOrDefault(false)

    /** Strips path separators and other characters the filesystem rejects. */
    private fun sanitize(value: String): String =
        value.replace(Regex("[<>:\"/\\\\|?*\\u0000]"), "_").trim().ifBlank { "Telegram" }
}
