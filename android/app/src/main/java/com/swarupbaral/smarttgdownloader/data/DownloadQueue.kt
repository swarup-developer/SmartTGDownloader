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
object DownloadQueueStore {

    private const val PREFS_FILE = "smarttg_queue"
    private const val KEY_ITEMS = "pending_items"
    private const val KEY_FAILED = "failed_items"

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

    private fun encode(items: List<QueuedDownload>): String {
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
        return array.toString()
    }

    private fun decode(raw: String?): List<QueuedDownload> {
        if (raw == null) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
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
        }.getOrDefault(emptyList())
    }
}
