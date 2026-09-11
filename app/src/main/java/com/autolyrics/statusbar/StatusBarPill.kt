package com.autolyrics.statusbar

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.autolyrics.R
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.*

/**
 * Drives the Android 16 Live Activity pill.
 *
 * A promoted *ongoing notification* is what renders the top status-bar pill.
 * Every time the current lyric line changes (via [MediaTracker.state]) we rebuild
 * the promoted notification and push it through [NotificationManager.notify], so
 * the pill stays in sync with playback. When nothing is playing we cancel it.
 *
 * Which text goes on the pill is governed by [StatusBarPrefs] — that is the
 * "customizations on how to change lines" (single line vs. a context window).
 */
class StatusBarPill(
    private val context: Application,
    private val manager: NotificationManager,
    private val prefs: StatusBarPrefs
) {
    private val mediaTracker = MediaTracker.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var lastText: String? = null
    private var lastSub: String? = null
    private var plainIndex = 0
    private var plainAdvanceRunnable: Runnable? = null

    private val plainTick = object : Runnable {
        override fun run() {
            val current = mediaTracker.state.value
            if (current.status == LyricsStatus.PLAIN_ONLY && current.isPlaying) {
                updatePlainIndex(current)
                refresh(current)
                handler.postDelayed(this, prefs.plainAdvanceMs.coerceAtLeast(250L))
            }
        }
    }

    fun start() {
        scope.launch {
            mediaTracker.state.collect { state ->
                onStateUpdated(state)
            }
        }
    }

    fun stop() {
        scope.cancel()
        plainAdvanceRunnable?.let { handler.removeCallbacks(it) }
        plainAdvanceRunnable = null
        handler.removeCallbacks(plainTick)
        try {
            manager.cancel(StatusBarLyricsService.NOTIFICATION_ID)
        } catch (_: Exception) { }
        lastText = null
        lastSub = null
}

    private fun onStateUpdated(state: LyricsState) {
        if (!prefs.enabled) {
            hide()
            return
        }

        if (state.track == null) {
            if (prefs.showIdlePill) {
                show(
                    if (prefs.showTrackHeader) "♪  StatusBar Lyrics" else "♪  Lyrics idle…",
                    ""
                )
            } else {
                hide()
            }
            return
        }

        when (state.status) {
            LyricsStatus.FOUND -> {
                stopPlainTicker()
                refresh(state)
            }
            LyricsStatus.PLAIN_ONLY -> {
                if (state.isPlaying) {
                    updatePlainIndex(state)
                    startPlainTicker()
                } else {
                    stopPlainTicker()
                }
                refresh(state)
            }
            else -> {
                // LOADING / NOT_FOUND / ERROR / NO_MEDIA — nothing to sing yet.
                stopPlainTicker()
                if (prefs.showIdlePill) {
                    show(
                        if (prefs.showTrackHeader) "${state.track.title} — ${state.track.artist}"
                        else state.track.title,
                        ""
                    )
                } else {
                    hide()
                }
            }
        }
    }

    private fun refresh(state: LyricsState) {
        val text = buildPillText(state)
        if (text == null) {
            hide()
            return
        }
        val sub = if (prefs.showTrackHeader && state.track != null)
            state.track.title.ifBlank { "♫" } else ""
        show(text, sub, state)
    }

    private fun show(text: String, sub: String, state: LyricsState? = null) {
        if (text == lastText && sub == lastSub) return
        lastText = text
        lastSub = sub
        val notification = try {
            buildNotification(text, sub, state)
        } catch (_: Exception) {
            null
        } ?: return
        try {
            manager.notify(StatusBarLyricsService.NOTIFICATION_ID, notification)
        } catch (_: Exception) { }
    }

    private fun hide() {
        if (lastText == null && lastSub == null) return
        lastText = null
        lastSub = null
        try {
            manager.cancel(StatusBarLyricsService.NOTIFICATION_ID)
        } catch (_: Exception) { }
    }
/**
     * Resolves the actual pill text from the current state, honouring the chosen
     * line-change customisation (SINGLE vs. CONTEXT window).
     */
    private fun buildPillText(state: LyricsState): String? {
        val mode = prefs.lineMode
        val lines = state.lines
        if (lines.isEmpty()) return null

        if (state.status != LyricsStatus.FOUND) {
            val cur = plainIndex.coerceIn(0, lines.size - 1)
            return if (mode == StatusBarLineMode.SINGLE)
                lines[cur].text
            else
                buildContext(lines, cur, prefs)
        }

        var cur = state.currentIndex
        if (cur < 0) {
            // Playback hasn't reached the first timestamp yet — start on the first line.
            cur = 0
        }
        if (cur >= lines.size) cur = lines.size - 1

        return if (mode == StatusBarLineMode.SINGLE)
            lines[cur].text
        else
            buildContext(lines, cur, prefs)
    }

    private fun buildContext(
        lines: List<com.autolyrics.model.LyricLine>,
        cur: Int,
        prefs: StatusBarPrefs
    ): String {
        val start = (cur - prefs.contextBefore).coerceAtLeast(0)
        val end = (cur + prefs.contextAfter).coerceAtMost(lines.size - 1)
        val parts = mutableListOf<String>()
        for (i in start..end) {
            val marker = if (i == cur) "▶  " else "   "
            parts.add("$marker${lines[i].text}")
        }
        return parts.joinToString("\n")
    }

    private fun updatePlainIndex(state: LyricsState) {
        val duration = state.track?.durationMs ?: 0L
        if (duration <= 0 || state.lines.isEmpty()) return
        val pos = try { mediaTracker.getCurrentPositionMs().coerceAtLeast(0L) } catch (_: Exception) { 0L }
        val idx = ((pos.toFloat() / duration) * state.lines.size).toInt()
        plainIndex = idx.coerceIn(0, state.lines.size - 1)
    }

    private fun startPlainTicker() {
        if (plainAdvanceRunnable != null) return
        plainAdvanceRunnable = Runnable {
            plainAdvanceRunnable = null
            handler.removeCallbacks(plainTick)
            handler.post(plainTick)
        }
        plainAdvanceRunnable!!.run()
    }

    private fun stopPlainTicker() {
        plainAdvanceRunnable?.let { handler.removeCallbacks(it) }
        plainAdvanceRunnable = null
        handler.removeCallbacks(plainTick)
    }

    private fun buildNotification(
        text: String,
        sub: String,
        state: LyricsState?
    ): Notification {
        val builder = NotificationCompat.Builder(context, StatusBarLyricsService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setRequestPromotedOngoing(true)
            .setShowWhen(false)
            .setStyle(NotificationCompat.BigTextStyle())
            .setShortCriticalText(text)
            .setSubText(sub)

        // A content title is always required — without it (or without a small
        // icon) Android silently drops the notification and no pill appears.
        builder.setContentTitle(
            state?.track?.let { it.title.ifBlank { it.artist } } ?: "StatusBar Lyrics"
        )
        if (prefs.showTrackHeader && sub.isNotBlank()) {
            builder.setContentText(sub)
        }

        val track = state?.track
        if (track != null && track.durationMs > 0) {
            val pos = try { mediaTracker.getCurrentPositionMs().coerceAtLeast(0L) } catch (_: Exception) { 0L }
            builder.setProgress(
                track.durationMs.toInt(),
                pos.toInt(),
                false
            )
        }

        return builder.build()
    }
}