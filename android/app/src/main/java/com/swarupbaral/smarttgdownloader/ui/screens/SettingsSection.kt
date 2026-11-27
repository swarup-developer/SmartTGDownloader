package com.swarupbaral.smarttgdownloader.ui.screens

/**
 * The group a Settings screen is showing.
 *
 * Settings used to be one long column: account, appearance, the analyze
 * question, scan limit, channels, downloads and updates all stacked in a single
 * scroll, reachable only by scrolling past the rest. The groups are the same
 * content, but each one is now a screen of its own, chosen from a row of section
 * labels at the top of Settings and from the app's menu, so nothing has to be
 * hunted for to be found.
 *
 * [key] is deliberately separate from the enum's own name: the key travels in a
 * navigation argument, and so can end up saved in a back stack, so renaming a
 * constant here must not silently break a link written by an older build.
 */
enum class SettingsSection(val key: String, val label: String) {
    ACCOUNT("account", "Account"),
    APPEARANCE("appearance", "Appearance"),

    /**
     * The analyze question and how deep a scan walks — the two decisions that
     * belong to a scan rather than to a download.
     */
    ANALYSIS("analysis", "Analysis"),

    DOWNLOADS("downloads", "Downloads"),
    CHANNELS("channels", "Channels"),
    UPDATES("updates", "Updates");

    companion object {

        /**
         * The group opened when no section was asked for.
         *
         * Account, because it is what the old single-column screen led with: a
         * returning user finds what they saw before rather than a rearranged
         * screen they have to re-learn.
         */
        val DEFAULT = ACCOUNT

        /**
         * Resolves a navigation argument to a section.
         *
         * Anything unrecognised — a missing argument, a blank string, or a key
         * written by a build that had a group this one does not — falls back to
         * [DEFAULT] rather than failing. A section argument arrives from a saved
         * back stack, which can outlive the build that wrote it, and opening
         * Settings at the wrong group is a far smaller failure than opening
         * Settings to nothing at all.
         */
        fun fromKey(key: String?): SettingsSection =
            entries.firstOrNull { it.key == key?.trim() } ?: DEFAULT
    }
}
