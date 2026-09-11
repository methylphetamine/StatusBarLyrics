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

    /** Marquee position for the tiny pill slot (chars already scrolled past). */
    private var marqueeOffset = 0
    private var marqueeSource: String? = null

    /**
     * The Live-Activity pill slot is only a few characters wide — long lyric
     * lines render as nothing. We therefore feed the pill a short scrolling
     * window over the lyric text (like a ticker) and advance it on a timer.
     * The full line still lives in the shade notification.
     */
    private fun marqueePillText(text: String): String {
        val clean = text.replace("\\s+".toRegex(), " ").trim()
        if (clean.length <= PILL_WINDOW) {
            marqueeSource = null
            return clean
        }
        if (clean != marqueeSource) {
            marqueeSource = clean
            marqueeOffset = 0
        }
        val padded = "$clean    "
        val end = (marqueeOffset + PILL_WINDOW).coerceAtMost(padded.length)
        val head = padded.substring(marqueeOffset, end)
        // Pad the tail so the window stays a fixed width while scrolling.
        return head.padEnd(PILL_WINDOW)
    }

    private val marqueeTick = object : Runnable {
        override fun run() {
            val src = marqueeSource ?: return
            marqueeOffset += 1
            if (marqueeOffset > src.length + 4) marqueeOffset = 0
            lastText = marqueePillText(src)
            show(lastText!!, lastSub)
            handler.postDelayed(this, MARQUEE_INTERVAL_MS)
        }
    }

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
        handler.removeCallbacks(marqueeTick)
        try {
            manager.cancel(StatusBarLyricsService.NOTIFICATION_ID)
        } catch (_: Exception) { }
        lastText = null
        lastSub = null
        marqueeSource = null
        marqueeOffset = 0
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
        // The pill slot can only fit a few characters: collapse any multi-line
        // CONTEXT window to the active line and clip the text to the window.
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }
            ?.replace("▶", "").replace("♪", "").trim() ?: text
        val pillText = marqueePillText(firstLine)

        if (pillText == lastText && sub == lastSub) return
        lastText = pillText
        lastSub = sub
        val notification = try {
            buildNotification(pillText, sub, state)
        } catch (_: Exception) {
            null
        } ?: return
        try {
            manager.notify(StatusBarLyricsService.NOTIFICATION_ID, notification)
        } catch (_: Exception) { }
        // Kick (or reset) the marquee ticker for the tiny pill slot.
        handler.removeCallbacks(marqueeTick)
        handler.postDelayed(marqueeTick, MARQUEE_INTERVAL_MS)
    }

    private fun hide() {
        handler.removeCallbacks(marqueeTick)
        marqueeSource = null
        marqueeOffset = 0
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

    private companion object {
        /** How many characters of text the pill slot can actually render. */
        const val PILL_WINDOW = 7

        /** Marquee scroll step interval in ms. */
        const val MARQUEE_INTERVAL_MS = 350L
    }
}