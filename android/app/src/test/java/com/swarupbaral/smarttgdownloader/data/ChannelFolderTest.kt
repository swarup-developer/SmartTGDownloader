package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a finished file lands in the user's Download folder.
 *
 * The layout is user-visible and effectively permanent — people will have files
 * in these folders long after this code changes — so the rules are pinned here:
 * one folder named after the app, one folder per channel inside it, and nothing
 * in a channel's name that a filesystem would reject or resolve elsewhere.
 *
 * Everything here is a pure call on [DownloadsStore], so it needs no device.
 */
class ChannelFolderTest {

    @Test
    fun `a channel gets a folder of its own inside the app folder`() {
        assertEquals("SmartTG Downloader/MC", DownloadsStore.channelFolder("MC"))
    }

    @Test
    fun `the root folder is named after the app that wrote it`() {
        // This is what the user sees while hunting for their files, so it has to
        // be recognisable as SmartTG Downloader and nothing else.
        assertEquals("SmartTG Downloader", DownloadsStore.ROOT)
    }

    @Test
    fun `the file type is not part of the path`() {
        // An earlier 2.2.0 build added a <type> level. The channel is what a
        // person looks for, and the extension already says what the file is.
        val folder = DownloadsStore.channelFolder("MC")

        assertEquals(2, folder.split('/').size)
        assertFalse(folder.contains("Audio"))
        assertFalse(folder.contains("Video"))
    }

    @Test
    fun `the spoken path names the folder a person has to open`() {
        assertEquals(
            "Download/SmartTG Downloader/MC",
            DownloadsStore.displayPath(DownloadsStore.channelFolder("MC"))
        )
    }

    @Test
    fun `a batch that saved nothing reports the app folder itself`() {
        // The completion summary falls back to this when every file failed, so
        // it has to name a folder that really exists.
        assertEquals("Download/SmartTG Downloader", DownloadsStore.displayPath())
    }

    @Test
    fun `the button label is the channel name, not the whole path`() {
        assertEquals("MC", DownloadsStore.channelName(DownloadsStore.channelFolder("MC")))
    }

    @Test
    fun `characters a filesystem rejects are replaced, not dropped`() {
        assertEquals("SmartTG Downloader/MC_Bonus", DownloadsStore.channelFolder("MC/Bonus"))
        assertEquals("SmartTG Downloader/A_B_C", DownloadsStore.channelFolder("A:B?C"))
        assertEquals("SmartTG Downloader/C__Windows", DownloadsStore.channelFolder("C:\\Windows"))
    }

    @Test
    fun `a channel that sanitises to nothing still gets a usable folder`() {
        assertEquals("SmartTG Downloader/Telegram", DownloadsStore.channelFolder("   "))
        assertEquals("SmartTG Downloader/Telegram", DownloadsStore.channelFolder(""))
    }

    @Test
    fun `a name of nothing but dots cannot climb out of the app folder`() {
        // `.` and `..` are path references rather than names: without this, a
        // channel called ".." would write into Download/ itself, next to the
        // app's own folder.
        assertEquals("SmartTG Downloader/Telegram", DownloadsStore.channelFolder(".."))
        assertEquals("SmartTG Downloader/Telegram", DownloadsStore.channelFolder("."))
        assertEquals("SmartTG Downloader/Telegram", DownloadsStore.channelFolder("..."))
    }

    @Test
    fun `surrounding whitespace is trimmed so folder names do not look doubled`() {
        assertEquals("SmartTG Downloader/MC", DownloadsStore.channelFolder("  MC  "))
    }

    @Test
    fun `non-latin channel names are kept as they are`() {
        // Telegram channels are named in every script; mangling the name would
        // make the folder unrecognisable to the person who named the channel.
        assertEquals("SmartTG Downloader/संगीत", DownloadsStore.channelFolder("संगीत"))
        assertEquals("SmartTG Downloader/日本語", DownloadsStore.channelFolder("日本語"))
        assertEquals("SmartTG Downloader/🎵 Music", DownloadsStore.channelFolder("🎵 Music"))
    }

    @Test
    fun `the same channel always resolves to the same folder`() {
        assertEquals(DownloadsStore.channelFolder("MC"), DownloadsStore.channelFolder("MC"))
        assertNotEquals(DownloadsStore.channelFolder("MC"), DownloadsStore.channelFolder("MC 2"))
    }

    @Test
    fun `an archive is named after the channel and what is in it`() {
        assertEquals("MC - Audio (20 files).zip", DownloadsStore.archiveName("MC", "Audio", 20))
        assertEquals("MC - All (3 files).zip", DownloadsStore.archiveName("MC", "All", 3))
    }

    @Test
    fun `an archive name is sanitised and always ends in zip`() {
        // An archive travels — it gets sent, copied, opened elsewhere — so the
        // name has to be usable outside the app's own folder.
        for ((title, count) in listOf("My/Channel" to 2, "C:\\Songs" to 1, "  " to 7)) {
            val name = DownloadsStore.archiveName(title, "Video", count)

            assertTrue(name, name.endsWith(".zip"))
            assertFalse(name, name.contains('/'))
            assertFalse(name, name.contains('\\'))
            assertTrue(name, name.contains("Video"))
            assertTrue(name, name.contains(count.toString()))
        }
        assertEquals("My_Channel - Video (2 files).zip", DownloadsStore.archiveName("My/Channel", "Video", 2))
    }

    @Test
    fun `an unnamed type still names the archive something`() {
        assertEquals("MC - All (5 files).zip", DownloadsStore.archiveName("MC", "", 5))
    }

    @Test
    fun `every folder is exactly one segment under the app folder, whatever the name`() {
        // Swept rather than spot-checked: one stray separator in a channel name
        // would silently scatter that channel's files across two folders.
        val names = listOf(
            "MC", "My/Channel", "A:B", "  ", "..", "../escape", "C:\\Windows",
            "**", "a?b*c", "\u0000nul", "🎵 Music", "tab\there", "new\nline",
            "a".repeat(300), "संगीत".repeat(60)
        )

        for (name in names) {
            val folder = DownloadsStore.channelFolder(name)
            val segments = folder.split('/')
            assertEquals("$name -> $folder", 2, segments.size)
            assertTrue("$name -> $folder", folder.startsWith("${DownloadsStore.ROOT}/"))
            assertTrue("$name -> $folder", segments[1].isNotBlank())
            assertFalse("$name -> $folder", segments[1] == "." || segments[1] == "..")
        }
    }

    @Test
    fun `an over-long channel name is shortened to fit one path component`() {
        // Filesystems cap a single component at 255 bytes and Telegram does not
        // cap a channel title, so an unshortened name would make every download
        // in that channel fail.
        for (name in listOf("a".repeat(300), "संगीत".repeat(60), "🎵".repeat(200))) {
            val segment = DownloadsStore.channelFolder(name).substringAfter('/')

            assertTrue(
                "${name.take(12)}… -> ${segment.toByteArray(Charsets.UTF_8).size} bytes",
                segment.toByteArray(Charsets.UTF_8).size <= 180
            )
            assertTrue("$name shortened to nothing", segment.isNotBlank())
        }
    }

    @Test
    fun `shortening never cuts a character in half`() {
        // A length limit that split a surrogate pair or a multi-byte character
        // would leave a replacement glyph in the folder name.
        val segment = DownloadsStore.channelFolder("🎵".repeat(200)).substringAfter('/')

        // Re-encoding the name must round-trip: a lone surrogate would not.
        val roundTripped = String(segment.toByteArray(Charsets.UTF_8), Charsets.UTF_8)

        assertEquals(segment, roundTripped)
        assertFalse(segment.contains('\uFFFD'))
    }
}
