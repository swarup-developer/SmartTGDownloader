package com.swarupbaral.smarttgdownloader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One file waiting to be downloaded, with everything needed to fetch it again
 * after the process has died.
 *
 * [fileId] is carried so TDLib progress updates can be routed back to the right
 * row without a message lookup, and so a partial download can be handed back to
 * TDLib to resume.
 */
data class QueuedDownload(
    val messageId: Long,
    val fileId: Int,
    val chatId: Long,
    val fileName: String,
    val category: String,
    val sourceTitle: String,
    val sizeBytes: Long
)

/**
 * Durable storage for the pending download queue.
 *
 * Deliberately SharedPreferences + JSON rather than another Room table: this is
 * transient work-in-progress, not user data, and adding a Room entity would
 * mean a schema version bump and a hand-written migration that could wipe the
 * download history if it were even slightly wrong. A queue that fails to parse
 * simply means "nothing pending", which is a safe degradation.
 *
 * Only the *remaining* items are stored, and the list is rewritten after each
 * file completes, so a process kill leaves an accurate resume point.
 */
/**
 * A whole selection the user asked for as a single .zip archive.
 *
 * [items] is the complete selection rather than a cursor, because the archive is
 * written in one pass from the beginning: a run that was interrupted throws its
 * half-written archive away and starts a new one instead of trying to splice
 * into the middle of it. That restart is cheap rather than wasteful — TDLib
 * keeps fetched files in its own cache, so the second pass reads them from disk
 * instead of downloading them again.
 *
 * [fileName] and [folder] are decided when the request is made, so the archive
 * ends up in the channel's folder next to where separate files would have gone.
 */
data class ZipRequest(
    val fileName: String,
    val folder: String,
    val items: List<QueuedDownload>,
    /**
     * Whether the separate files are published as well as packed.
     *
     * Carried on the request rather than read from preferences at run time: a
     * request can outlive the launch that made it, and it should still do what it
     * was asked to do even if the setting has changed since.
     */
    val keepFiles: Boolean = false
)

object DownloadQueueStore {

    private const val PREFS_FILE = "smarttg_queue"
    private const val KEY_ITEMS = "pending_items"
    private const val KEY_FAILED = "failed_items"
    private const val KEY_ZIP = "zip_request"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun load(context: Context): List<QueuedDownload> = decode(prefs(context).getString(KEY_ITEMS, null))

    fun save(context: Context, items: List<QueuedDownload>) {
        if (items.isEmpty()) {
            clear(context)
            return
        }
        prefs(context).edit().putString(KEY_ITEMS, encode(items)).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ITEMS).apply()
    }

    /**
     * Items that failed in the last run, kept in a separate key from the
     * pending queue.
     *
     * They are deliberately NOT pending work: nothing should re-fetch a file
     * that already failed on its own, so [DownloadManager] never resumes them
     * automatically. Holding them here means the Retry action still works after
     * the process has been killed, and filling a queue that then silently
     * droppped failed files is what this avoids.
     */
    fun loadFailed(context: Context): List<QueuedDownload> =
        decode(prefs(context).getString(KEY_FAILED, null))

    fun saveFailed(context: Context, items: List<QueuedDownload>) {
        if (items.isEmpty()) {
            clearFailed(context)
            return
        }
        prefs(context).edit().putString(KEY_FAILED, encode(items)).apply()
    }

    fun clearFailed(context: Context) {
        prefs(context).edit().remove(KEY_FAILED).apply()
    }

    /**
     * The selection the user asked for as one .zip archive, or null.
     *
     * Persisted like the queue, so an archive survives the process being killed
     * and is written when the app is next opened rather than being lost with the
     * screen that asked for it.
     */
    fun loadZipRequest(context: Context): ZipRequest? {
        val raw = prefs(context).getString(KEY_ZIP, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ZipRequest(
                fileName = json.getString("fileName"),
                folder = json.getString("folder"),
                items = decodeItems(json.getJSONArray("items")),
                // Absent in a request written before this existed, which meant
                // exactly this: the archive only.
                keepFiles = json.optBoolean("keepFiles", false)
            )
        }.getOrNull()
    }

    fun saveZipRequest(context: Context, request: ZipRequest) {
        val json = JSONObject().apply {
            put("fileName", request.fileName)
            put("folder", request.folder)
            put("items", encodeItems(request.items))
            put("keepFiles", request.keepFiles)
        }
        prefs(context).edit().putString(KEY_ZIP, json.toString()).apply()
    }

    fun clearZipRequest(context: Context) {
        prefs(context).edit().remove(KEY_ZIP).apply()
    }

    private fun encode(items: List<QueuedDownload>): String = encodeItems(items).toString()

    private fun encodeItems(items: List<QueuedDownload>): JSONArray {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject().apply {
                    put("messageId", item.messageId)
                    put("fileId", item.fileId)
                    put("chatId", item.chatId)
                    put("fileName", item.fileName)
                    put("category", item.category)
                    put("sourceTitle", item.sourceTitle)
                    put("sizeBytes", item.sizeBytes)
                }
            )
        }
        return array
    }

    private fun decode(raw: String?): List<QueuedDownload> {
        if (raw == null) return emptyList()
        return runCatching { decodeItems(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    private fun decodeItems(array: JSONArray): List<QueuedDownload> =
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            QueuedDownload(
                messageId = item.getLong("messageId"),
                fileId = item.getInt("fileId"),
                chatId = item.getLong("chatId"),
                fileName = item.getString("fileName"),
                category = item.getString("category"),
                sourceTitle = item.getString("sourceTitle"),
                sizeBytes = item.getLong("sizeBytes")
            )
        }
}
