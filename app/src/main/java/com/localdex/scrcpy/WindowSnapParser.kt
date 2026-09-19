package com.localdex.scrcpy

/**
 * Text parsing for the `am`/`dumpsys` shell commands behind [WindowSnap].
 * Dependency-free (no Android imports) so it can be unit tested on the JVM,
 * the same way [ScrcpyProtocol] is — the risk here is entirely in matching
 * these formats correctly, not in anything device-specific.
 */
internal object WindowSnapParser {
    // Every one of these tolerates leading whitespace on purpose: DisplayContent.dump
    // is called as dump(pw, "  ", true) from RootWindowContainer.dumpDisplayContents,
    // so it prints "  Display: mDisplayId=7", not "Display: mDisplayId=7". Anchoring
    // this one at column 0 is what made the whole lookup silently find nothing.
    private val DISPLAY_HEADER = Regex("""^\s*Display: mDisplayId=(\d+)""")
    private val CURRENT_FOCUS = Regex("""^\s*mCurrentFocus=Window\{\S+ u\d+ ([^}]+)\}""")
    private val CURRENT_FOCUS_NULL = Regex("""^\s*mCurrentFocus=null\s*$""")

    private val ROOT_TASK_HEADER = Regex("""^\s*RootTask id=\d+ .*displayId=(\d+)""")
    private val CHILD_TASK_LINE =
        Regex("""^\s*taskId=(\d+): (\S+)(?: bounds=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)])?""")
    private val VISIBLE_FLAG = Regex("""\bvisible=(true|false)\b""")

    /** A window's bounds on its display, as `am stack list` reports them. */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * One task on the DeX display. [component] is the fully-qualified
     * `package/class` form `am start -n` needs; [bounds] is absent when
     * `am stack list` didn't report any for the task.
     */
    data class TaskWindow(
        val taskId: Int,
        val component: String,
        val bounds: Bounds?,
        val visible: Boolean = false,
    )

    /** The package of the focused window on [displayId], from `dumpsys window displays`. */
    fun parseFocusedPackage(dumpsysWindowDisplays: String, displayId: Int): String? {
        var currentDisplay: Int? = null
        for (line in dumpsysWindowDisplays.lineSequence()) {
            DISPLAY_HEADER.find(line)?.let { currentDisplay = it.groupValues[1].toIntOrNull() }
            if (currentDisplay != displayId) continue
            CURRENT_FOCUS.find(line)?.let { return it.groupValues[1].substringBefore('/') }
            if (CURRENT_FOCUS_NULL.containsMatchIn(line)) return null
        }
        return null
    }

    /**
     * The task on [displayId] to act on, from `am stack list`.
     *
     * [focusedPackage] is only a hint, and may be null: the window this app itself
     * is showing has focus while its own button is being tapped, so the DeX
     * display's own focus can legitimately be unknown at that moment. When the hint
     * doesn't resolve, the display's single visible task — and failing that, its
     * single task of any kind — is unambiguous enough to act on.
     */
    fun parseFocusedTask(
        amStackList: String,
        displayId: Int,
        focusedPackage: String?,
    ): TaskWindow? {
        var currentDisplay: Int? = null
        val candidates = mutableListOf<TaskWindow>()
        for (line in amStackList.lineSequence()) {
            ROOT_TASK_HEADER.find(line)?.let { currentDisplay = it.groupValues[1].toIntOrNull() }
            if (currentDisplay != displayId) continue
            val match = CHILD_TASK_LINE.find(line) ?: continue
            val taskId = match.groupValues[1].toIntOrNull() ?: continue
            val component = match.groupValues[2]
            val visible = VISIBLE_FLAG.find(line)?.groupValues?.get(1) == "true"
            val task = TaskWindow(taskId, component, parseBounds(match.groupValues), visible)
            if (focusedPackage != null && component.substringBefore('/') == focusedPackage) {
                return task
            }
            candidates += task
        }
        return candidates.singleOrNull { it.visible } ?: candidates.singleOrNull()
    }

    /**
     * Every `am stack list` line belonging to [displayId], header lines included,
     * verbatim. For the diagnostics report rather than for acting on: when a
     * window control does nothing, what's actually on the display — whether
     * there's a home task behind the freeform ones, what windowing mode each is
     * in — is the thing worth reading, and guessing at it from the symptom is how
     * this project has gone wrong before. Returns a placeholder rather than an
     * empty string when the display has no tasks, since "no tasks" is itself a
     * finding and an empty section looks like a failed command.
     */
    fun displaySection(amStackList: String, displayId: Int): String {
        var currentDisplay: Int? = null
        val lines = mutableListOf<String>()
        for (line in amStackList.lineSequence()) {
            val header = ROOT_TASK_HEADER.find(line)
            if (header != null) {
                currentDisplay = header.groupValues[1].toIntOrNull()
            }
            if (currentDisplay == displayId) lines += line.trimEnd()
        }
        return if (lines.isEmpty()) "(no tasks listed for display $displayId)"
        else lines.joinToString("\n")
    }

    private fun parseBounds(groups: List<String>): Bounds? {
        val left = groups.getOrNull(3)?.toIntOrNull() ?: return null
        val top = groups.getOrNull(4)?.toIntOrNull() ?: return null
        val right = groups.getOrNull(5)?.toIntOrNull() ?: return null
        val bottom = groups.getOrNull(6)?.toIntOrNull() ?: return null
        return Bounds(left, top, right, bottom)
    }

    /**
     * Whether a window is effectively maximized — what decides which way a toggle
     * goes. Deliberately a bounds comparison rather than a windowing-mode read:
     * `am stack list` never prints the windowing mode, and a maximized window can
     * still fall a little short of the display when a taskbar takes a strip of it,
     * so this allows a [MAXIMIZED_COVERAGE] margin either way. Unknown bounds count
     * as not maximized, so a toggle with nothing to go on shrinks rather than grows
     * (the recoverable direction: a window too small can still be dragged).
     */
    fun isMaximized(bounds: Bounds?, displayWidth: Int, displayHeight: Int): Boolean {
        if (bounds == null || displayWidth <= 0 || displayHeight <= 0) return false
        return bounds.width >= displayWidth * MAXIMIZED_COVERAGE &&
            bounds.height >= displayHeight * MAXIMIZED_COVERAGE
    }

    private const val MAXIMIZED_COVERAGE = 0.9f
}
