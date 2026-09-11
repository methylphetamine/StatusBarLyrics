package com.autolyrics.statusbar

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/**
 * Controls whether lyrics are sent to the Xposed clock hook or the standard pill.
 *
 * When Xposed/LSPosed is available and this app is enabled as an Xposed module,
 * lyrics are pushed directly to [StatusBarClockHook.currentLyric] which the hook
 * reads to replace the system clock text. Otherwise falls back to the notification
 * pill.
 */
class XposedClockController(private val context: Application) {

    private val mediaTracker = MediaTracker.getInstance(context)
    private val prefs = StatusBarPrefs(context)
    private val appPrefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var lastLyric: String? = null

    /**
     * Whether the Xposed hook is active (LSPosed module enabled for SystemUI).
     */
    val isActive: Boolean
        get() = StatusBarClockHook.enabled && isXposedModuleEnabled()

    fun start() {
        // Enable the hook flag
        StatusBarClockHook.enabled = appPrefs.getBoolean(KEY_XPOSED_MODE, false)

        scope.launch {
            mediaTracker.state.collect { state ->
                onStateUpdated(state)
            }
        }
    }

    fun stop() {
        scope.cancel()
        StatusBarClockHook.currentLyric = null
        lastLyric = null
    }

    fun setEnabled(enabled: Boolean) {
        appPrefs.edit().putBoolean(KEY_XPOSED_MODE, enabled).apply()
        StatusBarClockHook.enabled = enabled
        if (!enabled) {
            StatusBarClockHook.currentLyric = null
        }
    }

    private fun isXposedModuleEnabled(): Boolean {
        // Check if our Xposed module is loaded by looking for the xposed_init marker
        // and if the hook has been initialized
        return try {
            // If we can access XposedBridge, the module framework is available
            Class.forName("de.robv.android.xposed.XposedBridge")
            // Check if our hook has been instantiated (set by the hook's companion)
            StatusBarClockHook.enabled
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun onStateUpdated(state: LyricsState) {
        if (!prefs.enabled) {
            updateLyric(null)
            return
        }

        val lyric = when {
            state.track == null -> null
            state.status == LyricsStatus.FOUND -> buildLyricText(state)
            state.status == LyricsStatus.PLAIN_ONLY && state.isPlaying -> buildPlainLyricText(state)
            else -> null
        }

        updateLyric(lyric)
    }

    private fun buildLyricText(state: LyricsState): String? {
        val lines = state.lines
        if (lines.isEmpty()) return null

        val mode = prefs.lineMode
        var cur = state.currentIndex
        if (cur < 0) cur = 0
        if (cur >= lines.size) cur = lines.size - 1

        return when (mode) {
            StatusBarLineMode.SINGLE -> lines[cur].text
            else -> buildContextForClock(lines, cur)
        }
    }

    private fun buildPlainLyricText(state: LyricsState): String? {
        val lines = state.lines
        if (lines.isEmpty()) return null

        val duration = state.track?.durationMs ?: 0L
        if (duration <= 0) return lines.firstOrNull()?.text

        val pos = try { mediaTracker.getCurrentPositionMs().coerceAtLeast(0L) } catch (_: Exception) { 0L }
        val idx = ((pos.toFloat() / duration) * lines.size).toInt().coerceIn(0, lines.size - 1)
        return lines[idx].text
    }

    private fun buildContextForClock(lines: List<com.autolyrics.model.LyricLine>, cur: Int): String {
        // For clock display, just show the current line - too many chars otherwise
        return lines[cur].text
    }

    private fun updateLyric(lyric: String?) {
        if (lyric == lastLyric) return
        lastLyric = lyric

        if (isActive) {
            StatusBarClockHook.currentLyric = lyric
        }
    }

    companion object {
        const val KEY_XPOSED_MODE = "xposed_mode"
    }
}