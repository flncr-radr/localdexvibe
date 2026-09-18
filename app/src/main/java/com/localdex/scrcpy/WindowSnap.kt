package com.localdex.scrcpy

import android.util.Log
import com.localdex.Adb
import io.github.muntashirakon.adb.AbsAdbConnectionManager

/**
 * Snaps the focused window on a DeX display to a half or the full display.
 * Android's desktop-windowing docs describe only drag gestures for this — no
 * keyboard shortcut — so this drives it directly with the same two shell
 * commands `am`'s own hidden "task"/"stack" subcommands use, verified against
 * the AOSP source they come from:
 *
 * - `dumpsys window displays` prints each display's focused window under its
 *   own `Display: mDisplayId=N` header, as
 *   `mCurrentFocus=Window{<hash> u<uid> <title>}` (DisplayContent.dump /
 *   WindowState.toString).
 * - `am task resize <taskId> <left> <top> <right> <bottom>` moves/resizes a
 *   task (ActivityManagerShellCommand.runTaskResize / getBounds — the four
 *   bounds are separate space-separated args, not one comma-joined value).
 * - `am stack list` prints every root task's children under its own
 *   `RootTask id=... displayId=N` header, as
 *   `taskId=<id>: <component> bounds=... ...` (ActivityTaskManager.RootTaskInfo
 *   .toString / RootWindowContainer.getRootTaskInfo).
 *
 * The window title and the task's component string can use different forms of
 * the same component (short vs. fully-qualified class name), so [WindowSnapParser]
 * only ever matches on the package substring — the one part guaranteed to agree
 * between them.
 */
object WindowSnap {
    private const val TAG = "WindowSnap"

    enum class Direction { LEFT, RIGHT, MAXIMIZE, RESTORE }

    suspend fun snap(
        manager: AbsAdbConnectionManager,
        displayId: Int,
        displayWidth: Int,
        displayHeight: Int,
        direction: Direction,
    ) {
        val focusedPackage = WindowSnapParser.parseFocusedPackage(
            Adb.runShell(manager, "dumpsys window displays"), displayId
        )
        if (focusedPackage == null) {
            Log.w(TAG, "No focused window on display $displayId; not snapping")
            return
        }

        val taskId = WindowSnapParser.parseTaskId(
            Adb.runShell(manager, "am stack list"), displayId, focusedPackage
        )
        if (taskId == null) {
            Log.w(TAG, "Could not find a task for $focusedPackage on display $displayId")
            return
        }

        val bounds = when (direction) {
            Direction.LEFT -> intArrayOf(0, 0, displayWidth / 2, displayHeight)
            Direction.RIGHT -> intArrayOf(displayWidth / 2, 0, displayWidth, displayHeight)
            Direction.MAXIMIZE -> intArrayOf(0, 0, displayWidth, displayHeight)
            // A centered, explicitly-bounded rect — not full-display — since setting
            // bounds smaller than the display is what pulls a task out of fullscreen
            // windowing on devices where the platform's own restore/un-maximize
            // gesture doesn't (this is the actual fix for that, not just a shortcut).
            Direction.RESTORE -> {
                val w = (displayWidth * 0.7f).toInt()
                val h = (displayHeight * 0.7f).toInt()
                val left = (displayWidth - w) / 2
                val top = (displayHeight - h) / 2
                intArrayOf(left, top, left + w, top + h)
            }
        }
        Adb.runShell(manager, "am task resize $taskId ${bounds[0]} ${bounds[1]} ${bounds[2]} ${bounds[3]}")
    }
}
