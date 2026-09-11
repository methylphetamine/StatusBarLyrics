# StatusBar Lyrics

Show **synced lyrics for the currently playing song on the Android 16 status bar**
as a Live Activity pill, driven by the auto-lyrics engine (LRCLIB / SyncLRC lyrics +
media-session song detection).

This is a fork of [`GitUpGitUp/auto-lyrics`](https://github.com/GitUpGitUp/auto-lyrics)
adapted so that instead of (only) targeting Android Auto, the current lyric line is
rendered as a *promoted ongoing notification* — which is exactly what Android 16
renders as the **top status-bar pill** / Live Activity chip.

## Features

- Current lyric line shown on the Android 16 status bar, updated in real time as
  the song plays (works with Spotify, YouTube Music, Apple Music, Poweramp, etc.).
- Works with **synced** (LRC) and **plain** lyrics. Karaoke / word timestamps are
  kept for the phone view.
- Detects what's playing automatically via media session APIs (reuses auto-lyrics'
  `MediaTracker` + `MediaListenerService`).
- Lyrics come from **SyncLRC** (primary) and **LRCLIB** (fallback).
- **Customizable line-changing** behavior (see below).
- Phone companion screen still works for browsing / adjusting timing.

## How it renders (Android 16 status bar)

The status bar pill is a **promoted ongoing notification**. The
`StatusBarLyricsService` (a `NotificationListenerService`) observes `MediaTracker`,
and whenever the current lyric line changes it rebuilds the notification with
`NotificationCompat.Builder(...).setOngoing(true).setRequestPromotedOngoing(true)`
and pushes it via `NotificationManager.notify(...)`. Android renders this as the
pill at the top of the home screen (the Live Activity area) — the OS anchors it;
on Android 16 it appears at the top-left of the status area.

Files:

| File | Purpose |
|---|---|
| `app/src/main/java/com/autolyrics/statusbar/StatusBarLyricsService.kt` | Background `NotificationListenerService` that hosts the pill. |
| `app/src/main/java/com/autolyrics/statusbar/StatusBarPill.kt` | Observes `MediaTracker.state`, builds & publishes the promoted ongoing notification. |
| `app/src/main/java/com/autolyrics/statusbar/StatusBarPrefs.kt` | SharedPreferences-backed "how lines change" settings. |

## Customizing how lyric lines change

Open the app → tap the **⚙ / settings** button → **Android 16 Status Bar**:

| Setting | What it does |
|---|---|
| **Show status bar pill** | Master on/off for the status bar pill. |
| **Lines → 1 line** | Show only the current line; the pill flips cleanly on each change. |
| **Lines → Context** | Show the current line with a window of surrounding lines (`▶` marks the current line). |
| **Before / After** | In Context mode, how many previous / upcoming lines to show (0–3). |
| **Show track header** | Append "Title — Artist" / a progress bar to the pill content. |

These are stored in the `statusbar_prefs` SharedPreferences store (keys prefixed
`sb_`), so you can also change them programmatically.

## Permissions

On first run you'll be prompted (or can tap **Grant Notification Access** in the
app) to allow:

- **Notification Listener** — lets the app read media metadata/session tokens.
- **Live Updates** (posting promoted/ongoing notifications) — needed for the
  status-bar pill. If the pill doesn't appear, open your phone's app
  *Notifications* settings for StatusBar Lyrics and allow Live Updates.

The app declares `POST_NOTIFICATIONS`, `POST_PROMOTED_NOTIFICATIONS`,
`FOREGROUND_SERVICE*`, `MEDIA_CONTENT_CONTROL`, `INTERNET`, and
`BIND_NOTIFICATION_LISTENER_SERVICE`.

## Build & get the APK

This repo builds the APK with **GitHub Actions** so you can download it easily:

1. Push to GitHub (this is already set up):
   - On every push to `main` and every PR, the **Build APK** workflow compiles a
     **debug** (sideloadable) APK.
   - Open **Actions → Build APK → latest run → Artifacts** and download the
     `.apk` (e.g. `auto-lyrics-1.9.6.apk`).
2. Tag a release `v*` (e.g. `git tag v1.0 && git push --tags`) to also attach the
   APK to a **GitHub Release** for one-click downloads.

### Local build

```bash
# needs JDK 17 + an Android SDK; the Gradle Android plugin downloads the SDK on first run
./gradlew assembleDebug   # or: gradle assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Installing on the phone

Sideload the APK (you have root / Play Protect bypass, or use an installer). Then:

1. Open **StatusBar Lyrics**, play a song in any music app.
2. If asked, allow Notification Listener + Live Updates access.
3. The pill appears at the top of the screen with the current lyric line.

## License

MIT (inherited from auto-lyrics). Lyrics come from SyncLRC / LRCLIB.