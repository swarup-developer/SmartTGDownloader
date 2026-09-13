package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-time move out of the old `Download/SmartTG/<channel>/<type>/` tree.
 *
 * Two things here are easy to get wrong and expensive when wrong: deciding which
 * paths are legacy (the new folder's name *starts with* the old one, so a prefix
 * test would move freshly downloaded files onto themselves), and what the user
 * is told afterwards. Both are pure functions, so both are pinned here.
 *
 * The copy/delete itself needs a device and is not covered by this class.
 */
class DownloadsMigrationTest {

    @Test
    fun `a legacy file's channel becomes its new folder`() {
        assertEquals("SmartTG Downloader/MC", migrationTargetFolder("Download/SmartTG/MC/Audio/"))
    }

    @Test
    fun `a path without a trailing separator is read the same way`() {
        assertEquals("SmartTG Downloader/MC", migrationTargetFolder("Download/SmartTG/MC/Audio"))
    }

    @Test
    fun `a file below the channel but not below a type folder is still that channel`() {
        assertEquals("SmartTG Downloader/MC", migrationTargetFolder("Download/SmartTG/MC/"))
    }

    @Test
    fun `the new tree is never treated as legacy`() {
        // The regression this exists for: "SmartTG Downloader" starts with
        // "SmartTG", so a prefix test would sweep up every current download and
        // move it into a folder named "SmartTG Downloader Downloader".
        assertNull(migrationTargetFolder("Download/SmartTG Downloader/MC/Audio/"))
        assertNull(migrationTargetFolder("Download/SmartTG Downloader/MC/"))
    }

    @Test
    fun `a file sitting directly in the old root is left alone`() {
        // The app never wrote one, so moving it would be guessing about somebody
        // else's file.
        assertNull(migrationTargetFolder("Download/SmartTG/"))
        assertNull(migrationTargetFolder("Download/SmartTG"))
    }

    @Test
    fun `a folder that merely shares the old name's prefix is left alone`() {
        assertNull(migrationTargetFolder("Download/SmartTG Archives/MC/Audio/"))
        assertNull(migrationTargetFolder("Download/SmartTGM/MC/Audio/"))
    }

    @Test
    fun `unrelated folders in Download are left alone`() {
        assertNull(migrationTargetFolder("Download/DCIM/MC/Audio/"))
        assertNull(migrationTargetFolder("Download/Music/MC/"))
        assertNull(migrationTargetFolder("Pictures/SmartTG/MC/Audio/"))
    }

    @Test
    fun `the old root is matched exactly, not loosely`() {
        // A case variant is a folder the app never created, so there is nothing
        // of ours in it to move.
        assertNull(migrationTargetFolder("Download/smarttg/MC/Audio/"))
    }

    @Test
    fun `blank and malformed paths are ignored`() {
        assertNull(migrationTargetFolder(""))
        assertNull(migrationTargetFolder("   "))
        assertNull(migrationTargetFolder("Download"))
        assertNull(migrationTargetFolder("SmartTG/MC/Audio/"))
    }

    @Test
    fun `deeper legacy nesting still moves into the channel folder`() {
        // The old layout wrote exactly two levels, but a user may have filed
        // things deeper by hand; the first level is the channel either way.
        assertEquals(
            "SmartTG Downloader/MC",
            migrationTargetFolder("Download/SmartTG/MC/Audio/Old/")
        )
    }

    @Test
    fun `a legacy folder name maps to the folder the new writer would use`() {
        // A channel called "MC/Bonus" was already sanitised to "MC_Bonus" when the
        // old build created its folder, so the name on disk is exactly what the
        // new writer produces for that channel — the move must land in the same
        // folder, not a second one derived from the original title.
        for (onDisk in listOf("MC", "MC_Bonus", "My Channel", "संगीत", "🎵 Music")) {
            assertEquals(
                onDisk,
                DownloadsStore.channelFolder(onDisk),
                migrationTargetFolder("Download/SmartTG/$onDisk/Audio/")
            )
        }
        assertEquals(
            "SmartTG Downloader/MC_Bonus",
            migrationTargetFolder("Download/SmartTG/MC_Bonus/Audio/")
        )
    }

    @Test
    fun `a pass with nothing to do says nothing`() {
        // A first launch with no old tree must not announce anything.
        assertNull(MigrationReport().summary())
        assertNull(MigrationReport(skipped = 4).summary())
    }

    @Test
    fun `a completed pass names the new folder and how many files moved`() {
        val summary = MigrationReport(moved = 24).summary()!!

        assertTrue(summary, summary.contains("24"))
        assertTrue(summary, summary.contains("Download/SmartTG Downloader"))
    }

    @Test
    fun `a renamed file is explained rather than left as a mystery (1)`() {
        val summary = MigrationReport(moved = 5, renamed = 2).summary()!!

        assertTrue(summary, summary.contains("2"))
        assertTrue(summary, summary.contains("new name"))
    }

    @Test
    fun `a pass that could not move anything reports where the files still are`() {
        val summary = MigrationReport(failed = 3).summary()!!

        // It must not claim anything moved, and it must name the old folder —
        // that is the whole point of speaking up at all.
        assertFalse(summary, summary.contains("Moved"))
        assertTrue(summary, summary.contains("Download/SmartTG"))
        assertTrue(summary, summary.contains("3"))
    }

    @Test
    fun `a partly finished pass reports both halves`() {
        val summary = MigrationReport(moved = 10, failed = 2).summary()!!

        assertTrue(summary, summary.contains("Moved 10"))
        assertTrue(summary, summary.contains("2 file(s) could not be moved"))
    }

    @Test
    fun `only a pass that finished is treated as complete`() {
        // Complete means "never retry". A partial pass must stay incomplete so
        // the next launch picks up the remainder.
        assertTrue(MigrationReport().complete)
        assertTrue(MigrationReport(moved = 10, skipped = 1).complete)
        assertFalse(MigrationReport(moved = 10, failed = 1).complete)
        assertFalse(MigrationReport(blocked = true).complete)
    }
}
