package com.localdex.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Samples below mirror the real `toString()`/`dump()` output verified against
 * AOSP source (DisplayContent.dump, WindowState.toString, RootTaskInfo.toString,
 * RootWindowContainer.getRootTaskInfo) — not guessed formats.
 */
class WindowSnapParserTest {

    private val dumpsysWindowDisplays = """
        Display: mDisplayId=0
          init=true mDeferredRemoval=false stopped=false
          mCurrentFocus=Window{a1b2c3d4 u0 com.android.launcher3/.Launcher}
          mLastOrientation=0
        Display: mDisplayId=7 (organized)
          init=true mDeferredRemoval=false stopped=false
          mCurrentFocus=Window{f00d1234 u0 com.example/.MainActivity}
          mLastOrientation=0
    """.trimIndent()

    private val amStackList = """
        RootTask id=12 bounds=[0,0][1080,2400] displayId=0 userId=0
         configuration={1.0 310mcc260mnc}
          taskId=12: com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher}
        RootTask id=37 bounds=[0,0][1920,1440] displayId=7 userId=0
         configuration={1.0 310mcc260mnc}
          taskId=37: com.example/com.example.MainActivity bounds=[0,0][1920,1440] userId=0 visible=true topActivity=ComponentInfo{com.example/com.example.MainActivity}
    """.trimIndent()

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
            Display: mDisplayId=7
              mCurrentFocus=null
        """.trimIndent()
        assertNull(WindowSnapParser.parseFocusedPackage(text, 7))
    }

    @Test
    fun `parseFocusedPackage ignores focus lines under a different display's header`() {
        val text = """
            Display: mDisplayId=0
              mCurrentFocus=Window{aaaa u0 com.other/.Other}
            Display: mDisplayId=7
              mCurrentFocus=null
        """.trimIndent()
        assertNull(WindowSnapParser.parseFocusedPackage(text, 7))
    }

    // -- parseTaskId ----------------------------------------------------------------

    @Test
    fun `parseTaskId matches by package under the matching display header`() {
        assertEquals(37, WindowSnapParser.parseTaskId(amStackList, 7, "com.example"))
        assertEquals(12, WindowSnapParser.parseTaskId(amStackList, 0, "com.android.launcher3"))
    }

    @Test
    fun `parseTaskId returns null for a display id with no root task`() {
        assertNull(WindowSnapParser.parseTaskId(amStackList, 99, "com.example"))
    }

    @Test
    fun `parseTaskId falls back to the only child task on the display when the package does not match`() {
        // Covers the window title using a different (short vs fully-qualified) form
        // of the component than the task list — package matching still fails, but
        // there's only one window open on this display so it's unambiguous anyway.
        assertEquals(37, WindowSnapParser.parseTaskId(amStackList, 7, "com.mismatched.title.form"))
    }

    @Test
    fun `parseTaskId returns null when several tasks are on the display and none match`() {
        val text = """
            RootTask id=1 bounds=[0,0][960,1440] displayId=7 userId=0
             configuration={1.0}
              taskId=1: com.first/com.first.Main bounds=[0,0][960,1440] userId=0 visible=true topActivity=ComponentInfo{com.first/com.first.Main}
            RootTask id=2 bounds=[960,0][1920,1440] displayId=7 userId=0
             configuration={1.0}
              taskId=2: com.second/com.second.Main bounds=[960,0][1920,1440] userId=0 visible=false topActivity=ComponentInfo{com.second/com.second.Main}
        """.trimIndent()
        assertNull(WindowSnapParser.parseTaskId(text, 7, "com.unrelated"))
    }
}
