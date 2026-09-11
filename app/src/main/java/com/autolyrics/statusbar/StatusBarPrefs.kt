package com.autolyrics.statusbar

import android.content.Context
import android.content.SharedPreferences

/**
 * Build mode: how the current lyric line is chosen and displayed on the Android 16
 * status-bar pill.
 *
 *  * SINGLE  - show only the current line; the pill flips cleanly to each new line.
 *  * CONTEXT - show the current line surrounded by `contextBefore` / `contextAfter`
 *              neighbouring lines so you can see what came before and what is next.
 */
enum class StatusBarLineMode {
    SINGLE,
    CONTEXT
}

/**
 * SharedPreferences-backed settings for the status-bar lyric pill.
 *
 * These control *how lyric lines change* on the Android 16 Live Activity pill
 * (e.g. single-line vs. context window, how many leading/trailing lines to show,
 * whether the track header is appended, and how eagerly plain lyrics advance).
 *
 * Settings live in the `statusbar_prefs` store so they don't collide with the
 * phone/Android Auto settings that auto-lyrics keeps in `auto_lyrics_prefs`.
 */
class StatusBarPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("statusbar_prefs", Context.MODE_PRIVATE)

    /** Master switch — hides the pill entirely when false. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** How lyric lines are chosen/displayed on the pill. */
    var lineMode: StatusBarLineMode
        get() {
            val raw = prefs.getString(KEY_LINE_MODE, StatusBarLineMode.CONTEXT.name)
            return try {
                StatusBarLineMode.valueOf(raw ?: StatusBarLineMode.CONTEXT.name)
            } catch (_: Exception) {
                StatusBarLineMode.CONTEXT
            }
        }
        set(value) = prefs.edit().putString(KEY_LINE_MODE, value.name).apply()

    /** Number of previous lines to show in CONTEXT mode. */
    var contextBefore: Int
        get() = prefs.getInt(KEY_CONTEXT_BEFORE, 1)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_BEFORE, value.coerceIn(0, 3)).apply()

    /** Number of upcoming lines to show in CONTEXT mode. */
    var contextAfter: Int
        get() = prefs.getInt(KEY_CONTEXT_AFTER, 1)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_AFTER, value.coerceIn(0, 3)).apply()

    /** Append "Title — Artist" to the expanded pill notification. */
    var showTrackHeader: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HEADER, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_HEADER, value).apply()

    /** How frequently (ms) plain, unsynced lyrics advance while playing. */
    var plainAdvanceMs: Long
        get() = prefs.getLong(KEY_PLAIN_ADVANCE_MS, 1500L)
        set(value) = prefs.edit().putLong(KEY_PLAIN_ADVANCE_MS, value).apply()

    /** Empty / placeholder pill shown while the app is listening but idle. */
    var showIdlePill: Boolean
        get() = prefs.getBoolean(KEY_SHOW_IDLE_PILL, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_IDLE_PILL, value).apply()

    companion object {
        const val KEY_ENABLED = "sb_enabled"
        const val KEY_LINE_MODE = "sb_line_mode"
        const val KEY_CONTEXT_BEFORE = "sb_context_before"
        const val KEY_CONTEXT_AFTER = "sb_context_after"
        const val KEY_SHOW_HEADER = "sb_show_header"
        const val KEY_PLAIN_ADVANCE_MS = "sb_plain_advance_ms"
        const val KEY_SHOW_IDLE_PILL = "sb_show_idle_pill"
    }
}