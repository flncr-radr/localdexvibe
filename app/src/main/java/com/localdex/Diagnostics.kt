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

            appendLine(
                "dex_on_external_display: " +
                    Adb.runShellCommand(context, "settings get system dex_on_external_display")
                        .getOrElse { "(failed: ${it.message})" }.trim()
            )

            appendLine()
            appendLine("-- Tasks on display ${session.displayId} --")
            appendLine(displayTasks(context, session.displayId))
        }

        appendLine()
        appendLine("-- Desktop mode state --")
        appendLine(desktopModeState(context))

        appendLine()
        appendLine("-- Recent logs (this app only) --")
        appendLine(recentLogs())
    }

    /**
     * What the device thinks about desktop mode, independent of whether LocalDex
     * has a session of its own.
     *
     * This exists to be compared against a *working* DeX rather than read alone.
     * Another on-device DeX app is installed on the reporting device, and its DeX
     * behaves fully — taskbar tracking running apps, minimize keeping the icon,
     * show-desktop working — where LocalDex's gets only the chrome. Everything
     * plain-framework works on our display and everything needing DeX's own
     * session state does not, which points at the device never actually entering
     * DeX mode for it. Capturing this while the other app's desktop is up, and
     * again while LocalDex's is, should show what differs instead of guessing.
     *
     * Deliberately greps rather than dumping wholesale: `settings list` and
     * `dumpsys display` are enormous, and a report nobody reads is worth nothing.
     */
    private suspend fun desktopModeState(context: Context): String {
        val probes = listOf(
            "settings/global" to "settings list global",
            "settings/system" to "settings list system",
            "settings/secure" to "settings list secure",
        )
        return buildString {
            for ((label, command) in probes) {
                val out = Adb.runShellCommand(context, "$command | grep -iE 'dex|desktop'")
                    .getOrElse { "(failed: ${it.message})" }
                    .trim()
                appendLine("[$label] ${out.ifEmpty { "(nothing matched)" }}")
            }
            // What Samsung's own DeX service exposes. Setting dex_on_external_display=1
            // was not enough on its own: the flag took, and app windows still landed
            // as bare root tasks rather than inside the "Desk" container a working
            // DeX puts them in. That container is attached via
            // WindowContainerTransaction.setLaunchRoot, which only the system shell
            // can issue — so LocalDex cannot create one, and the only route left is
            // getting Samsung's DeX service to create it. This lists what of that
            // service is startable.
            val dexComponents = Adb.runShellCommand(
                context,
                "dumpsys package com.sec.android.app.launcher | " +
                    "grep -iE 'dexservice|desktopmode' | sort -u | head -40"
            ).getOrElse { "(failed: ${it.message})" }.trim()
            appendLine("[dex service components] ${dexComponents.ifEmpty { "(nothing matched)" }}")

            // How the virtual display itself is described. This is the other half of
            // the A/B: everything plain-framework works on our display and everything
            // needing DeX's own session state does not, so Samsung may simply not
            // consider this display eligible for DeX. Its flags, type and owner are
            // what such a check would read, and scrcpy picks those when it creates
            // the display — unlike the Desk container, they are ours to change.
            // Captured for every display so a working DeX's display and ours can be
            // compared line for line in one report.
            val displays = Adb.runShellCommand(
                context,
                "dumpsys display | grep -iE 'mDisplayId=|uniqueId=|DisplayDeviceInfo|" +
                    "ownerPackageName|flags=|mBaseDisplayInfo' | head -60"
            ).getOrElse { "(failed: ${it.message})" }.trim()
            appendLine()
            appendLine("[displays]")
            appendLine(displays.ifEmpty { "(nothing matched)" })
            appendLine()

            val services = Adb.runShellCommand(context, "service list | grep -iE 'dex|desktop'")
                .getOrElse { "(failed: ${it.message})" }
                .trim()
            appendLine("[services] ${services.ifEmpty { "(nothing matched)" }}")

            // Every display with its root tasks, so a working DeX display can be
            // compared against ours side by side in one capture.
            val tasks = Adb.runShellCommand(
                context,
                "dumpsys activity activities | grep -E 'Display #|type=(home|standard|recents)'"
            ).getOrElse { "(failed: ${it.message})" }.trim()
            appendLine()
            appendLine("[tasks, all displays]")
            append(tasks.ifEmpty { "(nothing matched)" })
        }
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
            // 'type=' rather than 'type=home': the first cut of this matched only
            // home tasks, so the app window and the desktop selector — the two
            // actually in question — were filtered out of the report.
            "dumpsys activity activities | grep -E 'Display #|RootTask id=|type='"
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
