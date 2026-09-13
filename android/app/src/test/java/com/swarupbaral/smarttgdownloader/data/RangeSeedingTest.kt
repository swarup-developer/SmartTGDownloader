package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The range picker's pre-fill rule.
 *
 * A remembered range outlives the list it was chosen from, so it has to be
 * clamped onto the list actually on screen. Everything here is a pure function
 * call on [SavedRange.seededFor]; the picker only renders what it returns.
 */
class RangeSeedingTest {

    @Test
    fun `a remembered range that fits the list is kept exactly`() {
        val seeded = SavedRange(from = 1, to = 20).seededFor(totalFiles = 200)

        assertEquals(SavedRange(from = 1, to = 20), seeded)
    }

    @Test
    fun `a range carried over from a bigger channel is clamped to this list`() {
        // 1–500 remembered, but this channel only produced 20 files.
        val seeded = SavedRange(from = 1, to = 500).seededFor(totalFiles = 20)

        assertEquals(SavedRange(from = 1, to = 20), seeded)
    }

    @Test
    fun `a start beyond the end of the list is pulled back into range`() {
        val seeded = SavedRange(from = 40, to = 60).seededFor(totalFiles = 20)

        // Clamping from AND to to the ceiling keeps the pair ordered, so the
        // picker can never pre-fill something it then rejects.
        assertEquals(SavedRange(from = 20, to = 20), seeded)
    }

    @Test
    fun `zero as the end follows the list rather than being clamped from zero`() {
        val seeded = SavedRange(from = 5, to = 0).seededFor(totalFiles = 37)

        // 0 means "to the end", so it must track the list size.
        assertEquals(SavedRange(from = 5, to = 37), seeded)
    }

    @Test
    fun `an inverted remembered range comes back ordered`() {
        val seeded = SavedRange(from = 100, to = 50).seededFor(totalFiles = 200)

        // from is fitted first, then to into from..total.
        assertEquals(SavedRange(from = 100, to = 100), seeded)
    }

    @Test
    fun `a number below one is raised to the first file`() {
        val seeded = SavedRange(from = 0, to = 5).seededFor(totalFiles = 10)

        assertEquals(SavedRange(from = 1, to = 5), seeded)
    }

    @Test
    fun `values below one never reach the picker`() {
        // Both stores sanitise to >= 1 / >= 0 on read, so negatives only ever
        // arrive from a hand-edited prefs file — and then they behave like the
        // sentinels they are: a start below 1 is raised, and a non-positive end
        // means "the whole list".
        assertEquals(SavedRange(from = 1, to = 10), SavedRange(-5, -1).seededFor(totalFiles = 10))
        assertEquals(SavedRange(from = 1, to = 10), SavedRange(-5, 0).seededFor(totalFiles = 10))
    }

    @Test
    fun `an empty result set still seeds a single usable file`() {
        // The picker's fields are never left empty or at "0 to 0", which would
        // fail its own validation on the first tap.
        assertEquals(SavedRange(from = 1, to = 1), SavedRange(1, 0).seededFor(totalFiles = 0))
        assertEquals(SavedRange(from = 1, to = 1), SavedRange(3, 9).seededFor(totalFiles = 0))
    }

    @Test
    fun `every remembered range seeds to something the picker accepts`() {
        val totals = listOf(0, 1, 2, 5, 20, 200, 50_000)
        val froms = listOf(-10, 0, 1, 2, 19, 20, 21, 1_000)
        val tos = listOf(-1, 0, 1, 2, 19, 20, 21, 1_000)

        // The invariant the UI depends on, swept rather than spot-checked: the
        // seeded pair is always ordered and always inside the list.
        for (total in totals) {
            for (from in froms) {
                for (to in tos) {
                    val seeded = SavedRange(from, to).seededFor(total)
                    val ceiling = maxOf(total, 1)
                    assertTrue(
                        "from ${seeded.from} for total $total (saved $from–$to)",
                        seeded.from >= 1 && seeded.from <= ceiling
                    )
                    assertTrue(
                        "to ${seeded.to} for total $total (saved $from–$to)",
                        seeded.to >= seeded.from && seeded.to <= ceiling
                    )
                }
            }
        }
    }

    @Test
    fun `clamping is stable when applied twice`() {
        val once = SavedRange(from = 1, to = 500).seededFor(totalFiles = 30)
        val twice = once.seededFor(totalFiles = 30)

        // The picker re-seeds whenever the list size changes; re-seeding to the
        // same size must not keep shrinking the range.
        assertEquals(once, twice)
    }
}
