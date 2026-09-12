package com.autolyrics.statusbar

import android.content.Context
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed hook that replaces the status bar clock text with the current lyric line.
 *
 * This module is loaded by LSPosed/EdXposed when the app is enabled as an Xposed module.
 * It hooks into the SystemUI status bar clock and replaces the text with lyrics.
 *
 * To use: Enable this app as an Xposed module in LSPosed Manager, scope to "System UI",
 * then reboot. The clock will show lyrics instead of time while music plays.
 */
class StatusBarClockHook : de.robv.android.xposed.IXposedHookLoadPackage {

    companion object {
        private const val TAG = "StatusBarLyrics"
        private const val SYSTEM_UI_PKG = "com.android.systemui"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != SYSTEM_UI_PKG) return
        if (!XposedConfig.enabled) return

        try {
            hookClock(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to hook clock: ${e.message}")
        }
    }

    private fun hookClock(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook the clock's setText method to intercept and replace with lyrics
        val clockClass = XposedHelpers.findClass(
            "com.android.systemui.statusbar.policy.Clock",
            lpparam.classLoader
        )

        XposedBridge.hookAllMethods(clockClass, "setText", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val lyric = XposedConfig.currentLyric
                if (!lyric.isNullOrBlank()) {
                    // Replace clock text with lyric
                    param.args[0] = lyric
                }
            }
        })

        // Also hook the clock's invalidate to force redraws when lyrics change
        XposedBridge.hookAllMethods(clockClass, "invalidate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val textView = param.thisObject as? TextView ?: return
                val lyric = XposedConfig.currentLyric
                if (!lyric.isNullOrBlank() && textView.text != lyric) {
                    textView.text = lyric
                }
            }
        })

        XposedBridge.log("$TAG: Clock hook installed successfully")
    }
}