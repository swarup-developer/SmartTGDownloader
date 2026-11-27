package com.swarupbaral.smarttgdownloader.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a requested Settings group into the group that opens.
 *
 * The key arrives from the app menu, and a key can also arrive from a saved back
 * stack written by an older build. That makes the lookup the one place in the
 * screen that has to survive input it did not produce: a group renamed, removed
 * or never written at all must land somewhere sensible instead of opening
 * Settings with nothing in it.
 *
 * Pure calls on [SettingsSection.fromKey]; no device needed.
 */
class SettingsSectionTest {

    @Test
    fun `every section's own key opens that section`() {
        for (section in SettingsSection.entries) {
            assertEquals(section, SettingsSection.fromKey(section.key))
        }
    }

    @Test
    fun `an unknown key falls back to the default section`() {
        assertEquals(SettingsSection.DEFAULT, SettingsSection.fromKey("downloadz"))
        assertEquals(SettingsSection.DEFAULT, SettingsSection.fromKey("Download"))
    }

    @Test
    fun `a missing or blank key falls back to the default section`() {
        assertEquals(SettingsSection.DEFAULT, SettingsSection.fromKey(null))
        assertEquals(SettingsSection.DEFAULT, SettingsSection.fromKey(""))
        assertEquals(SettingsSection.DEFAULT, SettingsSection.fromKey("   "))
    }

    @Test
    fun `surrounding whitespace does not turn a real key into a fallback`() {
        assertEquals(SettingsSection.DOWNLOADS, SettingsSection.fromKey(" downloads "))
    }

    @Test
    fun `the default is a real section`() {
        // The fallback has to be something the screen can draw: a default that is
        // not in the list would open Settings on an empty pane.
        assertTrue(SettingsSection.DEFAULT in SettingsSection.entries)
    }

    @Test
    fun `keys are unique, non-blank and free of spaces`() {
        // Two sections sharing a key would silently make one of them
        // unreachable from the menu; a key with a space would not survive being
        // put in a URL-like argument.
        val keys = SettingsSection.entries.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.all { it.isNotBlank() && !it.contains(' ') })
    }
}
