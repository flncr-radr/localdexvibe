package com.localdex.scrcpy

/**
 * Text parsing for the two `am`/`dumpsys` shell commands behind [WindowSnap].
 * Dependency-free (no Android imports) so it can be unit tested on the JVM,
 * the same way [ScrcpyProtocol] is — the risk here is entirely in matching
 * these formats correctly, not in anything device-specific.
 */
internal object WindowSnapParser {
    private val DISPLAY_HEADER = Regex("""^Display: mDisplayId=(\d+)""")
    private val CURRENT_FOCUS = Regex("""^\s*mCurrentFocus=Window\{\S+ u\d+ ([^}]+)\}""")
    private val CURRENT_FOCUS_NULL = Regex("""^\s*mCurrentFocus=null\s*$""")

    private val ROOT_TASK_HEADER = Regex("""^RootTask id=\d+ .*displayId=(\d+)""")
    private val CHILD_TASK_LINE = Regex("""^\s*taskId=(\d+): (\S+)""")

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
     * The task id on [displayId] whose component's package matches [focusedPackage],
     * from `am stack list`. Falls back to the display's only child task if there's
     * exactly one and none matched by package — a single open window, the common
     * case for this app, needs no match at all to know which task to snap.
     */
    fun parseTaskId(amStackList: String, displayId: Int, focusedPackage: String): Int? {
        var currentDisplay: Int? = null
        val candidates = mutableListOf<Int>()
        for (line in amStackList.lineSequence()) {
            ROOT_TASK_HEADER.find(line)?.let { currentDisplay = it.groupValues[1].toIntOrNull() }
            if (currentDisplay != displayId) continue
            val match = CHILD_TASK_LINE.find(line) ?: continue
            val taskId = match.groupValues[1].toIntOrNull() ?: continue
            val pkg = match.groupValues[2].substringBefore('/')
            if (pkg == focusedPackage) return taskId
            candidates += taskId
        }
        return candidates.singleOrNull()
    }
}
