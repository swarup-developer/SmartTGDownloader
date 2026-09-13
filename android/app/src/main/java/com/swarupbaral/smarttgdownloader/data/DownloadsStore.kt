package com.swarupbaral.smarttgdownloader.data

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
 * Layout: `Download/SmartTG Downloader/<channel>/<file>`
 *
 * One folder per channel, named after the channel, inside a folder named after
 * the app. The directory is created by MediaStore the first time a file is
 * written into it, so there is nothing to configure and nothing to clean up for
 * a channel that is never downloaded from. The file type is deliberately NOT a
 * folder here: the channel is what a person looks for, and the extension
 * already says what the file is. (2.2.0 development builds wrote to
 * `Download/SmartTG/<channel>/<type>/`; files already saved there are left
 * exactly where they are — this only changes where new downloads land.)
 *
 * The only exception is Android 8 and 9 (API 26–28), which predate scoped
 * storage and have no permission-free way to write to the shared Download
 * folder. There the legacy WRITE_EXTERNAL_STORAGE grant is used, and if the
 * user declines we fall back to the app's own external files directory rather
 * than failing the download.
 */
object DownloadsStore {

    /** Root folder this app owns inside Download/. */
    const val ROOT = "SmartTG Downloader"

    /** MIME type for a .zip archive; the platform has no constant for it. */
    const val ZIP_MIME = "application/zip"

    /**
     * Authority and root id of the shared-storage provider.
     *
     * Used only to hand a folder to the file manager (see [openFolder]); the
     * provider is part of the platform, so these are stable names rather than
     * something an OEM can rename.
     */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val EXTERNAL_STORAGE_DOWNLOADS_ROOT = "downloads"

    /**
     * The MIME type of a *location* rather than a document.
     *
     * Spelled out rather than taken from `DocumentsContract.Root`: the platform
     * only exposes `MIME_TYPE_ITEM` (`vnd.android.document/root`) in the public
     * SDK, while file managers accept `.../group` for a browsable root. The
     * value is fixed by the provider, so a literal is the accurate choice.
     */
    private const val MIME_TYPE_GROUP = "vnd.android.document/group"

    /**
     * Longest a channel folder name may be, in UTF-8 bytes.
     *
     * Filesystems allow 255; this leaves room for the file names inside it and
     * for the collision suffix MediaStore adds when two names match.
     */
    private const val MAX_SEGMENT_BYTES = 180

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

    /** What happened when the app asked the system to show a folder. */
    enum class FolderOpenOutcome {
        /** A file manager took over and is now on screen. */
        OPENED,

        /**
         * No installed app accepts a folder.
         *
         * A real outcome on a bare device: the caller must say where the files
         * are instead of leaving the user with a button that does nothing.
         */
        NO_APP
    }

    /** True when this API level needs the one-off legacy runtime grant. */
    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasLegacyPermission(context)

    fun hasLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Relative folder for one channel, e.g. `SmartTG Downloader/MyChannel`.
     *
     * Keyed on the channel's title so the folder is recognisable in the Files
     * app, with anything the filesystem rejects replaced (see [sanitize]).
     */
    fun channelFolder(sourceTitle: String): String = "$ROOT/${sanitize(sourceTitle)}"

    /** A folder phrased for a person: `Download/SmartTG Downloader/MyChannel`. */
    fun displayPath(relativeFolder: String = ROOT): String = "Download/$relativeFolder"

    /**
     * The name for an archive of one selection: `MC - Audio (20 files).zip`.
     *
     * The channel and the type are in the name because an archive travels — it
     * gets sent, copied to a computer, opened somewhere else — and "download.zip"
     * in a message thread tells the recipient nothing.
     */
    fun archiveName(sourceTitle: String, category: String, fileCount: Int): String =
        "${sanitize(sourceTitle)} - ${category.ifBlank { AppPreferences.CATEGORY_ALL }} " +
            "($fileCount files).zip"

    /** The channel name out of a folder path, for button labels. */
    fun channelName(relativeFolder: String): String = relativeFolder.substringAfterLast('/')

    /**
     * Shows [relativeFolder] — or the app's own folder when it is null — in the
     * device's file manager.
     *
     * There is no API to open a shared folder: the app composes the same
     * document URI that the platform's storage provider uses, and lets whichever
     * file manager is installed open it. Two forms are tried, best first:
     *
     *  1. The folder itself as a *directory* document. The file manager opens
     *     inside that channel's folder, which is what the user asked for.
     *  2. The Download root as a browsable *group*, for anything that can only
     *     open a location rather than a document.
     *
     * Deliberately no `file://` fallback: handing one to another app on Android
     * 7+ is a FileUriExposedException, so it would crash instead of falling
     * back. When nothing accepts a folder the caller is told, and says where the
     * files are.
     */
    fun openFolder(context: Context, relativeFolder: String? = null): FolderOpenOutcome {
        val folder = relativeFolder?.takeIf { it.isNotBlank() } ?: ROOT
        // startActivity from a non-Activity context needs NEW_TASK; the callers
        // currently pass an Activity, but this must not depend on that.
        val newTask = context !is Activity
        for (intent in folderIntents(folder, newTask)) {
            // Deliberately not gated on resolveActivity: since Android 11 that
            // returns null for packages the app cannot see, which would report
            // "no file manager" on a device that has one. Trying is the test.
            if (runCatching { context.startActivity(intent) }.isSuccess) return FolderOpenOutcome.OPENED
        }
        return FolderOpenOutcome.NO_APP
    }

    private fun folderIntents(folder: String, newTask: Boolean): List<Intent> {
        val documentUri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            "primary:Download/$folder"
        )
        val rootUri = DocumentsContract.buildRootUri(
            EXTERNAL_STORAGE_AUTHORITY,
            EXTERNAL_STORAGE_DOWNLOADS_ROOT
        )
        return listOf(
            viewIntent(documentUri, DocumentsContract.Document.MIME_TYPE_DIR, newTask),
            viewIntent(rootUri, MIME_TYPE_GROUP, newTask)
        )
    }

    private fun viewIntent(uri: Uri, mimeType: String, newTask: Boolean): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Copies [source] (TDLib's already-downloaded cache copy) into the public
     * Download folder, under this channel's own folder. Never overwrites a
     * different file: MediaStore and the legacy path both resolve name
     * collisions to a fresh name.
     */
    suspend fun save(
        context: Context,
        source: File,
        sourceTitle: String,
        category: String,
        fileName: String,
        mimeType: String = mimeFor(fileName, category)
    ): SavedFile = withContext(Dispatchers.IO) {
        val folder = channelFolder(sourceTitle)
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

    /**
     * Writes an archive into a channel's folder, straight from [write].
     *
     * The bytes go into their final home as they are produced rather than being
     * assembled in a temporary file and copied in: an archive of a large
     * selection is as large as the selection itself, and building it twice would
     * need that much space twice over.
     */
    suspend fun saveArchive(
        context: Context,
        folder: String,
        fileName: String,
        write: (OutputStream) -> Unit
    ): SavedFile = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveArchiveViaMediaStore(context, folder, fileName, write)
        } else {
            saveArchiveLegacy(context, folder, fileName, write)
        }
    }

    private fun saveArchiveViaMediaStore(
        context: Context,
        folder: String,
        fileName: String,
        write: (OutputStream) -> Unit
    ): SavedFile {
        val resolver = context.contentResolver
        // An attempt that was killed leaves an invisible pending row holding
        // its bytes. Discard it before writing a fresh archive, or the space is
        // never reclaimed.
        deleteStalePendingArchive(context, folder, fileName)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, ZIP_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the ZIP in Downloads.")
        try {
            resolver.openOutputStream(uri)?.use { output -> write(output) }
                ?: throw IOException("Could not open the ZIP for writing.")
        } catch (t: Throwable) {
            // No half-written archive left behind for the user to find.
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return SavedFile(
            location = uri.toString(),
            displayPath = "Download/$folder/$fileName",
            bytes = sizeOf(context, uri)
        )
    }

    private fun saveArchiveLegacy(
        context: Context,
        folder: String,
        fileName: String,
        write: (OutputStream) -> Unit
    ): SavedFile {
        val dir = File(
            if (hasLegacyPermission(context)) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } else {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            },
            folder
        )
        dir.mkdirs()
        // Written beside the final name and renamed: an interrupted attempt
        // leaves a .partial file, never something that looks like a finished
        // archive.
        val partial = File(dir, "$fileName.partial")
        partial.delete()
        partial.outputStream().use { output -> write(output) }
        val target = uniqueTarget(dir, fileName)
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return SavedFile(target.absolutePath, target.absolutePath, target.length())
    }

    private fun deleteStalePendingArchive(context: Context, folder: String, fileName: String) {
        runCatching {
            context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING} = 1",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$folder/", fileName)
            )
        }
    }

    private fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver
            .query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            ?: 0L
    }.getOrDefault(0L)

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

    /**
     * Turns a channel's title into a folder name the filesystem will accept.
     *
     * Beyond stripping the separators and reserved characters, two input shapes
     * need handling because Telegram allows them and a filesystem does not:
     *
     *  - **Nothing but dots.** `.` and `..` are path references, not names, so a
     *    channel called `..` would otherwise write *outside* the app's own
     *    folder, into Download/ itself.
     *  - **An over-long name.** A single path component is limited to 255 bytes
     *    (and a Devanagari or emoji character costs several bytes each), so a
     *    long channel title would fail to create its folder — and every download
     *    in that channel would fail with a puzzling error.
     */
    private fun sanitize(value: String): String {
        val cleaned = value.replace(Regex("[<>:\"/\\\\|?*\\u0000]"), "_").trim()
        if (cleaned.isBlank() || cleaned.all { it == '.' }) return "Telegram"
        return capToBytes(cleaned, MAX_SEGMENT_BYTES).trim().ifBlank { "Telegram" }
    }

    /**
     * Shortens [value] to at most [maxBytes] UTF-8 bytes, whole characters only.
     *
     * Byte-counted rather than character-counted because that is what the
     * filesystem enforces; code-point aware so an emoji or accented character is
     * never cut in half into a broken name.
     */
    private fun capToBytes(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val builder = StringBuilder()
        var used = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val character = String(Character.toChars(codePoint))
            val size = character.toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            builder.append(character)
            used += size
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }
}
