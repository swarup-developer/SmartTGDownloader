package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the download panel's range combobox says it currently holds.
 *
 * The value is worth pinning down because the control and the custom fields are
 * two views of one range: the combobox names the last applied range while the
 * fields pre-fill from it. If the two disagree — the combobox claiming "Newest
 * 100 files" while the fields hold 1 to 30 — the user is shown one thing and
 * given another, and the download that follows is not the one that was
 * described.
 *
 * The other trap is [SavedRange.to] of 0, which is the sentinel for "no range
 * applied", not a stored number. Read literally it would describe an empty
 * selection on a list the user has not chosen anything from yet.
 *
 * Pure calls on [rangeChoiceLabel]; no device needed.
 */
class RangeChoiceLabelTest {

    @Test
    fun `a range that has never been applied shows the prompt`() {
        // The default is exactly this: 1 to 0, meaning "the whole list", which is
        // not a range anyone chose.
        assertEquals(RANGE_CHOICE_PROMPT, rangeChoiceLabel(SavedRange(), 200))
        assertEquals(RANGE_CHOICE_PROMPT, rangeChoiceLabel(SavedRange(from = 5, to = 0), 200))
    }

    @Test
    fun `an empty result list shows the prompt`() {
        // There is nothing to have a range of, so any remembered range describes
        // a list that is not on screen.
        assertEquals(RANGE_CHOICE_PROMPT, rangeChoiceLabel(SavedRange(1, 20), 0))
    }

    @Test
    fun `a preset range is named the way the preset is offered`() {
        // The wording has to match the menu entry, or the value looks like a
        // different choice from the one that produced it.
        for (preset in AppPreferences.RANGE_PRESETS) {
            assertEquals("Newest $preset files", rangeChoiceLabel(SavedRange(1, preset), 500))
        }
    }

    @Test
    fun `a custom range names both ends`() {
        assertEquals("Files 5 to 40", rangeChoiceLabel(SavedRange(5, 40), 200))
    }

    @Test
    fun `a range remembered from a bigger channel is clamped onto this list`() {
        // "Newest 100" from a 5,000-file channel, now looking at 30 files. The
        // fields will hold 1 to 30, so the combobox must say the same — and it
        // is still the newest 30, not a range the user typed.
        assertEquals("Newest 30 files", rangeChoiceLabel(SavedRange(1, 100), 30))
    }

    @Test
    fun `a range that starts past this list is clamped too`() {
        assertEquals("File 12", rangeChoiceLabel(SavedRange(50, 80), 12))
    }

    @Test
    fun `a one-file range is named in the singular`() {
        assertEquals("Newest 1 file", rangeChoiceLabel(SavedRange(1, 1), 200))
        assertEquals("Newest 1 file", rangeChoiceLabel(SavedRange(1, 100), 1))
        assertEquals("File 7", rangeChoiceLabel(SavedRange(7, 7), 20))
    }
}
