package com.localdex.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /**
     * Verbatim from a device (display 23, One UI on Android 17), trimmed only in
     * the configuration lines' irrelevant middles. Three root tasks, in the order
     * the device printed them: the desktop selector (recents), the DeX launcher
     * (home), and the one actual app window. Written from real output rather than
     * from memory on purpose — a fixture invented to match the parser is how an
     * earlier bug in this file got validated instead of caught.
     */
    private val dexDisplayStackList = """
        RootTask id=1013 bounds=[47,47][1437,1097] displayId=23 userId=0
         configuration={0.8 515mcc2mnc ldltr 240dpi lrg land winConfig={ mBounds=Rect(47, 47 - 1437, 1097) mWindowingMode=freeform mActivityType=recents mAlwaysOnTop=on mRotation=ROTATION_0} s.6921}
          taskId=1013: com.sec.android.app.launcher/com.android.quickstep.RecentsActivity bounds=[47,47][1437,1097] userId=0 visible=true topActivity=ComponentInfo{com.sec.android.app.launcher/com.android.quickstep.RecentsActivity}
        RootTask id=1010 bounds=[0,0][1920,1440] displayId=23 userId=0
         configuration={0.8 515mcc2mnc ldltr 240dpi xlrg land winConfig={ mBounds=Rect(0, 0 - 1920, 1440) mWindowingMode=fullscreen mActivityType=home mAlwaysOnTop=undefined mRotation=ROTATION_0} s.6921}
          taskId=1011: com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher bounds=[0,0][1920,1440] userId=0 visible=true topActivity=ComponentInfo{com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher}
        RootTask id=1012 bounds=[288,216][1632,1224] displayId=23 userId=0
         configuration={0.8 515mcc2mnc ldltr 240dpi lrg land winConfig={ mBounds=Rect(288, 216 - 1632, 1224) mWindowingMode=freeform mActivityType=standard mAlwaysOnTop=off mRotation=ROTATION_0} s.6921}
          taskId=1012: com.android.chrome/com.google.android.apps.chrome.Main bounds=[288,216][1632,1224] userId=0 visible=false
    """.trimIndent()

    @Test
    fun `parseFocusedTask ignores the home and recents tasks when falling back`() {
        // The device logged "No task to move on display 23" here: home and recents
        // were two visible candidates, so singleOrNull matched neither.
        val task = WindowSnapParser.parseFocusedTask(dexDisplayStackList, 23, focusedPackage = null)
        assertEquals(1012, task?.taskId)
        assertEquals("com.android.chrome/com.google.android.apps.chrome.Main", task?.component)
    }

    @Test
    fun `parseFocusedTask does not snap the launcher when the desktop has focus`() {
        // Both the home task and the selector are com.sec.android.app.launcher, so
        // the hint alone would have matched one of them and moved the wrong window.
        val task = WindowSnapParser.parseFocusedTask(
            dexDisplayStackList, 23, focusedPackage = "com.sec.android.app.launcher"
        )
        assertEquals(1012, task?.taskId)
    }

    @Test
    fun `parseFocusedTask still honours the hint for a real app`() {
        val task = WindowSnapParser.parseFocusedTask(
            dexDisplayStackList, 23, focusedPackage = "com.android.chrome"
        )
        assertEquals(1012, task?.taskId)
    }

    @Test
    fun `parseFocusedTask keeps working when no activity type is printed`() {
        // Older or other builds may not print mActivityType; an unknown type must
        // degrade to the previous behaviour rather than filter everything out.
        val noTypes = dexDisplayStackList.lines()
            .filterNot { it.contains("mActivityType=") }
            .joinToString("\n")
        val task = WindowSnapParser.parseFocusedTask(noTypes, 23, focusedPackage = "com.android.chrome")
        assertEquals(1012, task?.taskId)
    }

    /**
     * Verbatim from the device right after minimizing Chrome and nothing else
     * (display 25). This is the state the Windows list exists to rescue: the
     * window is gone from the screen but its task is still listed, with its
     * bounds unchanged.
     */
    private val minimizedStackList = """
        RootTask id=1022 bounds=[0,0][1920,1440] displayId=25 userId=0
         configuration={0.8 ldltr 240dpi xlrg land winConfig={ mBounds=Rect(0, 0 - 1920, 1440) mWindowingMode=fullscreen mActivityType=home mAlwaysOnTop=undefined} s.8324}
          taskId=1023: com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher bounds=[0,0][1920,1440] userId=0 visible=true topActivity=ComponentInfo{com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher}
        RootTask id=1024 bounds=[288,216][1632,1224] displayId=25 userId=0
         configuration={0.8 ldltr 240dpi lrg land winConfig={ mBounds=Rect(288, 216 - 1632, 1224) mWindowingMode=freeform mActivityType=standard mAlwaysOnTop=off} s.8324}
          taskId=1024: com.android.chrome/com.google.android.apps.chrome.Main bounds=[288,216][1632,1224] userId=0 visible=false
    """.trimIndent()

    @Test
    fun `parseTasks keeps a minimized window, with its bounds`() {
        val tasks = WindowSnapParser.parseTasks(minimizedStackList, 25)
        assertEquals(1, tasks.size)
        val chrome = tasks.single()
        assertEquals(1024, chrome.taskId)
        assertFalse(chrome.visible)
        // Bounds survive minimizing, so restoring does not have to invent a size.
        assertEquals(WindowSnapParser.Bounds(288, 216, 1632, 1224), chrome.bounds)
    }

    @Test
    fun `parseTasks leaves out home and recents`() {
        val tasks = WindowSnapParser.parseTasks(dexDisplayStackList, 23)
        assertEquals(listOf(1012), tasks.map { it.taskId })
    }

    @Test
    fun `parseTasks returns every app window in listed order`() {
        val twoApps = dexDisplayStackList + "\n" + """
            RootTask id=1030 bounds=[0,0][960,720] displayId=23 userId=0
             configuration={winConfig={ mWindowingMode=freeform mActivityType=standard}}
              taskId=1030: com.example.other/.Main bounds=[0,0][960,720] userId=0 visible=true
        """.trimIndent()
        assertEquals(listOf(1012, 1030), WindowSnapParser.parseTasks(twoApps, 23).map { it.taskId })
    }

    @Test
    fun `parseTasks returns nothing for a display with only home on it`() {
        assertEquals(emptyList<Int>(), WindowSnapParser.parseTasks(minimizedStackList, 99).map { it.taskId })
    }

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

    // --- overlay display lookup -------------------------------------------------

    /** Shape of a One UI `dumpsys display` once overlay_display_devices is set. */
    private val dumpsysDisplayWithOverlay = """
        DISPLAY MANAGER (dumpsys display)
          mOnlyCore=false
          Display Devices: size=2
            DisplayDeviceInfo{"Built-in Screen": uniqueId="local:4619827259835644672", 1080 x 2340}
            DisplayDeviceInfo{"Overlay #1: 1920x1440, 240 dpi": uniqueId="overlay:1", 1920 x 1440}
          Logical Displays: size=2
            Display 0:
              mDisplayId=0
              mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, displayGroupId 0, FLAG_TRUSTED, real 1080 x 2340}
              mPrimaryDisplayDevice=Built-in Screen
            Display 2:
              mDisplayId=2
              mBaseDisplayInfo=DisplayInfo{"Overlay #1: 1920x1440, 240 dpi", displayId 2, displayGroupId 0, FLAG_PRESENTATION, real 1920 x 1440}
              mPrimaryDisplayDevice=Overlay #1: 1920x1440, 240 dpi
    """.trimIndent()

    @Test
    fun `finds the overlay display id`() {
        assertEquals(2, WindowSnapParser.parseOverlayDisplayId(dumpsysDisplayWithOverlay))
    }

    @Test
    fun `overlay display id is null before the display exists`() {
        // What the very first poll sees: the setting is written but the system has
        // not created the device yet, which is why the lookup retries at all.
        val noOverlay = """
            DISPLAY MANAGER (dumpsys display)
              Logical Displays: size=1
                Display 0:
                  mDisplayId=0
                  mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, displayGroupId 0, real 1080 x 2340}
                  mPrimaryDisplayDevice=Built-in Screen
        """.trimIndent()
        assertNull(WindowSnapParser.parseOverlayDisplayId(noOverlay))
    }

    @Test
    fun `overlay display id never picks the built-in screen`() {
        // The failure that would matter most: mirroring display 0 would put the
        // phone's own screen inside the viewer, which looks like a working session.
        assertNotEquals(0, WindowSnapParser.parseOverlayDisplayId(dumpsysDisplayWithOverlay))
    }

    @Test
    fun `falls back to mPrimaryDisplayDevice when no DisplayInfo name is printed`() {
        val olderFormat = """
            Logical Displays: size=2
              Display 0:
                mDisplayId=0
                mPrimaryDisplayDevice=Built-in Screen
              Display 5:
                mDisplayId=5
                mPrimaryDisplayDevice=Overlay #1: 1920x1440, 240 dpi
        """.trimIndent()
        assertEquals(5, WindowSnapParser.parseOverlayDisplayId(olderFormat))
    }

    @Test
    fun `overlay lookup is not fooled by the uniqueId of a display device`() {
        // uniqueId="overlay:1" is printed in the Display Devices section, which
        // carries no logical display id. Matching it there would attach the word
        // "overlay" to whatever id happened to be in scope.
        val devicesOnly = """
            Display Devices: size=2
              DisplayDeviceInfo{"Built-in Screen": uniqueId="local:46198", 1080 x 2340}
              DisplayDeviceInfo{"Some Screen": uniqueId="overlay:1", 1920 x 1440}
            Logical Displays: size=1
              Display 0:
                mDisplayId=0
                mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, real 1080 x 2340}
                mPrimaryDisplayDevice=Built-in Screen
        """.trimIndent()
        assertNull(WindowSnapParser.parseOverlayDisplayId(devicesOnly))
    }
}
