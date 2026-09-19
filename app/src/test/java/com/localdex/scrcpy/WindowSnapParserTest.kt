package com.localdex.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samples below mirror the real `toString()`/`dump()` output verified against
 * AOSP source (DisplayContent.dump, WindowState.toString, RootTaskInfo.toString,
 * RootWindowContainer.getRootTaskInfo) — not guessed formats.
 *
 * The two-space indent on the `Display:` lines is load-bearing and deliberate:
 * RootWindowContainer.dumpDisplayContents calls `displayContent.dump(pw, "  ",
 * true)` and DisplayContent.dump prints that prefix before "Display: mDisplayId=".
 * An earlier version of this fixture left the indent off, so it agreed with a
 * column-0-anchored regex that could never match real output — the parse found
 * nothing on device while these tests passed.
 */
class WindowSnapParserTest {

    private val dumpsysWindowDisplays = """
        |WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
        |  Display: mDisplayId=0
        |    init=true mDeferredRemoval=false stopped=false
        |  mCurrentFocus=Window{a1b2c3d4 u0 com.android.launcher3/.Launcher}
        |  mFocusedApp=ActivityRecord{1111 u0 com.android.launcher3/.Launcher}
        |  Display: mDisplayId=7 (organized)
        |    init=true mDeferredRemoval=false stopped=false
        |  mCurrentFocus=Window{f00d1234 u0 com.example/.MainActivity}
        |  mFocusedApp=ActivityRecord{2222 u0 com.example/.MainActivity}
    """.trimMargin()

    private val amStackList = """
        RootTask id=12 bounds=[0,0][1080,2400] displayId=0 userId=0
         configuration={1.0 310mcc260mnc}
          taskId=12: com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher}
        RootTask id=37 bounds=[0,0][1920,1440] displayId=7 userId=0
         configuration={1.0 310mcc260mnc}
          taskId=37: com.example/com.example.MainActivity bounds=[100,80][1400,1100] userId=0 visible=true topActivity=ComponentInfo{com.example/com.example.MainActivity}
    """.trimIndent()

    // -- displaySection ------------------------------------------------------------

    @Test
    fun `displaySection keeps only the requested display's block, header included`() {
        val section = WindowSnapParser.displaySection(amStackList, 7)
        assertTrue(section.contains("RootTask id=37"))
        assertTrue(section.contains("taskId=37: com.example/com.example.MainActivity"))
        // The other display's block must not leak in — that is the whole point.
        assertFalse(section.contains("RootTask id=12"))
        assertFalse(section.contains("launcher3"))
    }

    @Test
    fun `displaySection stops collecting at the next display's header`() {
        // Display 0 comes first, so its block ends where display 7's header starts;
        // a parser that only latched the first header would swallow both.
        val section = WindowSnapParser.displaySection(amStackList, 0)
        assertTrue(section.contains("RootTask id=12"))
        assertFalse(section.contains("RootTask id=37"))
        assertFalse(section.contains("com.example"))
    }

    @Test
    fun `displaySection says so rather than returning empty for an absent display`() {
        val section = WindowSnapParser.displaySection(amStackList, 99)
        assertTrue(section.contains("no tasks listed"))
        assertTrue(section.contains("99"))
    }

    // -- parseFocusedPackage -------------------------------------------------------

    @Test
    fun `parseFocusedPackage reads the package under the matching display header`() {
        assertEquals("com.example", WindowSnapParser.parseFocusedPackage(dumpsysWindowDisplays, 7))
        assertEquals("com.android.launcher3", WindowSnapParser.parseFocusedPackage(dumpsysWindowDisplays, 0))
    }

    @Test
    fun `parseFocusedPackage returns null for a display id that is not present`() {
        assertNull(WindowSnapParser.parseFocusedPackage(dumpsysWindowDisplays, 99))
    }

    @Test
    fun `parseFocusedPackage returns null when the display has no focus`() {
        val text = """
            |  Display: mDisplayId=7
            |  mCurrentFocus=null
        """.trimMargin()
        assertNull(WindowSnapParser.parseFocusedPackage(text, 7))
    }

    @Test
    fun `parseFocusedPackage ignores focus lines under a different display's header`() {
        val text = """
            |  Display: mDisplayId=0
            |  mCurrentFocus=Window{aaaa u0 com.other/.Other}
            |  Display: mDisplayId=7
            |  mCurrentFocus=null
        """.trimMargin()
        assertNull(WindowSnapParser.parseFocusedPackage(text, 7))
    }

    // -- parseFocusedTask -----------------------------------------------------------

    @Test
    fun `parseFocusedTask matches by package and captures component and bounds`() {
        val task = WindowSnapParser.parseFocusedTask(amStackList, 7, "com.example")!!
        assertEquals(37, task.taskId)
        // Fully-qualified form: what `am start -n` needs, not the short form the
        // focused-window title uses.
        assertEquals("com.example/com.example.MainActivity", task.component)
        assertEquals(WindowSnapParser.Bounds(100, 80, 1400, 1100), task.bounds)
    }

    @Test
    fun `parseFocusedTask returns null for a display id with no root task`() {
        assertNull(WindowSnapParser.parseFocusedTask(amStackList, 99, "com.example"))
    }

    @Test
    fun `parseFocusedTask falls back to the only task on the display when the package does not match`() {
        // Covers the window title using a different (short vs fully-qualified) form
        // of the component than the task list — package matching still fails, but
        // there's only one window open on this display so it's unambiguous anyway.
        val task = WindowSnapParser.parseFocusedTask(amStackList, 7, "com.mismatched.title.form")!!
        assertEquals(37, task.taskId)
    }

    @Test
    fun `parseFocusedTask works with no focus hint at all`() {
        // The real case behind "No focused window on the DeX display": this app's
        // own window holds focus while its button is tapped, so the hint is null.
        val task = WindowSnapParser.parseFocusedTask(amStackList, 7, null)!!
        assertEquals(37, task.taskId)
    }

    @Test
    fun `parseFocusedTask prefers the visible task when the hint does not resolve`() {
        val text = """
            |RootTask id=1 bounds=[0,0][1920,1440] displayId=7 userId=0
            | configuration={1.0}
            |  taskId=1: com.first/com.first.Main bounds=[0,0][1920,1440] userId=0 visible=true topActivity=ComponentInfo{com.first/com.first.Main}
            |RootTask id=2 bounds=[0,0][960,1440] displayId=7 userId=0
            | configuration={1.0}
            |  taskId=2: com.second/com.second.Main bounds=[0,0][960,1440] userId=0 visible=false topActivity=ComponentInfo{com.second/com.second.Main}
        """.trimMargin()
        val task = WindowSnapParser.parseFocusedTask(text, 7, null)!!
        assertEquals(1, task.taskId)
        assertTrue(task.visible)
    }

    @Test
    fun `parseFocusedTask returns null when several visible tasks match nothing`() {
        // Two windows side by side, neither matching the hint: genuinely ambiguous,
        // so it declines rather than moving whichever one happened to be parsed first.
        val text = """
            |RootTask id=1 bounds=[0,0][960,1440] displayId=7 userId=0
            | configuration={1.0}
            |  taskId=1: com.first/com.first.Main bounds=[0,0][960,1440] userId=0 visible=true topActivity=ComponentInfo{com.first/com.first.Main}
            |RootTask id=2 bounds=[960,0][1920,1440] displayId=7 userId=0
            | configuration={1.0}
            |  taskId=2: com.second/com.second.Main bounds=[960,0][1920,1440] userId=0 visible=true topActivity=ComponentInfo{com.second/com.second.Main}
        """.trimMargin()
        assertNull(WindowSnapParser.parseFocusedTask(text, 7, "com.unrelated"))
    }

    @Test
    fun `parseFocusedTask tolerates a task line with no bounds`() {
        // RootTaskInfo.toString only appends bounds when childTaskBounds is non-null.
        val text = """
            RootTask id=37 bounds=[0,0][1920,1440] displayId=7 userId=0
             configuration={1.0}
              taskId=37: com.example/com.example.MainActivity userId=0 visible=true
        """.trimIndent()
        val task = WindowSnapParser.parseFocusedTask(text, 7, "com.example")!!
        assertEquals(37, task.taskId)
        assertEquals("com.example/com.example.MainActivity", task.component)
        assertNull(task.bounds)
    }

    // -- isMaximized ----------------------------------------------------------------

    @Test
    fun `isMaximized is true for bounds covering the whole display`() {
        assertTrue(WindowSnapParser.isMaximized(WindowSnapParser.Bounds(0, 0, 1920, 1440), 1920, 1440))
    }

    @Test
    fun `isMaximized allows a taskbar-sized strip off the full size`() {
        // A maximized window sitting above a taskbar still counts as maximized.
        assertTrue(WindowSnapParser.isMaximized(WindowSnapParser.Bounds(0, 0, 1920, 1380), 1920, 1440))
    }

    @Test
    fun `isMaximized is false for a restored window`() {
        // The 70% centred rect a restore produces.
        assertFalse(WindowSnapParser.isMaximized(WindowSnapParser.Bounds(288, 216, 1632, 1224), 1920, 1440))
    }

    @Test
    fun `isMaximized is false for a half-display snap`() {
        assertFalse(WindowSnapParser.isMaximized(WindowSnapParser.Bounds(0, 0, 960, 1440), 1920, 1440))
    }

    @Test
    fun `isMaximized is false when bounds are unknown`() {
        // Toggling with nothing to go on should shrink, not grow — a window too
        // small can still be dragged back, one stuck fullscreen is the bug itself.
        assertFalse(WindowSnapParser.isMaximized(null, 1920, 1440))
    }

    @Test
    fun `isMaximized is false for a display with no size yet`() {
        assertFalse(WindowSnapParser.isMaximized(WindowSnapParser.Bounds(0, 0, 1920, 1440), 0, 0))
    }
}
