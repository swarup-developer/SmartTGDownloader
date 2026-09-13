package com.swarupbaral.smarttgdownloader.data

import com.swarupbaral.smarttgdownloader.data.HistoryWalk.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the paging policy behind "a channel with thousands of
 * files shows zero, or one".
 *
 * Both symptoms came from this logic, not from the network: a short or empty
 * page was treated as the end of the chat, and the boundary message was
 * collected twice. Each test below is a case the old loop got wrong.
 */
class HistoryWalkTest {

    /** A descending run of ids, mimicking one page of a chat. */
    private fun page(newestId: Long, count: Int): List<Long> =
        (0 until count).map { newestId - it }

    @Test
    fun `a full page advances and asks from its oldest message`() {
        val walk = HistoryWalk()
        val step = walk.next(page(newestId = 1_000, count = 100), totalCount = 50_000)

        assertTrue(step is Step.Advance)
        assertEquals((step as Step.Advance).fresh.size, 100)
        // The next request starts at a real message id from this page, never a
        // guessed one: TDLib wants an id that exists in the chat.
        assertEquals(901L, walk.fromMessageId)
        assertEquals(100, walk.scannedMessages)
    }

    @Test
    fun `an empty first page is a retry, not the end of the chat`() {
        // The reported bug: a large channel whose history TDLib has not loaded
        // yet answers with nothing, and the scan used to stop right there.
        val walk = HistoryWalk()
        val step = walk.next(emptyList(), totalCount = 50_000)

        assertTrue("an empty page must not end the walk", step is Step.Retry)
        assertTrue((step as Step.Retry).delayMs > 0)
    }

    @Test
    fun `the walk recovers once Telegram has loaded the history`() {
        val walk = HistoryWalk()

        // Empty, then empty again, then real: the classic back-fill sequence.
        assertTrue(walk.next(emptyList(), totalCount = 50_000) is Step.Retry)
        assertTrue(walk.next(emptyList(), totalCount = 50_000) is Step.Retry)

        val step = walk.next(page(newestId = 50_000, count = 100), totalCount = 50_000)
        assertTrue(step is Step.Advance)
        assertEquals(100, walk.scannedMessages)
        assertFalse(walk.historyIncomplete)
    }

    @Test
    fun `a short page is not the end of the chat either`() {
        // TDLib answers from what it has loaded, so a big channel can hand back
        // 30 messages while it fetches the rest.
        val walk = HistoryWalk()
        val step = walk.next(page(newestId = 50_000, count = 30), totalCount = 50_000)

        assertTrue(step is Step.Advance)
        // The next request continues from the oldest message of the short page.
        assertEquals(49_971L, walk.fromMessageId)

        // And the follow-up, which returns only the boundary echo, must retry
        // rather than conclude the channel is finished.
        assertTrue(walk.next(listOf(50_000L), totalCount = 50_000) is Step.Retry)
    }

    @Test
    fun `the boundary message is not handled twice`() {
        val walk = HistoryWalk()

        val first = walk.next(page(newestId = 1_000, count = 100), totalCount = 50_000)
        assertEquals(100, (first as Step.Advance).fresh.size)

        // getChatHistory(offset = 0) includes from_message_id itself, so a real
        // continuation page is the echo followed by the next 99 messages.
        val continuation = listOf(901L) + page(newestId = 900, count = 99)
        val second = walk.next(continuation, totalCount = 50_000)
        val fresh = (second as Step.Advance).fresh

        assertFalse("the echo must not be re-handled", fresh.contains(901L))
        assertEquals(99, fresh.size)
        // Counting it twice would also over-report how far the walk got.
        assertEquals(199, walk.scannedMessages)
    }

    @Test
    fun `an identical repeated page yields nothing new`() {
        val walk = HistoryWalk()
        walk.next(page(newestId = 1_000, count = 100), totalCount = 50_000)

        // Same page again: every id is at or above the oldest one already seen.
        val step = walk.next(page(newestId = 1_000, count = 100), totalCount = 50_000)
        assertTrue(step is Step.Retry)
        assertEquals(100, walk.scannedMessages)
    }

    @Test
    fun `Telegram's own count ends the walk without spending retries`() {
        val walk = HistoryWalk()
        // Walk a chat of 100 messages: a single page covers it.
        val step = walk.next(page(newestId = 100, count = 100), totalCount = 100)
        assertTrue(step is Step.Advance)

        // Everything was covered, so an empty follow-up is the real end.
        assertEquals(Step.End, walk.next(emptyList(), totalCount = 100))
    }

    @Test
    fun `giving up mid-history is reported as incomplete`() {
        val walk = HistoryWalk()
        walk.next(page(newestId = 50_000, count = 100), totalCount = 50_000)

        // Walk stalls after 100 of 50,000 messages.
        var retries = 0
        while (true) {
            when (val step = walk.next(emptyList(), totalCount = 50_000)) {
                is Step.Retry -> {
                    retries++
                    assertTrue(step.delayMs > 0)
                }
                Step.End -> break
                is Step.Advance -> throw AssertionError("nothing new was offered")
            }
        }

        // Bounded patience: it must not retry forever.
        assertTrue("expected a bounded number of retries, got $retries", retries in 1..5)
        assertTrue("a proven truncation must be flagged", walk.historyIncomplete)
    }

    @Test
    fun `an unknown message count never claims incompleteness`() {
        val walk = HistoryWalk()
        walk.next(page(newestId = 1_000, count = 100))

        // Without a count there is no evidence either way, and a false "may be
        // incomplete" is worse than silence.
        while (walk.next(emptyList()) is Step.Retry) { }

        assertFalse(walk.historyIncomplete)
    }

    @Test
    fun `a nearly complete walk is not flagged as truncated`() {
        // 9,900 of 10,000 messages: Telegram's count is approximate, so the
        // tail must not be mistaken for a stalled walk and reported as a
        // truncation the user has to act on.
        val walk = HistoryWalk()
        var remaining = 9_900
        var newest = 10_000L
        while (remaining > 0) {
            val size = minOf(100, remaining)
            walk.next(page(newestId = newest, count = size), totalCount = 10_000)
            newest -= size
            remaining -= size
        }
        while (walk.next(emptyList(), totalCount = 10_000) is Step.Retry) { }

        assertFalse(walk.historyIncomplete)
    }

    @Test
    fun `every message is handled exactly once across overlapping pages`() {
        val walk = HistoryWalk()
        val handled = mutableListOf<Long>()
        var cursor = 5_000L

        for (i in 0 until 20) {
            // Each continuation re-delivers the previous page's oldest id.
            val ids = page(newestId = cursor, count = 100)
            when (val step = walk.next(ids, totalCount = 50_000)) {
                is Step.Advance -> handled += step.fresh
                is Step.Retry -> Unit
                Step.End -> break
            }
            cursor -= 99
        }

        assertEquals("no duplicates", handled.distinct().size, handled.size)
        assertEquals(handled.size, walk.scannedMessages)
        assertEquals("walked strictly downwards", handled.sortedDescending(), handled)
    }

    @Test
    fun `the walk always asks from a message it actually saw`() {
        val walk = HistoryWalk()
        val seen = mutableSetOf<Long>()

        var cursor = 3_000L
        repeat(10) {
            val ids = page(newestId = cursor, count = 100)
            seen += ids
            val step = walk.next(ids, totalCount = 3_000)
            if (step is Step.Advance) {
                // A guessed id (oldest - 1) can be an id that does not exist,
                // which TDLib rejects; only real ids are safe.
                assertTrue("fromMessageId ${walk.fromMessageId} was never seen", walk.fromMessageId in seen)
            }
            cursor -= 99
        }
    }
}
