package com.swarupbaral.smarttgdownloader.data

import com.swarupbaral.smarttgdownloader.data.ZipPacker.Entry
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The archive the "Download ZIP" action produces.
 *
 * Naming is the part that fails quietly: a duplicate name loses a file on
 * extraction, and a name containing a path separator is a zip-slip hazard in any
 * extractor that joins entry names onto a destination folder. Both are decided by
 * pure functions, so both are tested here — and [ZipPacker.write] is exercised for
 * real against `java.util.zip`, which needs no device.
 */
class ZipPackerTest {

    @Test
    fun `the first file keeps its own name`() {
        val used = mutableSetOf<String>()

        assertEquals("track.mp3", ZipPacker.uniqueEntryName("track.mp3", used))
    }

    @Test
    fun `two files with the same name both survive`() {
        val used = mutableSetOf<String>()
        val first = ZipPacker.uniqueEntryName("track.mp3", used)
        val second = ZipPacker.uniqueEntryName("track.mp3", used)
        val third = ZipPacker.uniqueEntryName("track.mp3", used)

        assertEquals("track.mp3", first)
        assertEquals("track (2).mp3", second)
        assertEquals("track (3).mp3", third)
    }

    @Test
    fun `a name with no extension still gets numbered`() {
        val used = mutableSetOf<String>()

        ZipPacker.uniqueEntryName("README", used)

        assertEquals("README (2)", ZipPacker.uniqueEntryName("README", used))
    }

    @Test
    fun `names differing only by case are treated as the same file`() {
        // The filesystem the archive is extracted onto may not tell them apart,
        // so two entries would collide there even though they do not here. The
        // numbered name keeps the second file's own spelling: it is that file's
        // name, not the first one's.
        val used = mutableSetOf<String>()
        ZipPacker.uniqueEntryName("Track.mp3", used)

        assertEquals("track (2).mp3", ZipPacker.uniqueEntryName("track.mp3", used))
    }

    @Test
    fun `a name with a path separator cannot escape the extraction folder`() {
        val used = mutableSetOf<String>()
        val name = ZipPacker.uniqueEntryName("../../etc/passwd", used)

        assertFalse(name, name.contains('/'))
        assertFalse(name, name.contains('\\'))
    }

    @Test
    fun `a name made only of dots is not a name`() {
        val used = mutableSetOf<String>()

        assertEquals("file", ZipPacker.uniqueEntryName("..", used))
        assertEquals("file (2)", ZipPacker.uniqueEntryName(".", used))
    }

    @Test
    fun `a blank name still produces an entry`() {
        val used = mutableSetOf<String>()

        assertEquals("file", ZipPacker.uniqueEntryName("   ", used))
    }

    @Test
    fun `a written archive reads back with the files inside it`() {
        withTempDir { dir ->
            val first = File(dir, "a.txt").apply { writeText("hello") }
            val second = File(dir, "b.txt").apply { writeText("world") }
            val archive = File(dir, "out.zip")

            val written = ZipPacker.write(
                archive.outputStream(),
                listOf(Entry("a.txt", first), Entry("b.txt", second))
            )

            assertEquals(2, written)
            ZipFile(archive).use { zip ->
                assertEquals(
                    listOf("a.txt", "b.txt"),
                    zip.entries().toList().map { it.name }
                )
                assertEquals("hello", zip.readText("a.txt"))
                assertEquals("world", zip.readText("b.txt"))
            }
        }
    }

    @Test
    fun `duplicate names in one archive both end up in it`() {
        withTempDir { dir ->
            val first = File(dir, "one.mp3").apply { writeText("first") }
            val second = File(dir, "two.mp3").apply { writeText("second") }
            val archive = File(dir, "out.zip")

            ZipPacker.write(
                archive.outputStream(),
                listOf(Entry("track.mp3", first), Entry("track.mp3", second))
            )

            ZipFile(archive).use { zip ->
                assertEquals(
                    listOf("track.mp3", "track (2).mp3"),
                    zip.entries().toList().map { it.name }
                )
                assertEquals("first", zip.readText("track.mp3"))
                assertEquals("second", zip.readText("track (2).mp3"))
            }
        }
    }

    @Test
    fun `a file that vanished is skipped rather than added empty`() {
        withTempDir { dir ->
            val present = File(dir, "here.txt").apply { writeText("here") }
            val missing = File(dir, "gone.txt")
            val archive = File(dir, "out.zip")
            val reported = mutableListOf<String>()

            val written = ZipPacker.write(
                archive.outputStream(),
                listOf(Entry("gone.txt", missing), Entry("here.txt", present)),
                onEntryWritten = { reported += it.name }
            )

            // Skipped, not silently padded with an empty file — and the caller is
            // told how many really went in.
            assertEquals(1, written)
            assertEquals(listOf("here.txt"), reported)
            ZipFile(archive).use { zip ->
                assertEquals(listOf("here.txt"), zip.entries().toList().map { it.name })
            }
        }
    }

    private fun ZipFile.readText(name: String): String =
        getInputStream(getEntry(name)).bufferedReader().use { it.readText() }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ziptest").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
