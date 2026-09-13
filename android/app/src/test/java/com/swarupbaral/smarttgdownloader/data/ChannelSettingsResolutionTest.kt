package com.swarupbaral.smarttgdownloader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins down the precedence between a channel's remembered preferences and the
 * app-wide defaults in Settings.
 *
 * These are plain JVM tests: the rule was extracted into
 * [resolveChannelSettings] precisely so it can be checked without a device,
 * a SharedPreferences instance or a running TDLib client.
 */
class ChannelSettingsResolutionTest {

    private val defaults = ChannelDefaults(rangeFrom = 1, rangeTo = 0, scanLimit = 500)

    private fun savedChannel(
        chatId: Long = 42L,
        filter: String = "Video",
        rangeFrom: Int = 11,
        rangeTo: Int = 40,
        scanLimit: Int = 5_000
    ) = ChannelSettings(
        chatId = chatId,
        title = "A saved channel",
        filter = filter,
        rangeFrom = rangeFrom,
        rangeTo = rangeTo,
        scanLimit = scanLimit,
        updatedAtEpochMs = 1_000L
    )

    @Test
    fun `a channel with nothing saved follows the app-wide defaults`() {
        val resolved = resolveChannelSettings(
            chatId = 42L,
            title = "New channel",
            chosenFilter = "Audio",
            saved = null,
            defaults = defaults,
            now = 7L
        )

        // Range and limit come from Settings, so a channel that has never been
        // analyzed keeps tracking those values instead of freezing at whatever
        // they were the first time it was opened.
        assertEquals(1, resolved.rangeFrom)
        assertEquals(0, resolved.rangeTo)
        assertEquals(500, resolved.scanLimit)
    }

    @Test
    fun `a channel's own values override the defaults`() {
        val resolved = resolveChannelSettings(
            chatId = 42L,
            title = "A saved channel",
            chosenFilter = "Video",
            saved = savedChannel(),
            defaults = defaults,
            now = 7L
        )

        assertEquals(11, resolved.rangeFrom)
        assertEquals(40, resolved.rangeTo)
        assertEquals(5_000, resolved.scanLimit)
    }

    @Test
    fun `the on-screen filter always wins over the saved one`() {
        val resolved = resolveChannelSettings(
            chatId = 42L,
            title = "A saved channel",
            // The user picked Audio on Home; this channel last remembered Video.
            chosenFilter = "Audio",
            saved = savedChannel(filter = "Video"),
            defaults = defaults,
            now = 7L
        )

        // A scan collects exactly one file type, and that type is chosen before
        // the link resolves. Substituting the remembered value here would run a
        // scan for a type the user did not ask for.
        assertEquals("Audio", resolved.filter)
    }

    @Test
    fun `a saved range of zero still means the whole list`() {
        val resolved = resolveChannelSettings(
            chatId = 42L,
            title = "A saved channel",
            chosenFilter = "Audio",
            saved = savedChannel(rangeFrom = 5, rangeTo = 0),
            defaults = defaults,
            now = 7L
        )

        // 0 is the sentinel for "to the end", not an absent value: falling back
        // to the default here would silently change a deliberate choice.
        assertEquals(5, resolved.rangeFrom)
        assertEquals(0, resolved.rangeTo)
    }

    @Test
    fun `resolution keeps the channel identity and stamps it as recently used`() {
        val resolved = resolveChannelSettings(
            chatId = 99L,
            title = "Pulled from TDLib",
            chosenFilter = "Docs",
            saved = null,
            defaults = defaults,
            now = 1_700_000_000_000L
        )

        assertEquals(99L, resolved.chatId)
        assertEquals("Pulled from TDLib", resolved.title)
        // Settings lists channels newest-first and drops stale ones, so the
        // timestamp has to be refreshed on every resolve.
        assertEquals(1_700_000_000_000L, resolved.updatedAtEpochMs)
    }

    @Test
    fun `resolving twice with nothing changed keeps the same values`() {
        val first = resolveChannelSettings(42L, "C", "Audio", savedChannel(), defaults, now = 1L)
        val second = resolveChannelSettings(42L, "C", "Audio", first, defaults, now = 2L)

        // The resolve is written straight back to the store, so it has to be
        // idempotent for the preferences themselves — only the timestamp moves.
        assertEquals(first.rangeFrom, second.rangeFrom)
        assertEquals(first.rangeTo, second.rangeTo)
        assertEquals(first.scanLimit, second.scanLimit)
        assertEquals(first.filter, second.filter)
        assertEquals(2L, second.updatedAtEpochMs)
    }
}
