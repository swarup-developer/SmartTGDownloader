package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence a ZIP run ends with.
 *
 * This is the only place the app reports what happened to a selection the user
 * paid data for, so the rules are strict: never claim an archive that was not
 * written, never claim the separate files were kept when they were not, and never
 * leave a file that failed unmentioned.
 *
 * Pure calls on [ArchiveOutcome.summary]; no device needed.
 */
class ArchiveOutcomeTest {

    private fun outcome(
        fileName: String = "MC - Audio (20 files).zip",
        folder: String = "SmartTG Downloader/MC",
        packed: Int = 20,
        savedFiles: Int = 0,
        keptFiles: Boolean = false,
        failed: Int = 0,
        items: Int = 20,
        error: String? = null
    ) = ArchiveOutcome(
        fileName = fileName,
        folder = folder,
        packed = packed,
        savedFiles = savedFiles,
        keptFiles = keptFiles,
        failed = failed,
        items = items,
        error = error
    )

    @Test
    fun `a finished archive names the file, the count and the folder`() {
        val summary = outcome().summary(alsoSavedByQueue = 0)

        assertTrue(summary, summary.contains("MC - Audio (20 files).zip"))
        assertTrue(summary, summary.contains("20 file(s)"))
        assertTrue(summary, summary.contains("Download/SmartTG Downloader/MC"))
    }

    @Test
    fun `an archive-only run does not mention keeping the files`() {
        val summary = outcome().summary(alsoSavedByQueue = 0)

        assertFalse(summary, summary.contains("themselves"))
    }

    @Test
    fun `a kept-files run says the files were kept too`() {
        val summary = outcome(savedFiles = 18, keptFiles = true, packed = 18, items = 18)
            .summary(alsoSavedByQueue = 0)

        // The user asked for both, so both have to be reported.
        assertTrue(summary, summary.contains("MC - Audio (20 files).zip"))
        assertTrue(summary, summary.contains("18 file(s) themselves"))
        assertTrue(summary, summary.contains("Download/SmartTG Downloader/MC"))
    }

    @Test
    fun `a failure says which place the file is missing from`() {
        val archiveOnly = outcome(packed = 18, items = 20, failed = 2).summary(0)
        val keptToo = outcome(packed = 18, savedFiles = 18, keptFiles = true, items = 20, failed = 2)
            .summary(0)

        // With the files kept, "not in the archive" would be half the truth.
        assertTrue(archiveOnly, archiveOnly.contains("2 of 20 file(s) could not be downloaded"))
        assertTrue(archiveOnly, archiveOnly.contains("not in the archive"))
        assertTrue(keptToo, keptToo.contains("neither in the archive nor in the folder"))
    }

    @Test
    fun `an archive that could not be written is never reported as saved`() {
        val summary = outcome(
            packed = 0,
            savedFiles = 12,
            keptFiles = true,
            error = "No space left on device"
        ).summary(0)

        assertFalse(summary, summary.contains("Saved"))
        assertTrue(summary, summary.contains("could not be created"))
        assertTrue(summary, summary.contains("No space left on device"))
    }

    @Test
    fun `a failed archive says the files it did save are still there`() {
        // The point of keeping the files: a bad archive must not read as a lost
        // download, so the sentence has to say where they are.
        val summary = outcome(packed = 0, savedFiles = 12, keptFiles = true, error = "Locked")
            .summary(0)

        assertTrue(summary, summary.contains("12 file(s) that were already downloaded"))
        assertTrue(summary, summary.contains("Download/SmartTG Downloader/MC"))
    }

    @Test
    fun `a failed archive with nothing saved promises no files`() {
        val summary = outcome(packed = 0, savedFiles = 0, error = "Locked").summary(0)

        assertFalse(summary, summary.contains("already downloaded"))
    }

    @Test
    fun `a selection where nothing could be fetched is reported plainly`() {
        val summary = outcome(packed = 0, items = 20, failed = 20).summary(0)

        assertFalse(summary, summary.contains("Saved"))
        assertTrue(summary, summary.contains("none of the 20 file(s)"))
    }

    @Test
    fun `files saved by the ordinary queue in the same run are mentioned`() {
        val summary = outcome().summary(alsoSavedByQueue = 3)

        assertTrue(summary, summary.contains("3 file(s) were also saved separately"))
    }

    @Test
    fun `no summary ever claims an archive that was not written`() {
        // Swept across every shape a run can end in: a claim of a saved archive
        // must appear only when one exists.
        for (packed in 0..3) {
            for (savedFiles in 0..3) {
                for (keptFiles in listOf(false, true)) {
                    for (error in listOf<String?>(null, "No space left")) {
                        val summary = outcome(
                            packed = packed,
                            savedFiles = savedFiles,
                            keptFiles = keptFiles,
                            error = error
                        ).summary(0)
                        val wroteAnArchive = packed > 0 && error == null

                        assertEquals(
                            "packed=$packed saved=$savedFiles kept=$keptFiles error=$error -> $summary",
                            wroteAnArchive,
                            summary.startsWith("Saved ")
                        )
                    }
                }
            }
        }
    }
}
