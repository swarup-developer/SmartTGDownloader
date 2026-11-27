package com.swarupbaral.smarttgdownloader.data

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Setting one channel's scan limit from Settings → Channels.
 *
 * Three rules, all pure: which limits a channel is offered, how those choices
 * read, and what changing one actually writes. The last is the one with teeth —
 * a rewrite that looks harmless would either re-stamp the channel and reorder the
 * list the user is working in, or do nothing at all while the row above it already
 * claims the new value.
 */
class ChannelScanLimitSettingTest {

    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        // scanLimitLabel groups digits using the default locale.
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun channel(limit: Int, updatedAt: Long = 1_000L) = ChannelSettings(
        chatId = -1001234567890L,
        title = "MC",
        filter = AppPreferences.MEDIA_CATEGORIES.first(),
        rangeFrom = 1,
        rangeTo = 50,
        scanLimit = limit,
        updatedAtEpochMs = updatedAt
    )

    // --- Which limits are offered -------------------------------------------

    @Test
    fun `a channel on the ladder is offered the ladder`() {
        for (option in AppPreferences.SCAN_LIMIT_OPTIONS) {
            assertEquals(
                "channel on $option got a different list",
                AppPreferences.SCAN_LIMIT_OPTIONS,
                scanLimitChoicesFor(option)
            )
        }
    }

    @Test
    fun `a limit that is not a step is offered alongside them`() {
        // Written by an older build, or by a release whose ladder has changed.
        assertEquals(
            listOf(100, 250, 500, 1_000, 5_000, 0),
            scanLimitChoicesFor(250)
        )
        assertEquals(
            listOf(100, 500, 1_000, 5_000, 5_001, 0),
            scanLimitChoicesFor(5_001)
        )
        // Below the first step, so it heads the list.
        assertEquals(
            listOf(50, 100, 500, 1_000, 5_000, 0),
            scanLimitChoicesFor(50)
        )
    }

    @Test
    fun `unlimited and nonsense values all fall to the ladder's All step`() {
        for (value in listOf(0, -1, -5_000)) {
            assertEquals(
                "value $value did not fall back to the ladder",
                AppPreferences.SCAN_LIMIT_OPTIONS,
                scanLimitChoicesFor(value)
            )
        }
    }

    @Test
    fun `the limit in force is always among the choices`() {
        val probes = AppPreferences.SCAN_LIMIT_OPTIONS +
            listOf(-1, 0, 1, 99, 101, 250, 4_999, 5_001, 1_000_000)
        for (current in probes) {
            val choices = scanLimitChoicesFor(current)
            val expected = if (current <= 0) 0 else current
            assertTrue(
                "limit $current is not offered by its own control",
                expected in choices
            )
            // No step may be dropped or duplicated by adding one.
            assertEquals(choices.size, choices.toSet().size)
            // Ascending, except that All is the last resort rather than the
            // smallest number — sorting it into first place would reorder the
            // ladder the chips are read from.
            assertEquals(choices.filter { it > 0 }.sorted(), choices.filter { it > 0 })
        }
    }

    @Test
    fun `All stays last even when an extra step is added`() {
        // The order is the ladder's, and the unbounded step is the last resort in
        // that ordering — an inserted number must not push it into the middle.
        for (current in listOf(50, 250, 5_001)) {
            val choices = scanLimitChoicesFor(current)
            assertEquals(0, choices.last())
            // Exactly one unbounded entry: an inserted number must not duplicate it.
            assertEquals(1, choices.count { it <= 0 })
        }
    }

    @Test
    fun `every choice reads as a number of files, or as All files`() {
        assertEquals("100 files", scanLimitChoiceLabel(100))
        assertEquals("1,000 files", scanLimitChoiceLabel(1_000))
        assertEquals("5,000 files", scanLimitChoiceLabel(5_000))
        assertEquals("All files", scanLimitChoiceLabel(0))
        assertEquals("All files", scanLimitChoiceLabel(-1))
    }

    // --- What changing one writes -------------------------------------------

    @Test
    fun `changing the limit keeps everything else about the channel`() {
        val updated = channel(500).withScanLimit(5_000)
        assertEquals(5_000, updated!!.scanLimit)
        assertEquals(-1001234567890L, updated.chatId)
        assertEquals("MC", updated.title)
        assertEquals(AppPreferences.MEDIA_CATEGORIES.first(), updated.filter)
        assertEquals(1, updated.rangeFrom)
        assertEquals(50, updated.rangeTo)
    }

    @Test
    fun `re-choosing the limit in force writes nothing`() {
        // Null is what keeps the store untouched: re-stamping the channel would
        // move its row to the top of the Channels list while the user is in it.
        assertNull(channel(500).withScanLimit(500))
        assertNull(channel(0).withScanLimit(0))
        // A negative is the All step, so it is not a change either.
        assertNull(channel(0).withScanLimit(-5))
    }

    @Test
    fun `an edited channel keeps its place in the list`() {
        val before = channel(500, updatedAt = 7_777L)
        val after = before.withScanLimit(1_000)!!
        // Same stamp, so the newest-first ordering cannot move the row being
        // edited out from under the user.
        assertEquals(before.updatedAtEpochMs, after.updatedAtEpochMs)
    }

    @Test
    fun `a negative limit degrades to unlimited rather than below every step`() {
        assertEquals(0, channel(500).withScanLimit(-1)!!.scanLimit)
    }

    @Test
    fun `the channel handed in is left alone`() {
        // The store keeps the entry it read in its flow; editing a copy is what
        // keeps a rejected or unchanged write from being felt elsewhere.
        val original = channel(500)
        val updated = original.withScanLimit(100)!!
        assertNotSame(original, updated)
        assertEquals(500, original.scanLimit)
        assertEquals(100, updated.scanLimit)
    }
}
