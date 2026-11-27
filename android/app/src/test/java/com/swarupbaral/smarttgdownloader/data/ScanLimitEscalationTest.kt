package com.swarupbaral.smarttgdownloader.data

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The "Search deeper" escalation offered when a scan stops at a channel's
 * limit, and the labels Settings and the results panel render for it.
 *
 * Each step has to move, and the ladder has to end at unlimited rather than
 * wrapping around — a button that quietly re-selects the current value looks
 * like it worked while changing nothing.
 */
class ScanLimitEscalationTest {

    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        // scanLimitLabel groups digits using the default locale, so pin it to
        // make the expected strings deterministic.
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `escalation climbs the configured ladder one step at a time`() {
        assertEquals(500, AppPreferences.nextScanLimit(100))
        assertEquals(1_000, AppPreferences.nextScanLimit(500))
        assertEquals(5_000, AppPreferences.nextScanLimit(1_000))
        // 0 is "walk the whole channel" — the top of the ladder.
        assertEquals(0, AppPreferences.nextScanLimit(5_000))
    }

    @Test
    fun `unlimited has nothing left to escalate to`() {
        // Null is what hides the button; returning 0 here would offer to
        // "search deeper" into unlimited, from unlimited.
        assertNull(AppPreferences.nextScanLimit(0))
    }

    @Test
    fun `a value that is not on the ladder escalates to the next step above it`() {
        // e.g. a channel saved 250 by hand, or holding a value from an older
        // build whose ladder has since changed.
        assertEquals(500, AppPreferences.nextScanLimit(250))
        assertEquals(1_000, AppPreferences.nextScanLimit(501))
        assertEquals(5_000, AppPreferences.nextScanLimit(4_999))
    }

    @Test
    fun `anything above the top step escalates to unlimited`() {
        assertEquals(0, AppPreferences.nextScanLimit(5_001))
        assertEquals(0, AppPreferences.nextScanLimit(50_000))
    }

    @Test
    fun `escalation always moves forward, or ends`() {
        val probes = AppPreferences.SCAN_LIMIT_OPTIONS +
            listOf(-1, 0, 1, 99, 101, 250, 4_000, 5_001, 1_000_000)

        for (current in probes) {
            val next = AppPreferences.nextScanLimit(current) ?: continue
            assertNotEquals("nextScanLimit($current) did not move", current, next)
            if (current > 0 && next > 0) {
                assertTrue(
                    "nextScanLimit($current) = $next is not above it",
                    next > current
                )
            }
        }
    }

    @Test
    fun `never escalates to a value that is already consumed`() {
        // The top of the ladder is 0, and no step may wrap back to the smallest.
        for (option in AppPreferences.SCAN_LIMIT_OPTIONS) {
            val next = AppPreferences.nextScanLimit(option)
            if (next != null && next != 0) {
                assertTrue(next in AppPreferences.SCAN_LIMIT_OPTIONS)
                assertTrue(next > option)
            }
        }
    }

    @Test
    fun `labels separate thousands and name unlimited`() {
        assertEquals("100", AppPreferences.scanLimitLabel(100))
        assertEquals("500", AppPreferences.scanLimitLabel(500))
        assertEquals("1,000", AppPreferences.scanLimitLabel(1_000))
        assertEquals("5,000", AppPreferences.scanLimitLabel(5_000))
        assertEquals("All", AppPreferences.scanLimitLabel(0))
        // Defensive: a negative can only arrive from a hand-edited store, and
        // the label must still read as unlimited rather than as "-5".
        assertEquals("All", AppPreferences.scanLimitLabel(-5))
    }

    @Test
    fun `the default limit is on the ladder`() {
        // A default that can never escalate would make "Search deeper" a no-op
        // on every channel that has never been analyzed.
        assertTrue(AppPreferences.DEFAULT_SCAN_LIMIT in AppPreferences.SCAN_LIMIT_OPTIONS)
        assertTrue(AppPreferences.nextScanLimit(AppPreferences.DEFAULT_SCAN_LIMIT) != null)
    }

    @Test
    fun `the range presets all sit on the ladder of selectable values`() {
        // Presets and limits are separate concerns, but both must be positive
        // and ordering assumptions in the UI depend on them being sorted.
        assertTrue(AppPreferences.RANGE_PRESETS.all { it > 0 })
        assertEquals(
            AppPreferences.RANGE_PRESETS.sorted(),
            AppPreferences.RANGE_PRESETS
        )
        assertEquals(
            AppPreferences.SCAN_LIMIT_OPTIONS.filter { it > 0 }.sorted(),
            AppPreferences.SCAN_LIMIT_OPTIONS.filter { it > 0 }
        )
    }

    @Test
    fun `the deeper action names the limit it will give, not the one in force`() {
        // The number in the label is the promise the button makes. Naming the
        // limit already in force would describe what the user has and say nothing
        // about what pressing it buys.
        assertEquals("Search deeper (500 files)", searchDeeperActionLabel(100))
        assertEquals("Search deeper (1,000 files)", searchDeeperActionLabel(500))
        assertEquals("Search deeper (5,000 files)", searchDeeperActionLabel(1_000))
    }

    @Test
    fun `the last step warns that it is the unbounded one`() {
        // There is no number to promise once the limit is gone, and "Search the
        // whole channel" is the sentence that says so. A label reading
        // "Search deeper (0 files)" would read as a downgrade.
        assertEquals("Search the whole channel", searchDeeperActionLabel(5_000))
        assertEquals("Search the whole channel", searchDeeperActionLabel(50_000))
    }

    @Test
    fun `an unlimited scan has no deeper action at all`() {
        // Null is what hides the button, on both surfaces. A label here would
        // offer to search deeper into a scan that already read the whole channel.
        assertNull(searchDeeperActionLabel(0))
        assertNull(searchDeeperActionLabel(-5))
    }

    @Test
    fun `the action appears exactly when there is a step left to take`() {
        // The button and the ladder must agree: an action shown where the ladder
        // has run out would silently re-select the current limit and rescan the
        // same depth, which looks like it worked.
        val probes = AppPreferences.SCAN_LIMIT_OPTIONS + listOf(-1, 101, 250, 4_999, 50_000)
        for (current in probes) {
            assertEquals(
                "action presence for $current disagrees with the ladder",
                AppPreferences.nextScanLimit(current) != null,
                searchDeeperActionLabel(current) != null
            )
        }
    }

    @Test
    fun `the label never repeats the limit already in force`() {
        for (current in AppPreferences.SCAN_LIMIT_OPTIONS + listOf(250, 501, 4_999)) {
            val label = searchDeeperActionLabel(current) ?: continue
            val next = AppPreferences.nextScanLimit(current)
                ?: throw AssertionError("label for $current without a next step")
            if (next > 0) {
                // A numbered step must promise the number it will give...
                assertTrue(
                    "label for $current (\"$label\") does not name the next limit",
                    label.contains(AppPreferences.scanLimitLabel(next))
                )
            } else {
                // ...and the last step, which has no number to give, must not
                // borrow one. "Search deeper (All files)" would be nonsense.
                assertEquals("Search the whole channel", label)
            }
            if (current > 0) {
                assertNotEquals(
                    "label for $current (\"$label\") names the limit in force",
                    "Search deeper (${AppPreferences.scanLimitLabel(current)} files)",
                    label
                )
            }
        }
    }
}
