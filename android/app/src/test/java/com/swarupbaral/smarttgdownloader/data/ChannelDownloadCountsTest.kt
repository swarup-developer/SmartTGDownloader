package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-channel download count shown on History.
 *
 * It answers two questions a person actually asks on that screen: "how much of
 * this channel have I already got?", and — right after a batch — "did everything
 * I asked for really arrive?". Both depend on what is counted, so the rules are
 * pinned here rather than left to the UI.
 *
 * Pure function calls on [downloadedCountsByChannelFolder]; no device needed.
 */
class ChannelDownloadCountsTest {

    private fun entry(
        messageId: Long,
        channel: String,
        category: String = "Audio",
        succeeded: Boolean = true
    ) = DownloadHistoryEntity(
        messageId = messageId,
        chatId = 1L,
        fileName = "file-$messageId.ogg",
        category = category,
        sourceTitle = channel,
        sizeBytes = 1_000L,
        completedAtEpochMs = messageId,
        localPath = if (succeeded) "content://downloads/$messageId" else null,
        succeeded = succeeded
    )

    @Test
    fun `only files that actually arrived are counted`() {
        val counts = downloadedCountsByChannelFolder(
            listOf(
                entry(1, "MC"),
                entry(2, "MC"),
                entry(3, "MC", succeeded = false)
            )
        )

        assertEquals(2, counts[DownloadsStore.channelFolder("MC")])
    }

    @Test
    fun `a channel with nothing but failures has no count at all`() {
        // Absent rather than zero: a row only offers the folder button and the
        // count when there is something in that folder to go and see.
        val counts = downloadedCountsByChannelFolder(
            listOf(entry(1, "MC", succeeded = false), entry(2, "MC", succeeded = false))
        )

        assertTrue(counts.isEmpty())
    }

    @Test
    fun `each channel is counted on its own`() {
        val counts = downloadedCountsByChannelFolder(
            listOf(entry(1, "MC"), entry(2, "MC"), entry(3, "Music"), entry(4, "MC 2"))
        )

        assertEquals(2, counts[DownloadsStore.channelFolder("MC")])
        assertEquals(1, counts[DownloadsStore.channelFolder("Music")])
        assertEquals(1, counts[DownloadsStore.channelFolder("MC 2")])
    }

    @Test
    fun `the count follows the folder, not the spelling of the title`() {
        // "MC/Bonus" and "MC_Bonus" are the same folder on disk, so keeping two
        // counts would claim fewer files than the folder actually holds.
        val counts = downloadedCountsByChannelFolder(
            listOf(entry(1, "MC/Bonus"), entry(2, "MC_Bonus"))
        )

        assertEquals(1, counts.size)
        assertEquals(2, counts.values.first())
    }

    @Test
    fun `the file type does not split a channel's count`() {
        // History can be filtered by type; the number is about the channel, so a
        // filter must never quietly make it smaller.
        val counts = downloadedCountsByChannelFolder(
            listOf(entry(1, "MC", category = "Audio"), entry(2, "MC", category = "Video"))
        )

        assertEquals(2, counts[DownloadsStore.channelFolder("MC")])
    }

    @Test
    fun `a range download counts exactly what arrived`() {
        // The case this number exists for: 20 files picked out of a huge channel.
        val entries = (1L..20L).map { entry(it, "MC") } + entry(21, "MC", succeeded = false)

        assertEquals(
            20,
            downloadedCountsByChannelFolder(entries)[DownloadsStore.channelFolder("MC")]
        )
    }

    @Test
    fun `an empty history has no channels`() {
        assertTrue(downloadedCountsByChannelFolder(emptyList()).isEmpty())
    }

    @Test
    fun `every key is a channel folder, never a raw title`() {
        // The key is used to build the folder path the row opens, so it has to
        // be the folder form for every entry, including a sanitised title.
        val counts = downloadedCountsByChannelFolder(
            listOf(entry(1, "MC/Bonus"), entry(2, "MC"))
        )

        for (key in counts.keys) {
            assertTrue(key, key.startsWith("${DownloadsStore.ROOT}/"))
        }
        assertFalse(counts.containsKey("MC"))
        assertFalse(counts.containsKey("MC/Bonus"))
    }
}
