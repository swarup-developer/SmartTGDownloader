package com.swarupbaral.smarttgdownloader.data

import java.util.concurrent.ConcurrentHashMap

/**
 * Bookkeeping for the most recent scan, keyed by scan token.
 *
 * Only the chat id is kept. Scan results are intentionally NOT cached app-wide:
 * the UI consumes the result of the exact token it requested, and any newer
 * token supersedes older ones. This mirrors the stale-cache fix on the Windows
 * client.
 *
 * Storing just the chat id also matters for scale: a channel with tens of
 * thousands of matching files would otherwise pin every TdApi.Message in
 * memory. Individual messages are fetched on demand at download time instead.
 */
object ScanRegistry {

    private val chatIds = ConcurrentHashMap<Long, Long>()

    fun record(token: Long, chatId: Long) {
        chatIds.clear() // only the latest scan is meaningful
        chatIds[token] = chatId
    }

    fun chatIdFor(token: Long): Long = chatIds[token] ?: 0L

    fun clear() = chatIds.clear()
}
