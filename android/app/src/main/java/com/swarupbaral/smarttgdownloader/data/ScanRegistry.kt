package com.swarupbaral.smarttgdownloader.data

import java.util.concurrent.ConcurrentHashMap
import org.drinkless.tdlib.TdApi

/**
 * Bookkeeping for in-flight / completed scans keyed by scan token.
 *
 * Scan results are intentionally NOT cached app-wide: the UI consumes the
 * result of the exact token it requested, and any newer token supersedes
 * older ones. This mirrors the stale-cache fix on the Windows client.
 */
object ScanRegistry {

    data class ScanRecord(
        val chatId: Long,
        val messages: Map<Long, TdApi.Message>
    )

    private val records = ConcurrentHashMap<Long, ScanRecord>()

    fun record(token: Long, chatId: Long, messages: Map<Long, TdApi.Message>) {
        records.clear() // only the latest scan is meaningful
        records[token] = ScanRecord(chatId, messages)
    }

    fun chatIdFor(token: Long): Long = records[token]?.chatId ?: 0L

    fun messagesFor(token: Long): Map<Long, TdApi.Message> =
        records[token]?.messages ?: emptyMap()

    fun clear() = records.clear()
}
