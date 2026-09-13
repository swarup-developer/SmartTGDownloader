package com.swarupbaral.smarttgdownloader.data

/**
 * What one ZIP run produced: the archive, any separate files kept alongside it,
 * and whatever did not make it.
 *
 * Kept out of `DownloadManager` so the sentence it produces can be tested without
 * a device. This is the one place the app reports what happened to a selection the
 * user paid data for, and a sentence that overstates it is worse than no sentence
 * at all.
 */
data class ArchiveOutcome(
    val fileName: String,
    val folder: String,
    /** Files that made it into the archive. */
    val packed: Int,
    /** Files published individually as well, when the user asked to keep them. */
    val savedFiles: Int,
    /** True when the separate files were kept too. */
    val keptFiles: Boolean,
    /** Files that could not be downloaded, so they are in neither place. */
    val failed: Int,
    /** How many files the selection asked for. */
    val items: Int,
    /** Why the archive itself could not be written, or null when it was. */
    val error: String?
) {
    /**
     * The sentence the user reads at the end.
     *
     * Never claims an archive exists unless one does, never claims the separate
     * files were kept when they were not, and never hides a file that did not
     * arrive: "a ZIP of your 20 files" with two missing would be a quiet lie
     * about the user's own data.
     *
     * [alsoSavedByQueue] counts files saved separately by the ordinary download
     * path in the same run — a batch that was queued before the archive was asked
     * for.
     */
    fun summary(alsoSavedByQueue: Int): String = buildString {
        when {
            error != null -> {
                append("The ZIP could not be created ($error).")
                if (savedFiles > 0) {
                    // The files are still there — say so, rather than leaving the
                    // user to wonder whether a failed archive took them with it.
                    append(" The $savedFiles file(s) that were already downloaded are in ")
                    append("${DownloadsStore.displayPath(folder)}.")
                }
            }
            packed == 0 -> {
                append("The ZIP could not be created — none of the ")
                append("$items file(s) could be downloaded.")
            }
            else -> {
                append("Saved $fileName ($packed file(s))")
                if (keptFiles) append(" and the $savedFiles file(s) themselves")
                append(" to ${DownloadsStore.displayPath(folder)}.")
                if (failed > 0) {
                    append(" $failed of $items file(s) could not be downloaded, so they are ")
                    append(
                        if (keptFiles) {
                            "neither in the archive nor in the folder."
                        } else {
                            "not in the archive."
                        }
                    )
                }
            }
        }
        if (alsoSavedByQueue > 0) {
            append(" $alsoSavedByQueue file(s) were also saved separately.")
        }
    }
}
