package com.swarupbaral.smarttgdownloader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The download preferences remembered for one channel.
 *
 * Keyed by the resolved Telegram chat id, which is the only identifier that is
 * stable across the three ways a channel can be reached (a `t.me/name` link, a
 * private `t.me/c/...` link and an invite link all resolve to the same id).
 * Keying on the link text would remember the same channel several times over.
 */
data class ChannelSettings(
    val chatId: Long,
    val title: String,
    /** Category filter for this channel. */
    val filter: String,
    /** Last applied range picker values; [rangeTo] of 0 means "whole list". */
    val rangeFrom: Int,
    val rangeTo: Int,
    /** How many files an analysis of this channel collects before stopping. */
    val scanLimit: Int,
    val updatedAtEpochMs: Long
)

/**
 * The app-wide values a channel falls back to when it has nothing of its own.
 */
data class ChannelDefaults(
    val rangeFrom: Int = 1,
    /** 0 means "to the end of the list", as in [SavedRange]. */
    val rangeTo: Int = 0,
    val scanLimit: Int = AppPreferences.DEFAULT_SCAN_LIMIT
)

/**
 * Works out the settings a channel should be analyzed with.
 *
 * The precedence rule, in one place so it can be tested and reasoned about:
 *
 *  - **range and scan limit**: the channel's own values when it has any,
 *    otherwise the app-wide defaults. A channel that has never been analyzed
 *    therefore keeps following the defaults in Settings rather than being
 *    frozen at whatever they happened to be the first time it was opened.
 *  - **filter**: always [chosenFilter], i.e. what the user picked on screen.
 *    The scan collects one file type, and that type is chosen *before* the link
 *    resolves — so a remembered per-channel filter must never quietly replace a
 *    choice the user just made.
 *
 * A saved `rangeTo` of 0 is kept rather than treated as "unset": it is the
 * sentinel for "the whole list", which is a real value the user can leave.
 */
fun resolveChannelSettings(
    chatId: Long,
    title: String,
    chosenFilter: String,
    saved: ChannelSettings?,
    defaults: ChannelDefaults,
    now: Long = System.currentTimeMillis()
): ChannelSettings = ChannelSettings(
    chatId = chatId,
    title = title,
    filter = chosenFilter,
    rangeFrom = saved?.rangeFrom ?: defaults.rangeFrom,
    rangeTo = saved?.rangeTo ?: defaults.rangeTo,
    scanLimit = saved?.scanLimit ?: defaults.scanLimit,
    // Stamped on every resolve so the channel counts as recently used and can
    // be listed newest-first in Settings.
    updatedAtEpochMs = now
)

/**
 * Per-channel download preferences.
 *
 * The app-wide values in [AppPreferences] are the *defaults*; a channel only
 * gets an entry here once it has actually been analyzed. That ordering matters:
 * a music channel can stay on `Audio` while a movie channel stays on `Video`,
 * and a channel nobody has touched keeps following the global default rather
 * than being frozen at whatever the default was the first time it was opened.
 *
 * Stored as one JSON document rather than a Room table: it is a small settings
 * map, not user content, and a Room entity would force a schema version bump
 * plus a hand-written migration for something a malformed read can safely treat
 * as "no saved preferences".
 */
class ChannelPreferenceStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _channels = MutableStateFlow(readAll())

    /** All remembered channels, most recently used first. */
    val channels: StateFlow<List<ChannelSettings>> = _channels.asStateFlow()

    fun get(chatId: Long): ChannelSettings? =
        _channels.value.firstOrNull { it.chatId == chatId }

    fun save(settings: ChannelSettings) {
        val next = _channels.value
            .filter { it.chatId != settings.chatId } + settings
        write(next)
    }

    fun forget(chatId: Long) {
        write(_channels.value.filter { it.chatId != chatId })
    }

    fun forgetAll() {
        write(emptyList())
    }

    @Synchronized
    private fun write(channels: List<ChannelSettings>) {
        val sorted = channels.sortedByDescending { it.updatedAtEpochMs }
        _channels.value = sorted
        val array = JSONArray()
        for (channel in sorted) {
            array.put(
                JSONObject().apply {
                    put("chatId", channel.chatId)
                    put("title", channel.title)
                    put("filter", channel.filter)
                    put("rangeFrom", channel.rangeFrom)
                    put("rangeTo", channel.rangeTo)
                    put("scanLimit", channel.scanLimit)
                    put("updatedAt", channel.updatedAtEpochMs)
                }
            )
        }
        prefs.edit().putString(KEY_CHANNELS, array.toString()).apply()
    }

    /** Unreadable preferences degrade to "nothing remembered", never a crash. */
    private fun readAll(): List<ChannelSettings> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val chatId = item.optLong("chatId", 0L)
                if (chatId == 0L) return@mapNotNull null
                ChannelSettings(
                    chatId = chatId,
                    title = item.optString("title").ifBlank { "Telegram" },
                    filter = item.optString("filter").ifBlank { AppPreferences.CATEGORY_ALL },
                    rangeFrom = item.optInt("rangeFrom", 1).coerceAtLeast(1),
                    rangeTo = item.optInt("rangeTo", 0).coerceAtLeast(0),
                    scanLimit = item.optInt("scanLimit", AppPreferences.DEFAULT_SCAN_LIMIT)
                        .coerceAtLeast(0),
                    updatedAtEpochMs = item.optLong("updatedAt", 0L)
                )
            }
                .sortedByDescending { it.updatedAtEpochMs }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS_FILE = "smarttg_channel_prefs"
        const val KEY_CHANNELS = "channels"
    }
}
