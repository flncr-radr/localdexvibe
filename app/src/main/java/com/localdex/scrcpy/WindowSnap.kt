package com.localdex.scrcpy

import android.util.Log
import com.localdex.Adb
import io.github.muntashirakon.adb.AbsAdbConnectionManager

/**
 * Moves the focused window on a DeX display between fullscreen and a bounded
 * (freeform) window. Android's desktop-windowing docs describe only drag
 * gestures for this, so it's driven here with `am` shell commands instead.
 *
 * Why this isn't just `am task resize`: that call is a **silent no-op on a
 * maximized window**. ActivityTaskManagerService.resizeTask bails out early on
 * `!task.getWindowConfiguration().canResizeTask()`, and canResizeTask() is
 * `mWindowingMode == WINDOWING_MODE_FREEFORM || mWindowingMode ==
 * WINDOWING_MODE_MULTI_WINDOW` — so a fullscreen task rejects every resize,
 * logging "resizeTask not allowed on task=" server-side and changing nothing.
 * Bounds alone can never un-maximize a window; the *windowing mode* has to
 * change first. That's what an earlier version of this file got wrong.
 *
 * The windowing mode is changed with `am start --task <id> --windowingMode
 * <mode>`, which routes through ActivityOptions.setLaunchTaskId /
 * setLaunchWindowingMode against the task that already exists, rather than
 * starting anything new. Bounds are then applied with `am task resize`, which
 * is legal once the task is freeform.
 *
 * Commands and their output formats were verified against AOSP source
 * (ActivityManagerShellCommand, ActivityTaskManagerService, WindowConfiguration,
 * DisplayContent.dump, ActivityTaskManager.RootTaskInfo.toString).
 */
object WindowSnap {
    private const val TAG = "WindowSnap"

    /** From WindowConfiguration: the windowing modes `am start --windowingMode` takes. */
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5

    /** A restored window's size, as a fraction of the display. */
    private const val RESTORED_SIZE = 0.7f

    enum class Direction {
        LEFT,
        RIGHT,
        MAXIMIZE,
        RESTORE,

        /** Maximize a restored window, restore a maximized one. */
        TOGGLE,
    }

    /** What [snap] did, so callers can tell the user when nothing happened. */
    enum class Result {
        MAXIMIZED,
        RESTORED,
        NO_FOCUSED_WINDOW,
        NO_MATCHING_TASK,
    }

    suspend fun snap(
        manager: AbsAdbConnectionManager,
        displayId: Int,
        displayWidth: Int,
        displayHeight: Int,
        direction: Direction,
    ): Result {
        val focusedPackage = WindowSnapParser.parseFocusedPackage(
            Adb.runShell(manager, "dumpsys window displays"), displayId
        )
        if (focusedPackage == null) {
            Log.w(TAG, "No focused window on display $displayId; nothing to move")
            return Result.NO_FOCUSED_WINDOW
        }

        val task = WindowSnapParser.parseFocusedTask(
            Adb.runShell(manager, "am stack list"), displayId, focusedPackage
        )
        if (task == null) {
            Log.w(TAG, "No task found for $focusedPackage on display $displayId")
            return Result.NO_MATCHING_TASK
        }

        val resolved = if (direction == Direction.TOGGLE) {
            if (WindowSnapParser.isMaximized(task.bounds, displayWidth, displayHeight)) {
                Direction.RESTORE
            } else {
                Direction.MAXIMIZE
            }
        } else {
            direction
        }
        Log.i(TAG, "task=${task.taskId} ${task.component} bounds=${task.bounds} -> $resolved")

        if (resolved == Direction.MAXIMIZE) {
            setWindowingMode(manager, task, displayId, WINDOWING_MODE_FULLSCREEN)
            return Result.MAXIMIZED
        }

        // Freeform first, then bounds — the order the resizeTask guard requires.
        setWindowingMode(manager, task, displayId, WINDOWING_MODE_FREEFORM)
        val bounds = boundsFor(resolved, displayWidth, displayHeight)
        Adb.runShell(
            manager,
            "am task resize ${task.taskId} " +
                "${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"
        )
        return Result.RESTORED
    }

    private suspend fun setWindowingMode(
        manager: AbsAdbConnectionManager,
        task: WindowSnapParser.TaskWindow,
        displayId: Int,
        mode: Int,
    ) {
        val reply = Adb.runShell(
            manager,
            "am start --task ${task.taskId} --windowingMode $mode --display $displayId " +
                "-n ${task.component}"
        )
        Log.i(TAG, "windowingMode=$mode on task ${task.taskId}: ${reply.ifBlank { "(no output)" }}")
    }

    private fun boundsFor(
        direction: Direction,
        displayWidth: Int,
        displayHeight: Int,
    ): WindowSnapParser.Bounds = when (direction) {
        Direction.LEFT -> WindowSnapParser.Bounds(0, 0, displayWidth / 2, displayHeight)
        Direction.RIGHT ->
            WindowSnapParser.Bounds(displayWidth / 2, 0, displayWidth, displayHeight)
        else -> {
            val width = (displayWidth * RESTORED_SIZE).toInt()
            val height = (displayHeight * RESTORED_SIZE).toInt()
            val left = (displayWidth - width) / 2
            val top = (displayHeight - height) / 2
            WindowSnapParser.Bounds(left, top, left + width, top + height)
        }
    }
}
