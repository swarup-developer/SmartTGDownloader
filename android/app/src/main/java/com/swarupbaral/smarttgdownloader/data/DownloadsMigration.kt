package com.swarupbaral.smarttgdownloader.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.swarupbaral.smarttgdownloader.AppContainer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The app's folder name inside Download/ from before 2.2.0 was final:
 * `Download/SmartTG/<channel>/<type>/<file>`.
 *
 * Files written by an earlier development build are still there on a device
 * that installed one, so they are moved rather than abandoned.
 */
const val LEGACY_DOWNLOAD_ROOT = "SmartTG"

/**
 * The public Downloads folder's name inside shared storage.
 *
 * Spelled out rather than read from `Environment.DIRECTORY_DOWNLOADS`: the stub
 * Android jar that JVM unit tests run against carries no value for that
 * constant, so every legacy path would look like it was not in Download/ at all
 * — and this rule is precisely the part worth testing without a device. The
 * device-side code still uses the platform constant.
 */
private const val DOWNLOADS_DIRECTORY = "Download"

/**
 * The folder a file in the old tree should be moved into, or null when it is not
 * ours to move.
 *
 * This is a function rather than a `startsWith` because the new root's name
 * begins with the old one: `Download/SmartTG Downloader/...` matches the old
 * prefix `Download/SmartTG/` and would otherwise be swept up and moved onto
 * itself. The rules, in order:
 *
 *  - anything under `Download/<old root>/` that is not under the *new* root is
 *    legacy;
 *  - the first segment below the old root is the channel, because the old layout
 *    always wrote `<channel>/<type>/`;
 *  - a file sitting directly in the old root is left alone. The app never wrote
 *    one, so moving it would be guessing about somebody else's file.
 */
fun migrationTargetFolder(relativePath: String): String? {
    val segments = relativePath.trim().split('/').filter { it.isNotBlank() }
    // <Download>/<old root>/<channel>/...
    if (segments.size < 3) return null
    if (!segments[0].equals(DOWNLOADS_DIRECTORY, ignoreCase = true)) return null
    if (segments[1] != LEGACY_DOWNLOAD_ROOT) return null
    // The new tree is already where the files belong.
    if (segments[2] == DownloadsStore.ROOT) return null
    return DownloadsStore.channelFolder(segments[2])
}

/** What one migration pass did, or why it could not run. */
data class MigrationReport(
    val moved: Int = 0,
    /** Moved, but under a new name because the target folder already had one. */
    val renamed: Int = 0,
    val failed: Int = 0,
    /** Left alone because the old layout did not put it there. */
    val skipped: Int = 0,
    /** True when the pass could not even start, e.g. no storage grant yet. */
    val blocked: Boolean = false
) {
    /**
     * Whether this pass finished the job, i.e. it is safe to never retry.
     *
     * A pass that failed partway is deliberately NOT complete: the flag stays
     * unset so the next launch picks up whatever is left, rather than leaving
     * files stranded in a folder the app no longer writes to.
     */
    val complete: Boolean get() = failed == 0 && !blocked

    /**
     * One sentence for the user, or null when there is nothing worth saying.
     *
     * Silent on a clean no-op, because a first launch with nothing to move
     * should not produce an announcement. It does speak up when files could not
     * be moved: that is exactly the case where the user would otherwise find a
     * half-empty folder and no explanation.
     */
    fun summary(): String? {
        if (moved == 0 && failed == 0) return null
        return buildString {
            if (moved > 0) {
                append("Moved $moved file(s) into ${DownloadsStore.displayPath()}, ")
                append("one folder per channel.")
                if (renamed > 0) {
                    append(" $renamed had to be given a new name, because a file ")
                    append("of that name was already in the folder.")
                }
            }
            if (failed > 0) {
                if (moved > 0) append(" ")
                append("$failed file(s) could not be moved and are still in ")
                append("${DownloadsStore.displayPath(LEGACY_DOWNLOAD_ROOT)}.")
            }
        }
    }
}

/**
 * Moves files left in the pre-2.2.0-final Download tree into the per-channel
 * layout, once.
 *
 * Runs on its own at launch rather than behind a prompt: a prompt would have to
 * *not* be answered to leave files in a folder the app no longer writes to,
 * which is a worse default than tidying them up.
 *
 * Two things it is careful about, in this order:
 *
 *  - **Never lose a file.** Each file is copied, the copy is verified to exist,
 *    and only then is the original removed. If the copy cannot be written the
 *    original is untouched; if the original cannot be removed the copy is rolled
 *    back. The only possible failure is "still where it was".
 *  - **Never duplicate one either.** A rollback on a failed removal is what
 *    stops a half-move leaving two copies behind.
 */
object DownloadsMigration {

    private val _lastReport = MutableStateFlow<MigrationReport?>(null)

    /**
     * The most recent pass worth announcing.
     *
     * A StateFlow so a screen that composes *after* the migration still sees it:
     * Home may not exist yet when the pass runs.
     */
    val lastReport: StateFlow<MigrationReport?> = _lastReport.asStateFlow()

    /** Guards against a second pass when the activity is recreated. */
    private var running = false

    /** Runs the move once, if it is needed and possible. */
    suspend fun runIfNeeded(context: Context) {
        if (running) return
        if (AppContainer.appPreferences.legacyMigrationDone()) return
        running = true
        try {
            val report = run(context.applicationContext)
            if (report.complete) AppContainer.appPreferences.setLegacyMigrationDone(true)
            if (report.summary() != null) _lastReport.value = report
        } finally {
            running = false
        }
    }

    suspend fun run(context: Context): MigrationReport = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                migrateMediaStoreRows(context)
            } else {
                migrateLegacyFiles(context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A tidy-up must never take the app down with it. Nothing was lost
            // (see the order of operations in moveRow), and the flag stays unset
            // so the next launch tries again.
            MigrationReport(failed = 1)
        }
    }

    /**
     * Android 10+: every file this app wrote is a MediaStore row it owns, so the
     * move needs no storage permission — the same reason downloads need none.
     */
    private suspend fun migrateMediaStoreRows(context: Context): MigrationReport {
        val rows = legacyRows(context)
        if (rows.isEmpty()) return MigrationReport()

        var moved = 0
        var renamed = 0
        var failed = 0
        var skipped = 0

        for (row in rows) {
            val folder = migrationTargetFolder(row.relativePath) ?: run {
                skipped++
                continue
            }
            if (row.name.isBlank()) {
                skipped++
                continue
            }
            val oldUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row.id)
            val result = moveRow(context, oldUri, folder, row.name, row.mimeType)
            if (!result.moved) {
                failed++
                continue
            }
            moved++
            if (result.renamed) renamed++
            // Point the history row at the file's new home, so Open keeps
            // working and the row does not offer a re-download for a file that
            // is already on disk. A database hiccup must not abort the rest of
            // the move: the file itself is already in the right place.
            runCatching {
                AppContainer.historyDao.updateLocalPath(
                    oldUri.toString(),
                    result.newUri.toString()
                )
            }
        }

        if (moved > 0) removeEmptyLegacyFolders(context)
        return MigrationReport(moved = moved, renamed = renamed, failed = failed, skipped = skipped)
    }

    private data class LegacyRow(
        val id: Long,
        val name: String,
        val mimeType: String,
        val relativePath: String
    )

    private fun legacyRows(context: Context): List<LegacyRow> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        // Two LIKE clauses, not one: the new root's name starts with the old
        // one, so a single prefix match would sweep up freshly downloaded files.
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" +
                " AND ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?" +
                " AND ${MediaStore.MediaColumns.MIME_TYPE} != ?"
        val args = arrayOf(
            "${Environment.DIRECTORY_DOWNLOADS}/$LEGACY_DOWNLOAD_ROOT/%",
            "${Environment.DIRECTORY_DOWNLOADS}/${DownloadsStore.ROOT}/%",
            // Directory rows carry no file, and their paths are the folder names
            // that decide where the files go.
            DocumentsContract.Document.MIME_TYPE_DIR
        )
        val rows = mutableListOf<LegacyRow>()
        context.contentResolver
            .query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null)
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream"
                    // A folder is not content to copy. The selection excludes
                    // these, and this is the second line of defence: mistaking a
                    // directory row for a file would report a move failure for
                    // every folder in the tree, on every launch, forever.
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    rows += LegacyRow(
                        id = cursor.getLong(idColumn),
                        name = cursor.getString(nameColumn).orEmpty(),
                        mimeType = mimeType,
                        relativePath = cursor.getString(pathColumn).orEmpty()
                    )
                }
            }
        return rows
    }

    private data class MoveResult(val moved: Boolean, val renamed: Boolean, val newUri: Uri? = null)

    /**
     * Copies one row into its channel folder, then removes the original.
     *
     * Copy-then-delete rather than a metadata-only `RELATIVE_PATH` update: it
     * behaves the same on every supported release, and each step can be checked.
     * The copy is marked pending until it is complete, so a half-written file is
     * never visible in the Files app.
     */
    private fun moveRow(
        context: Context,
        oldUri: Uri,
        folder: String,
        fileName: String,
        mimeType: String
    ): MoveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$folder"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val newUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return MoveResult(false, false)

        val copied = runCatching {
            resolver.openOutputStream(newUri)?.use { output ->
                resolver.openInputStream(oldUri)?.use { input -> input.copyTo(output) }
                    ?: throw IOException("The original file could not be read.")
            } ?: throw IOException("The new file could not be opened for writing.")
        }.isSuccess

        if (!copied) {
            runCatching { resolver.delete(newUri, null, null) }
            return MoveResult(false, false)
        }

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(newUri, values, null, null)

        // What name did MediaStore actually use? A name already present in the
        // folder is given a new one instead of overwriting, and the user should
        // be told rather than left wondering about the "(1)".
        val actualName = runCatching { displayNameOf(context, newUri) }.getOrNull()
        val renamed = actualName != null && actualName != fileName

        // A delete can also *throw* — for a row this app does not own, MediaStore
        // raises a security exception rather than returning zero — so it is
        // caught here rather than allowed to abort the pass with the copy still
        // in place. Both outcomes mean the same thing: undo the copy.
        val removed = runCatching { resolver.delete(oldUri, null, null) }.getOrDefault(0)
        if (removed <= 0) {
            // The original is still there. Keeping the copy would mean two of the
            // same file, so undo the copy and report it.
            runCatching { resolver.delete(newUri, null, null) }
            return MoveResult(false, false)
        }
        return MoveResult(true, renamed, newUri)
    }

    private fun displayNameOf(context: Context, uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /**
     * Best-effort removal of the now-empty legacy folders.
     *
     * MediaStore keeps a directory row after its last file goes, so without this
     * the user is left with an empty `Download/SmartTG` — the very folder they
     * were told the app no longer uses. Failures are ignored: a stale empty
     * folder is a cosmetic problem, not something to report as a failed move.
     */
    private fun removeEmptyLegacyFolders(context: Context) {
        runCatching {
            val folderUri = "${Environment.DIRECTORY_DOWNLOADS}/$LEGACY_DOWNLOAD_ROOT/"
            val rows = mutableListOf<Pair<Long, String>>()
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.MediaColumns.MIME_TYPE} = ?",
                arrayOf("$folderUri%", DocumentsContract.Document.MIME_TYPE_DIR),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.getLong(0) to cursor.getString(1).orEmpty()
                }
            }
            // Deepest first, so a parent only becomes empty once its children are
            // gone; a delete on a non-empty folder simply does nothing.
            for ((id, path) in rows.sortedByDescending { it.second.length }) {
                if (path.contains("${DownloadsStore.ROOT}/")) continue
                context.contentResolver.delete(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                    null,
                    null
                )
            }
        }
    }

    /**
     * Android 8–9: the old tree is an ordinary directory, and moving a file
     * inside the shared storage tree is a rename — no copy, no extra space.
     *
     * Without the legacy write grant nothing can be moved, and this is not the
     * place to ask for it: the request belongs to the download flow, where the
     * user can see what it is for. The pass reports itself blocked, so the flag
     * stays unset and it happens once the grant exists.
     */
    private suspend fun migrateLegacyFiles(context: Context): MigrationReport {
        if (!DownloadsStore.hasLegacyPermission(context)) return MigrationReport(blocked = true)

        val downloads =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val legacyRoot = File(downloads, LEGACY_DOWNLOAD_ROOT)
        if (!legacyRoot.isDirectory) return MigrationReport()

        var moved = 0
        var renamed = 0
        var failed = 0
        var skipped = 0

        val files = runCatching {
            legacyRoot.walkTopDown().filter { it.isFile }.toList()
        }.getOrElse { return MigrationReport(failed = 1) }

        for (file in files) {
            val relative = runCatching { file.relativeTo(legacyRoot).path }.getOrNull()
            val folder = relative?.let { migrationTargetFolder("$LEGACY_DOWNLOAD_ROOT/$it") }
            if (folder == null) {
                skipped++
                continue
            }
            val targetDir = File(downloads, folder)
            val destination = runCatching {
                targetDir.mkdirs()
                uniqueDestination(targetDir, file.name)
            }.getOrNull()
            if (destination == null) {
                failed++
                continue
            }
            if (destination.name != file.name) renamed++
            if (!runCatching { file.renameTo(destination) }.getOrDefault(false)) {
                failed++
                continue
            }
            moved++
            runCatching {
                AppContainer.historyDao.updateLocalPath(
                    file.absolutePath,
                    destination.absolutePath
                )
            }
        }

        // Same tidy-up as the MediaStore path: leave no empty folder behind.
        if (moved > 0) {
            runCatching {
                legacyRoot.walkBottomUp().forEach { if (it.isDirectory) it.delete() }
            }
        }
        return MigrationReport(moved = moved, renamed = renamed, failed = failed, skipped = skipped)
    }

    /** Appends ` (n)` until the name is free, mirroring MediaStore's behavior. */
    private fun uniqueDestination(dir: File, fileName: String): File {
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
}
