package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finished ZIP recorded as its own kind of History entry.
 *
 * Two things have to hold or the feature is worse than not having it: an archive
 * must never be offered as something that can be re-fetched (there is no message
 * behind it), and its synthetic id must never land on a real download's row.
 *
 * Pure calls on the helpers the engine uses; no device needed.
 */
class ArchiveHistoryRowsTest {

    private fun fileEntry(
        messageId: Long,
        channel: String = "MC",
        sizeBytes: Long = 1_000L,
        completedAt: Long = messageId
    ) = DownloadHistoryEntity(
        messageId = messageId,
        chatId = 1L,
        fileName = "file-$messageId.ogg",
        category = "Audio",
        sourceTitle = channel,
        sizeBytes = sizeBytes,
        completedAtEpochMs = completedAt,
        localPath = "content://downloads/$messageId",
        succeeded = true,
        entryKind = DownloadEntryKind.FILE
    )

    private fun archiveEntry(
        messageId: Long = -1L,
        channel: String = "MC",
        sizeBytes: Long = 9_000L,
        completedAt: Long = 100L
    ) = DownloadHistoryEntity(
        messageId = messageId,
        chatId = 1L,
        fileName = "$channel - Audio (20 files).zip",
        category = "Audio",
        sourceTitle = channel,
        sizeBytes = sizeBytes,
        completedAtEpochMs = completedAt,
        localPath = "content://downloads/zip",
        succeeded = true,
        entryKind = DownloadEntryKind.ARCHIVE
    )

    @Test
    fun `an archive row is the only thing that counts as an archive`() {
        assertTrue(archiveEntry().isArchive)
        assertFalse(fileEntry(1).isArchive)

        // Rows written before archives existed carry no kind at all. They are
        // files, and treating one as an archive would put a Re-download-less Open
        // row on a perfectly fetchable download.
        assertFalse(fileEntry(1).copy(entryKind = null).isArchive)
    }

    @Test
    fun `an archive of one type is filed under that type`() {
        assertEquals("Audio", archiveCategory(listOf("Audio", "Audio", "Audio")))
        assertEquals("Audio", archiveCategory(listOf("Audio")))
    }

    @Test
    fun `a mixed archive is filed under All rather than one of its types`() {
        // Labelling it "Audio" because audio came first would hide it from
        // anyone filtering by Video, who is looking at a zip of their videos.
        assertEquals(AppPreferences.CATEGORY_ALL, archiveCategory(listOf("Audio", "Video")))
        assertEquals(AppPreferences.CATEGORY_ALL, archiveCategory(listOf("Audio", "Audio", "Docs")))
    }

    @Test
    fun `an archive with no types at all is filed under All`() {
        assertEquals(AppPreferences.CATEGORY_ALL, archiveCategory(emptyList()))
        assertEquals(AppPreferences.CATEGORY_ALL, archiveCategory(listOf("", "  ")))
    }

    @Test
    fun `the first archive takes the first id below the message range`() {
        // Telegram message ids are positive; archives are numbered downwards so
        // the two spaces can never meet.
        assertEquals(-1L, archiveHistoryId(existing = null, lowestArchiveId = null))
    }

    @Test
    fun `each new archive takes the next id down`() {
        assertEquals(-4L, archiveHistoryId(existing = null, lowestArchiveId = -3L))
        // Deleting rows leaves gaps, and a gap is still lower than everything in
        // use — so the next archive can never land on an occupied id.
        assertEquals(-2L, archiveHistoryId(existing = null, lowestArchiveId = -1L))
    }

    @Test
    fun `an archive already on disk keeps its own row instead of adding another`() {
        // Re-downloading the same selection into the same place updates one row,
        // rather than filling the list with entries pointing at one file.
        assertEquals(-7L, archiveHistoryId(existing = -7L, lowestArchiveId = -9L))
    }

    @Test
    fun `an archive is never summarised as downloaded files`() {
        // The zip holds the same audio this summary counts, so counting it too
        // would report a channel as having more files and more bytes than the
        // user actually has.
        val summaries = channelDownloadSummaries(
            listOf(fileEntry(1, sizeBytes = 1_000), archiveEntry(sizeBytes = 9_000))
        )

        assertEquals(1, summaries.size)
        assertEquals(1, summaries.first().files)
        assertEquals(1_000L, summaries.first().bytes)
    }

    @Test
    fun `a channel known only through an archive has no summary`() {
        // There is no downloaded file to count and no size to add up. The archive
        // itself is still listed below with its own Open action.
        val summaries = channelDownloadSummaries(listOf(archiveEntry(channel = "OnlyZip")))

        assertTrue(summaries.isEmpty())
    }

    @Test
    fun `an archive does not become the channel's newest title`() {
        // The card's name comes from the most recent row; an archive must not be
        // able to rename a channel on the card.
        val summaries = channelDownloadSummaries(
            listOf(
                fileEntry(1, channel = "MC", completedAt = 5L),
                archiveEntry(channel = "MC Renamed", completedAt = 99L)
            )
        )

        assertEquals("MC", summaries.single().title)
        assertEquals(5L, summaries.single().lastDownloadedAt)
    }
}
