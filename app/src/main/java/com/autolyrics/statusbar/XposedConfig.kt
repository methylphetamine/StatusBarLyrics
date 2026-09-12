package com.autolyrics.statusbar

/**
 * Runtime config for the Xposed clock hook.
 *
 * This class deliberately has NO Xposed imports so the app can read/write it
 * without loading any Xposed classes. The actual [StatusBarClockHook] (which
 * does import the Xposed API) is only ever loaded by the LSPosed framework —
 * never by the app itself.
 */
object XposedConfig {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var currentLyric: String? = null
}