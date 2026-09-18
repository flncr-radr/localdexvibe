package com.localdex.scrcpy

/**
 * Text parsing for the `am`/`dumpsys` shell commands behind [WindowSnap].
 * Dependency-free (no Android imports) so it can be unit tested on the JVM,
 * the same way [ScrcpyProtocol] is — the risk here is entirely in matching
 * these formats correctly, not in anything device-specific.
 */
internal object WindowSnapParser {
    private val DISPLAY_HEADER = Regex("""^Display: mDisplayId=(\d+)""")
    private val CURRENT_FOCUS = Regex("""^\s*mCurrentFocus=Window\{\S+ u\d+ ([^}]+)\}""")
    private val CURRENT_FOCUS_NULL = Regex("""^\s*mCurrentFocus=null\s*$""")

    private val ROOT_TASK_HEADER = Regex("""^RootTask id=\d+ .*displayId=(\d+)""")
    private val CHILD_TASK_LINE =
        Regex("""^\s*taskId=(\d+): (\S+)(?: bounds=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)])?""")

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
    data class TaskWindow(val taskId: Int, val component: String, val bounds: Bounds?)

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
     * The task on [displayId] whose component's package matches [focusedPackage],
     * from `am stack list`. Falls back to the display's only child task if there's
     * exactly one and none matched by package — a single open window, the common
     * case for this app, needs no match at all to know which task to act on.
     */
    fun parseFocusedTask(
        amStackList: String,
        displayId: Int,
        focusedPackage: String,
    ): TaskWindow? {
        var currentDisplay: Int? = null
        val candidates = mutableListOf<TaskWindow>()
        for (line in amStackList.lineSequence()) {
            ROOT_TASK_HEADER.find(line)?.let { currentDisplay = it.groupValues[1].toIntOrNull() }
            if (currentDisplay != displayId) continue
            val match = CHILD_TASK_LINE.find(line) ?: continue
            val taskId = match.groupValues[1].toIntOrNull() ?: continue
            val component = match.groupValues[2]
            val bounds = parseBounds(match.groupValues)
            val task = TaskWindow(taskId, component, bounds)
            if (component.substringBefore('/') == focusedPackage) return task
            candidates += task
        }
        return candidates.singleOrNull()
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
