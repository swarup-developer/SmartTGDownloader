package com.swarupbaral.smarttgdownloader.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds one .zip archive out of files TDLib has already fetched.
 *
 * Kept apart from [DownloadManager] and free of any Android API so the archive
 * rules can be tested without a device: what a file is called *inside* the
 * archive is the part that goes wrong silently, long after the download that
 * produced it.
 */
object ZipPacker {

    /** One file on its way into the archive. */
    data class Entry(val name: String, val source: File)

    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Writes [entries] into [output] as a single .zip, calling [onEntryWritten]
     * after each file so progress can be reported.
     *
     * @return how many files were written.
     */
    fun write(
        output: OutputStream,
        entries: List<Entry>,
        onEntryWritten: (Entry) -> Unit = {}
    ): Int {
        var written = 0
        ZipOutputStream(BufferedOutputStream(output, BUFFER_BYTES)).use { zip ->
            // Media is already compressed, so the fast level keeps the CPU cost
            // down for almost the same archive size; documents still shrink.
            zip.setLevel(Deflater.BEST_SPEED)
            val used = mutableSetOf<String>()
            for (entry in entries) {
                // A cached file can be gone by the time we get to it (TDLib
                // evicts under pressure). Skipping is right: an empty file in the
                // archive would be worse than a reported omission.
                if (!entry.source.isFile) continue
                zip.putNextEntry(ZipEntry(uniqueEntryName(entry.name, used)))
                entry.source.inputStream().use { input ->
                    input.copyTo(zip, BUFFER_BYTES)
                }
                zip.closeEntry()
                written++
                onEntryWritten(entry)
            }
        }
        return written
    }

    /**
     * The name this file gets inside the archive.
     *
     * Two rules, both about what happens once the archive is somewhere else:
     *
     *  - **Safe to extract.** A path separator in an entry name is a zip-slip
     *    hazard for any extractor that joins the name onto a destination folder,
     *    and Telegram file names can contain one; so separators become
     *    underscores, and a name made only of dots (a path reference, not a name)
     *    is replaced.
     *  - **Every file survives extraction.** Two files can share a name — the
     *    same track posted twice — so a repeat is numbered. Case is ignored when
     *    deciding that, because the filesystem the archive is extracted onto may
     *    not distinguish `Track.mp3` from `track.mp3`.
     *
     * [used] carries the names already handed out, lowercased.
     */
    fun uniqueEntryName(name: String, used: MutableSet<String>): String {
        val safe = name
            .replace(Regex("[/\\\\]"), "_")
            .trim()
            .takeIf { candidate -> candidate.isNotBlank() && !candidate.all { it == '.' } }
            ?: "file"
        var candidate = safe
        var index = 2
        while (!used.add(candidate.lowercase())) {
            candidate = withSuffix(safe, index)
            index++
        }
        return candidate
    }

    /** `track.mp3` at index 2 becomes `track (2).mp3`, matching MediaStore's habit. */
    private fun withSuffix(name: String, index: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) {
            "$name ($index)"
        } else {
            "${name.substring(0, dot)} ($index)${name.substring(dot)}"
        }
    }
}
