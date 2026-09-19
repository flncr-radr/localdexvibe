package com.localdex

import android.content.Context
import android.os.Build
import com.localdex.scrcpy.ScrcpySession
import com.localdex.scrcpy.WindowSnapParser

/**
 * A copy-and-paste report of the app's own state, for bug reports — the
 * alternative being "send me a screenshot and describe what you tapped",
 * which this whole session's back-and-forth was an argument against.
 *
 * Deliberately text, not a file: it needs to land in a chat message with zero
 * extra steps, and everything in it is either public device info or this
 * app's own state — nothing a user wouldn't already see on their own screen.
 */
object Diagnostics {

    /** Does ADB round-trips and runs a `logcat` subprocess — call this off the main thread. */
    suspend fun collect(context: Context): String = buildString {
        appendLine("LocalDex diagnostics")
        appendLine("=====================")

        appendLine()
        appendLine("App: ${appVersion(context)}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Build: ${Build.DISPLAY}")

        appendLine()
        appendLine("-- Pairing / connection --")
        appendLine("Has paired before: ${Prefs.hasPairedBefore(context)}")
        appendLine("Wireless debugging enabled: ${WirelessDebugging.isEnabled(context)}")
        appendLine("Can toggle it ourselves: ${WirelessDebugging.canToggle(context)}")
        appendLine("ADB status: ${Adb.getConnectionStatus(context, forceCheck = true)}")

        appendLine()
        appendLine("-- Configuration --")
        appendLine("Display spec: ${Prefs.getDisplaySpec(context)}")
        appendLine("Exit tab position: ${Prefs.getPanelPositionFraction(context)} from bottom")

        appendLine()
        appendLine("-- Session --")
        val session = ScrcpySession.current
        if (session == null) {
            appendLine("No session running")
        } else {
            appendLine("State: ${session.state.value}")
            appendLine("Display id: ${session.displayId}")
            appendLine("Video size: ${session.videoWidth}x${session.videoHeight}")
            appendLine(
                "Freeform: ${
                    when {
                        session.freeformForceInProgress -> "still forcing"
                        session.freeformForceFailed -> "FAILED — apps open fullscreen"
                        else -> "forced OK"
                    }
                }"
            )

            appendLine()
            appendLine("-- Tasks on display ${session.displayId} --")
            appendLine(displayTasks(context, session.displayId))
        }

        appendLine()
        appendLine("-- Recent logs (this app only) --")
        appendLine(recentLogs())
    }

    /**
     * What is actually on the DeX display, for when a window control does nothing
     * visible. Minimize in particular reorders a task *behind* the display's home
     * task (AOSP DesktopTasksController.minimizeTaskInner does `wct.reorder(token,
     * false)`), so whether this display has a home task at all decides whether
     * minimize can work — and that is a fact to read off the device, not to infer
     * from the symptom.
     */
    private suspend fun displayTasks(context: Context, displayId: Int): String {
        if (displayId < 0) return "(display id not known yet)"
        val stack = Adb.runShellCommand(context, "am stack list")
            .getOrElse { return "(could not list tasks: ${it.message})" }
        val types = Adb.runShellCommand(
            context,
            "dumpsys activity activities | grep -E " +
                "'Display #|RootTask id=|ACTIVITY_TYPE_HOME|type=home'"
        ).getOrDefault("")
        return buildString {
            appendLine(WindowSnapParser.displaySection(stack, displayId))
            if (types.isNotBlank()) {
                appendLine()
                appendLine("Root tasks and activity types (all displays):")
                append(types.trim())
            }
        }
    }

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (e: Exception) {
        "unknown"
    }

    /**
     * `logcat -d` reads only entries this app is allowed to see — its own, since
     * Android 4.1 — so this needs no permission beyond what every app already has.
     * Scoped to this app's own tags; `*:S` silences everything else so a chatty
     * system log doesn't drown out the handful of lines that actually matter.
     */
    private fun recentLogs(): String = try {
        val process = ProcessBuilder(
            "logcat", "-d", "-t", "200",
            "ScrcpySession:*", "VideoDecoder:*", "Controller:*", "WindowSnap:*",
            "DexService:*", "AdbMdns:*", "MainActivity:*", "ViewerActivity:*",
            "AndroidRuntime:E", "*:S"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.trim().ifEmpty {
            "(logcat returned nothing — either the app genuinely logged nothing, or this " +
                "device does not let it read even its own log; the sections above are the " +
                "reliable ones)"
        }
    } catch (e: Exception) {
        "(could not read logs: ${e.message})"
    }
}
