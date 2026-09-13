package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-channel cards above the History list.
 *
 * These aggregate a whole channel, so they are the numbers a user trusts at a
 * glance — and the ones that are wrong in a way nobody notices. Ordering, what
 * counts, and what the total honestly means are all pinned here.
 *
 * Pure calls on [channelDownloadSummaries]; no device needed.
 */
class ChannelDownloadSummaryTest {

    private fun entry(
        messageId: Long,
        channel: String,
        sizeBytes: Long = 1_000L,
        completedAt: Long = messageId,
        succeeded: Boolean = true
    ) = DownloadHistoryEntity(
        messageId = messageId,
        chatId = 1L,
        fileName = "file-$messageId.ogg",
        category = "Audio",
        sourceTitle = channel,
        sizeBytes = sizeBytes,
        completedAtEpochMs = completedAt,
        localPath = if (succeeded) "content://downloads/$messageId" else null,
        succeeded = succeeded
    )

    @Test
    fun `a channel's files and bytes are added up`() {
        val summaries = channelDownloadSummaries(
            listOf(
                entry(1, "MC", sizeBytes = 1_000),
                entry(2, "MC", sizeBytes = 2_000),
                entry(3, "MC", sizeBytes = 3_000)
            )
        )

        assertEquals(1, summaries.size)
        assertEquals(3, summaries.first().files)
        assertEquals(6_000L, summaries.first().bytes)
    }

    @Test
    fun `each channel is summarised separately`() {
        val summaries = channelDownloadSummaries(
            listOf(entry(1, "MC"), entry(2, "Music"), entry(3, "MC"))
        )

        assertEquals(2, summaries.size)
        assertEquals(2, summaries.single { it.title == "MC" }.files)
        assertEquals(1, summaries.single { it.title == "Music" }.files)
    }

    @Test
    fun `the most recently downloaded channel comes first`() {
        // A different order arriving from the query must not reshuffle the cards.
        val summaries = channelDownloadSummaries(
            listOf(
                entry(1, "Music", completedAt = 100),
                entry(2, "MC", completedAt = 900),
                entry(3, "Books", completedAt = 500)
            )
        )

        assertEquals(listOf("MC", "Books", "Music"), summaries.map { it.title })
    }

    @Test
    fun `a channel's position is set by its newest download, not its oldest`() {
        val summaries = channelDownloadSummaries(
            listOf(
                entry(1, "Music", completedAt = 900),
                entry(2, "MC", completedAt = 100),
                entry(3, "MC", completedAt = 950)
            )
        )

        assertEquals(listOf("MC", "Music"), summaries.map { it.title })
    }

    @Test
    fun `failed downloads are not counted and add no size`() {
        val summaries = channelDownloadSummaries(
            listOf(
                entry(1, "MC", sizeBytes = 1_000),
                entry(2, "MC", sizeBytes = 9_000, succeeded = false)
            )
        )

        assertEquals(1, summaries.first().files)
        assertEquals(1_000L, summaries.first().bytes)
    }

    @Test
    fun `a channel whose downloads all failed is not summarised at all`() {
        // There is nothing in its folder to open, so a card would be a dead end.
        val summaries = channelDownloadSummaries(
            listOf(entry(1, "MC", succeeded = false), entry(2, "MC", succeeded = false))
        )

        assertTrue(summaries.isEmpty())
    }

    @Test
    fun `sizes Telegram did not report are flagged rather than counted as zero`() {
        // The total is a floor in that case, and the card says "or more" — a
        // number presented as final would be a guess.
        val summaries = channelDownloadSummaries(
            listOf(
                entry(1, "MC", sizeBytes = 2_000),
                entry(2, "MC", sizeBytes = 0),
                entry(3, "MC", sizeBytes = 0)
            )
        )

        val summary = summaries.first()
        assertEquals(2_000L, summary.bytes)
        assertEquals(2, summary.unknownSizes)
    }

    @Test
    fun `a negative size can never subtract from the total`() {
        val summaries = channelDownloadSummaries(
            listOf(entry(1, "MC", sizeBytes = 5_000), entry(2, "MC", sizeBytes = -4_000))
        )

        assertEquals(5_000L, summaries.first().bytes)
        assertEquals(1, summaries.first().unknownSizes)
    }

    @Test
    fun `one folder is one card, titled with the newest name that wrote to it`() {
        // Two titles can sanitise to the same folder ("MC/Bonus" and "MC_Bonus") and
        // then really are one folder on disk, so they are one card — and the title
        // shown is the one from the most recent download.
        val summaries = channelDownloadSummaries(
            listOf(
                entry(1, "MC/Bonus", completedAt = 100),
                entry(2, "MC_Bonus", completedAt = 900)
            )
        )

        assertEquals(1, summaries.size)
        assertEquals("MC_Bonus", summaries.first().title)
        assertEquals(2, summaries.first().files)
    }

    @Test
    fun `a channel that was renamed becomes two cards, because it has two folders`() {
        // The folder is derived from the channel's title, so a rename is a new
        // folder on disk with its own files. Merging them would point one card's
        // button at a folder that does not hold everything the card counts.
        val summaries = channelDownloadSummaries(
            listOf(
                entry(1, "Old Name", completedAt = 100),
                entry(2, "New Name", completedAt = 900)
            )
        )

        assertEquals(2, summaries.size)
        assertEquals("New Name", summaries.first().title)
        assertTrue(summaries.any { it.folder == "SmartTG Downloader/Old Name" })
        assertTrue(summaries.any { it.folder == "SmartTG Downloader/New Name" })
    }

    @Test
    fun `every folder is the one the app writes to now`() {
        val summaries = channelDownloadSummaries(listOf(entry(1, "My/Channel")))

        assertEquals(DownloadsStore.channelFolder("My/Channel"), summaries.first().folder)
        assertTrue(summaries.first().folder.startsWith("${DownloadsStore.ROOT}/"))
    }

    @Test
    fun `the per-row counts are the same numbers as the cards`() {
        // Both are built from one rule; a row and the card above it must never
        // disagree about how many files a channel holds.
        val entries = listOf(entry(1, "MC"), entry(2, "MC"), entry(3, "Music"))

        val counts = downloadedCountsByChannelFolder(entries)

        for (summary in channelDownloadSummaries(entries)) {
            assertEquals(summary.files, counts[summary.folder])
        }
    }

    @Test
    fun `an empty history has no cards`() {
        assertTrue(channelDownloadSummaries(emptyList()).isEmpty())
    }
}
