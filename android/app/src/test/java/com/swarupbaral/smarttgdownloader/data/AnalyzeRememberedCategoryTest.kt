package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type a scan uses when the analyze question is skipped.
 *
 * This value is chosen with no dialog in front of the user, so nothing on screen
 * explains it. Getting it wrong is therefore invisible in the worst way: the scan
 * collects files nothing matches and reports the channel as empty, which sends
 * the user hunting for a problem the channel does not have.
 *
 * Pure calls on [rememberedAnalyzeCategory]; no device needed.
 */
class AnalyzeRememberedCategoryTest {

    @Test
    fun `a type this build knows about is used as it stands`() {
        for (category in AppPreferences.DOWNLOAD_CATEGORIES) {
            assertEquals(category, rememberedAnalyzeCategory(category))
        }
    }

    @Test
    fun `an unknown type falls back to All instead of being passed to the scan`() {
        // A preference outlives the build that wrote it, so a type removed or
        // renamed later arrives here as a value no file can match.
        assertEquals(AppPreferences.CATEGORY_ALL, rememberedAnalyzeCategory("Audiobooks"))
        assertEquals(AppPreferences.CATEGORY_ALL, rememberedAnalyzeCategory("audio"))
    }

    @Test
    fun `a missing or blank type falls back to All`() {
        assertEquals(AppPreferences.CATEGORY_ALL, rememberedAnalyzeCategory(null))
        assertEquals(AppPreferences.CATEGORY_ALL, rememberedAnalyzeCategory(""))
        assertEquals(AppPreferences.CATEGORY_ALL, rememberedAnalyzeCategory("   "))
    }

    @Test
    fun `surrounding whitespace does not turn a real type into a fallback`() {
        assertEquals("Audio", rememberedAnalyzeCategory(" Audio "))
    }

    @Test
    fun `the fallback is the sentinel that excludes nothing`() {
        // The fallback has to be the value the scan reads as "every type". Any
        // media category here would silently filter out the very files the user
        // asked for, and an unrecognised one would exclude everything.
        val fallback = rememberedAnalyzeCategory(null)

        assertEquals(AppPreferences.CATEGORY_ALL, fallback)
        assertFalse(
            "All is the \"every type\" sentinel, not one of the types",
            fallback in AppPreferences.MEDIA_CATEGORIES
        )
        assertTrue(AppPreferences.MEDIA_CATEGORIES.isNotEmpty())
    }
}
