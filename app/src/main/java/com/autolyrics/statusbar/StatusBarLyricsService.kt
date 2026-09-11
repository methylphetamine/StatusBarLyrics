package com.autolyrics.statusbar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.service.notification.NotificationListenerService

/**
 * Android 16 status-bar lyrics service.
 *
 * Runs as a background `NotificationListenerService` (exactly like the media
 * listener and LiveMedia's controller) so it can post a *promoted ongoing
 * notification* — the thing Android renders as the top status-bar pill. The
 * pill is rebuilt by `StatusBarPill` whenever the current lyric line changes.
 *
 * This service only needs the `NotificationManager` plus the existing
 * `MediaTracker` state (read-only); it does not add a second media controller,
 * so it is lightweight.
 */
class StatusBarLyricsService : NotificationListenerService() {

    private lateinit var pill: StatusBarPill

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "StatusBar Lyrics", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
        pill = StatusBarPill(application, manager, StatusBarPrefs(application))
        pill.start()
    }

    override fun onDestroy() {
        pill.stop()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 4242
        const val CHANNEL_ID = "StatusBarLyricsChannel"
    }
}