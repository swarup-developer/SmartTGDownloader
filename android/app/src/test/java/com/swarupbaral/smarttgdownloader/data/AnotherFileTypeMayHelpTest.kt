package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether an empty scan is one a different file type could fix.
 *
 * This gate decides whether the results panel offers *Change type*. Getting it
 * wrong in the permissive direction is the worse failure: after a scan for `All`
 * every media type has already been looked for, so a *Change type* button there
 * would invite the user to change something and watch the same empty list come
 * back — the app suggesting a fix it knows cannot work.
 *
 * Pure calls on [anotherFileTypeMayHelp]; no device needed.
 */
class AnotherFileTypeMayHelpTest {

    @Test
    fun `an All scan leaves no other type to try`() {
        // Every media type was already collected, however much sits in the channel.
        assertFalse(anotherFileTypeMayHelp(AppPreferences.CATEGORY_ALL, 500))
        assertFalse(anotherFileTypeMayHelp(AppPreferences.CATEGORY_ALL, 0))
    }

    @Test
    fun `a typed scan that passed over other media can be retyped`() {
        for (category in AppPreferences.MEDIA_CATEGORIES) {
            assertTrue(anotherFileTypeMayHelp(category, 1))
        }
    }

    @Test
    fun `a typed scan that passed over nothing offers no type change`() {
        // The walk saw no other media, so the channel has nothing of any type —
        // another type would find exactly as little.
        for (category in AppPreferences.MEDIA_CATEGORIES) {
            assertFalse(anotherFileTypeMayHelp(category, 0))
        }
    }

    @Test
    fun `the media categories are the ones this rule expects to be retypeable`() {
        // The rule treats everything that is not All as a type that can be tried.
        // If a sentinel were ever added to the list, this would fail rather than
        // silently offer a type change for something that is not a type.
        assertFalse(AppPreferences.CATEGORY_ALL in AppPreferences.MEDIA_CATEGORIES)
        assertTrue(AppPreferences.MEDIA_CATEGORIES.isNotEmpty())
    }
}
