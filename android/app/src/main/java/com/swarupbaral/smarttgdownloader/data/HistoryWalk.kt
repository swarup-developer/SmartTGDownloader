package com.swarupbaral.smarttgdownloader.data

/**
 * The paging policy for walking a chat's history newest-first.
 *
 * Extracted from `TelegramRepository.scanChat` and kept pure so the rules that
 * decide where the walk goes next can be unit-tested. The bug this replaces —
 * a channel holding tens of thousands of files reporting **zero**, or a single
 * file — was entirely a policy bug, not a network one, and none of it would
 * have been caught by testing the UI.
 *
 * Three rules matter, and each produced that symptom when it was wrong:
 *
 *  1. **A continuation page re-delivers one message.** `getChatHistory` with
 *     `offset = 0` includes `from_message_id` itself, so the last message of
 *     the previous page arrives again. Collecting it twice means duplicate
 *     rows — and duplicate `LazyColumn` keys.
 *  2. **A short or empty page is not the end of the chat.** TDLib answers out
 *     of the history it has already loaded and back-fills from the server in
 *     the background, so a first pass over a large channel can legitimately
 *     come back empty. Treating that as "finished" is what made a channel full
 *     of files look empty.
 *  3. **Giving up has to be reported.** When the walk stops without evidence
 *     of reaching the start of the chat, the result is marked incomplete, so
 *     the UI can say so rather than asserting the channel contains no such
 *     files.
 */
internal class HistoryWalk(
    private val pageSize: Int = PAGE_SIZE,
    private val retryDelaysMs: LongArray = RETRY_DELAYS_MS
) {

    /** What to do with the page that just arrived. */
    sealed interface Step {
        /** These ids are new; handle them and ask again from [fromMessageId]. */
        data class Advance(val fresh: List<Long>) : Step

        /**
         * Nothing new came back. Ask again for the same range after [delayMs]:
         * TDLib may still be loading that stretch of history.
         */
        data class Retry(val delayMs: Long) : Step

        /** The walk is over. */
        data object End : Step
    }

    /**
     * The id to ask from next; 0 means "start at the newest message".
     *
     * Always an id that came back from the chat, never a guessed one: TDLib
     * wants a real message id, and the previous page's oldest message is the
     * only value here that is guaranteed to be one.
     */
    var fromMessageId: Long = 0L
        private set

    /** Messages examined so far, each counted once. */
    var scannedMessages: Int = 0
        private set

    /**
     * True when the walk stopped short of the start of the chat.
     *
     * Only set when Telegram's own message count proves it, so the UI never
     * cries "incomplete" on a channel that was walked to the end — the
     * count is approximate, so a tolerance below absorbs the rounding.
     */
    var historyIncomplete: Boolean = false
        private set

    /** The page size this walk issues, so the caller asks for matching pages. */
    val requestedPageSize: Int get() = pageSize

    /** Oldest id handled so far; anything at or above it is a duplicate. */
    private var oldestSeen = Long.MAX_VALUE

    /** Telegram's approximate message count for the chat; 0 while unknown. */
    private var approximateTotal = 0

    private var stalls = 0

    /**
     * Consumes one page and decides what happens next.
     *
     * [pageIds] are the message ids from the page, newest first.
     * [totalCount] is `TdApi.Messages.totalCount` — the chat's approximate
     * message count, which is what lets this distinguish "the chat ends here"
     * from "Telegram has not loaded the rest yet".
     */
    fun next(pageIds: List<Long>, totalCount: Int = 0): Step {
        if (totalCount > 0) approximateTotal = totalCount

        // Rule 1: only ids below everything already handled are new, which
        // drops the boundary echo and any overlap between pages.
        val fresh = pageIds.filter { it < oldestSeen }

        if (fresh.isEmpty()) {
            // A chat whose whole history has been walked needs no retry.
            if (walkedEverything) return Step.End
            if (stalls < retryDelaysMs.size) {
                val delay = retryDelaysMs[stalls]
                stalls++
                return Step.Retry(delay)
            }
            // Rule 3: out of patience. Say whether this is a real truncation.
            historyIncomplete = approximateTotal > 0 && !walkedEverything
            return Step.End
        }

        stalls = 0
        scannedMessages += fresh.size
        oldestSeen = fresh.min()
        fromMessageId = oldestSeen
        return Step.Advance(fresh)
    }

    /**
     * True when Telegram's message count says the chat has been covered.
     *
     * A fraction rather than an exact match because the count is approximate;
     * the tolerance is deliberately one-sided, since claiming a walk is
     * complete when it is not is the bug being fixed, while an occasional
     * "may be incomplete" costs the user one extra scan.
     */
    private val walkedEverything: Boolean
        get() = approximateTotal > 0 &&
            scannedMessages * 100 >= approximateTotal * COMPLETENESS_PERCENT

    companion object {
        /** `getChatHistory`'s maximum, and TDLib's recommended page size. */
        const val PAGE_SIZE = 100

        /**
         * Waits between re-asks before concluding the walk is over: one entry
         * per retry, so the budget is three attempts spanning ~1.2s.
         */
        private val RETRY_DELAYS_MS = longArrayOf(200L, 400L, 600L)

        /** Percentage of the chat's message count that counts as "all of it". */
        private const val COMPLETENESS_PERCENT = 95
    }
}
