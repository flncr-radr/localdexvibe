package com.localdex

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.localdex.scrcpy.ScrcpySession
import com.localdex.scrcpy.WindowSnap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fullscreen interactive view of the DeX display.
 *
 * Touch and a real hardware/Bluetooth keyboard are both forwarded to the mirrored
 * display; the system Back gesture/button is forwarded as a DeX Back key. The side
 * control tab carries the rest: window snapping, and stand-ins for DeX's own
 * taskbar buttons, which ignore injected mouse clicks (see sendKeyToDex). Closing
 * happens through that tab's Stop button (with confirmation) or the persistent
 * notification's Stop action.
 */
class ViewerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ViewerActivity"

        /** Kept local to the phone rather than forwarded to DeX. */
        private val LOCAL_KEYCODES = setOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
        )

        /**
         * Meta+arrow window snapping — not a documented Android shortcut, so this
         * app drives it itself (see WindowSnap). Held locally rather than forwarded:
         * DeX has nothing bound to these combinations anyway.
         */
        private val SNAP_DIRECTIONS = mapOf(
            KeyEvent.KEYCODE_DPAD_LEFT to WindowSnap.Direction.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to WindowSnap.Direction.RIGHT,
            KeyEvent.KEYCODE_DPAD_UP to WindowSnap.Direction.MAXIMIZE,
            KeyEvent.KEYCODE_DPAD_DOWN to WindowSnap.Direction.RESTORE,
        )
    }

    private lateinit var root: CoordinatorLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var controlPanel: View
    private lateinit var controlPanelHandle: View
    private lateinit var viewerSnapLeftButton: Button
    private lateinit var viewerSnapRightButton: Button
    private lateinit var viewerRestoreButton: Button
    private lateinit var viewerBackButton: Button
    private lateinit var viewerHomeButton: Button
    private lateinit var viewerShortcutsButton: Button
    private lateinit var viewerStatsButton: Button
    private lateinit var viewerStopButton: Button
    private lateinit var viewerStatsOverlay: TextView
    private lateinit var clipboardManager: ClipboardManager

    private var surfaceReady = false
    private var surfaceGivenToDecoder = false
    private var freeformWarningWatchStarted = false
    private var controlPanelExpanded = false
    private var controlPanelPositioned = false
    private var statsVisible = false
    private var statsJob: Job? = null

    /**
     * The last text either sent to, or received from, the device's clipboard —
     * checked in both directions before acting, so setting the phone's clipboard
     * from a device change doesn't immediately echo back to the device, and vice
     * versa.
     */
    @Volatile
    private var lastSyncedClipboard: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = currentClipboardText() ?: return@OnPrimaryClipChangedListener
        if (text == lastSyncedClipboard) return@OnPrimaryClipChangedListener
        lastSyncedClipboard = text
        session?.controller?.sendClipboard(text)
    }

    private val session: ScrcpySession?
        get() = ScrcpySession.current

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activeSession = session
        if (activeSession == null) {
            finish()
            return
        }

        setContentView(R.layout.activity_viewer)
        root = findViewById(R.id.viewerRoot)
        surfaceView = findViewById(R.id.surfaceView)
        statusText = findViewById(R.id.viewerStatus)
        controlPanel = findViewById(R.id.controlPanel)
        controlPanelHandle = findViewById(R.id.controlPanelHandle)
        viewerSnapLeftButton = findViewById(R.id.viewerSnapLeftButton)
        viewerSnapRightButton = findViewById(R.id.viewerSnapRightButton)
        viewerRestoreButton = findViewById(R.id.viewerRestoreButton)
        viewerBackButton = findViewById(R.id.viewerBackButton)
        viewerHomeButton = findViewById(R.id.viewerHomeButton)
        viewerShortcutsButton = findViewById(R.id.viewerShortcutsButton)
        viewerStatsButton = findViewById(R.id.viewerStatsButton)
        viewerStopButton = findViewById(R.id.viewerStopButton)
        viewerStatsOverlay = findViewById(R.id.viewerStatsOverlay)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        setupControlPanel()
        viewerSnapLeftButton.setOnClickListener { triggerSnap(WindowSnap.Direction.LEFT) }
        viewerSnapRightButton.setOnClickListener { triggerSnap(WindowSnap.Direction.RIGHT) }
        viewerRestoreButton.setOnClickListener { triggerSnap(WindowSnap.Direction.TOGGLE) }
        viewerBackButton.setOnClickListener { sendKeyToDex(KeyEvent.KEYCODE_BACK) }
        viewerHomeButton.setOnClickListener { sendKeyToDex(KeyEvent.KEYCODE_HOME) }
        viewerShortcutsButton.setOnClickListener {
            session?.controller?.sendMetaKeyPress(KeyEvent.KEYCODE_SLASH)
        }
        viewerStatsButton.setOnClickListener { toggleStats() }
        viewerStopButton.setOnClickListener { confirmStop() }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                surfaceGivenToDecoder = false
                offerSurface()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                surfaceGivenToDecoder = false
                session?.videoDecoder?.clearSurface()
            }
        })

        surfaceView.setOnTouchListener { view, event ->
            val s = session ?: return@setOnTouchListener false
            s.controller?.forwardMotionEvent(
                event, view.width, view.height, s.videoWidth, s.videoHeight
            )
            true
        }

        // Fold/unfold and rotation change the container size without recreating the
        // activity (configChanges); keep the surface at the video's aspect ratio.
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                val s = session ?: return@addOnLayoutChangeListener
                if (s.videoWidth > 0) applyAspectRatio(s.videoWidth, s.videoHeight)
            }
        }

        lifecycleScope.launch {
            activeSession.state.collectLatest { state ->
                when (state) {
                    is ScrcpySession.State.Starting -> statusText.text = state.message
                    is ScrcpySession.State.Running -> {
                        statusText.visibility = View.GONE
                        applyAspectRatio(state.videoWidth, state.videoHeight)
                        offerSurface()
                        watchFreeformResult()
                        attachClipboardBridge()
                    }
                    is ScrcpySession.State.Stopped -> {
                        if (state.error != null) {
                            AlertDialog.Builder(this@ViewerActivity)
                                .setTitle("Session ended")
                                .setMessage(state.error)
                                .setPositiveButton("OK") { _, _ -> finish() }
                                .setOnDismissListener { finish() }
                                .show()
                        } else {
                            finish()
                        }
                    }
                }
            }
        }
    }

    /** Hands the surface to the decoder once both exist. Idempotent. */
    private fun offerSurface() {
        if (!surfaceReady || surfaceGivenToDecoder) return
        val decoder = session?.videoDecoder ?: return
        decoder.setSurface(surfaceView.holder.surface)
        surfaceGivenToDecoder = true
    }

    /**
     * Wires the device→phone half of clipboard sync once the session has a
     * Controller. Safe to call repeatedly (from both onResume and the state
     * collector) — it's a plain reassignment, not a subscription that would stack.
     */
    private fun attachClipboardBridge() {
        session?.controller?.onClipboardReceived = { text ->
            if (text != lastSyncedClipboard) {
                lastSyncedClipboard = text
                runOnUiThread {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("DeX clipboard", text))
                }
            }
        }
    }

    private fun currentClipboardText(): String? {
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Warns once, by Toast, if forcing freeform mode on this display fails — this is
     * where the user actually notices apps opening fullscreen with no window
     * controls, so it's worth flagging even though the DeX session itself is fine.
     */
    private fun watchFreeformResult() {
        if (freeformWarningWatchStarted) return
        freeformWarningWatchStarted = true
        val activeSession = session ?: return
        lifecycleScope.launch {
            while (activeSession.freeformForceInProgress) delay(300)
            if (activeSession.freeformForceFailed) {
                Toast.makeText(
                    this@ViewerActivity,
                    "Couldn't switch to freeform mode — apps may open fullscreen.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Sizes the SurfaceView to exactly the video aspect ratio, centered. */
    private fun applyAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0) return
        root.post {
            val containerWidth = root.width
            val containerHeight = root.height
            if (containerWidth == 0 || containerHeight == 0) return@post

            val scale = minOf(
                containerWidth.toFloat() / videoWidth,
                containerHeight.toFloat() / videoHeight
            )
            val params = surfaceView.layoutParams as CoordinatorLayout.LayoutParams
            params.width = (videoWidth * scale).toInt()
            params.height = (videoHeight * scale).toInt()
            params.gravity = android.view.Gravity.CENTER
            surfaceView.layoutParams = params
        }
    }

    /**
     * Starts the panel collapsed to just the handle peeking from the right edge,
     * and wires the tap-to-toggle. Horizontal position (collapsed/expanded)
     * is driven by translationX, set once the panel has a measured width — it
     * starts at 0 on the very first layout pass, so setting it any earlier would
     * just get overwritten with 0. Vertical position is re-applied on every layout
     * change instead (fold/rotation can change root's height while the activity
     * survives via configChanges), which is safe since it doesn't interact with
     * the toggle state.
     *
     * The handle's own bounds are also excluded from the system's edge-swipe
     * gesture (setSystemGestureExclusionRects) — at the screen's edge, that
     * gesture would otherwise compete with taps meant for the handle and can win,
     * which is what made the earlier (thinner, 4dp) grip so hard to hit reliably.
     */
    private fun setupControlPanel() {
        val panelPositionFraction = Prefs.getPanelPositionFraction(this)
        controlPanel.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (root.height > 0) {
                // The layout centers the panel by default (layout_gravity center_vertical,
                // i.e. fraction 0.5); this is the offset from that baseline needed to
                // land at the configured fraction from the bottom instead. Clamped to
                // the slack a centered panel actually has, so that offset can't push
                // the panel's far edge off screen — with enough buttons in it the
                // panel is nearly as tall as the screen and has almost none.
                val slack = ((root.height - view.height) / 2f).coerceAtLeast(0f)
                view.translationY = ((0.5f - panelPositionFraction) * root.height)
                    .coerceIn(-slack, slack)
            }

            controlPanelHandle.systemGestureExclusionRects =
                listOf(android.graphics.Rect(0, 0, controlPanelHandle.width, controlPanelHandle.height))

            if (controlPanelPositioned) return@addOnLayoutChangeListener
            val hiddenOffset = view.width - controlPanelHandle.width
            if (hiddenOffset <= 0) return@addOnLayoutChangeListener
            controlPanelPositioned = true
            view.translationX = hiddenOffset.toFloat()
        }
        controlPanel.setOnClickListener { toggleControlPanel() }
    }

    private fun toggleControlPanel() {
        val hiddenOffset = (controlPanel.width - controlPanelHandle.width).toFloat()
        controlPanelExpanded = !controlPanelExpanded
        controlPanel.animate().translationX(if (controlPanelExpanded) 0f else hiddenOffset).start()
    }

    /**
     * The LocalDex-side equivalents of DeX's own taskbar cluster. DeX draws a
     * back/home pair down there, but those glyphs don't react to the mouse events
     * this app injects, so these send the keys instead — which scrcpy stamps with
     * the virtual display's id before injecting (Device.injectEvent ->
     * InputManager.setDisplayId), so they land on DeX rather than on the phone.
     *
     * HOME is the "show desktop" one: PhoneWindowManager routes a short press
     * through handleShortPressOnHome(event.getDisplayId()) -> startDockOrHome(
     * displayId, ...), so it raises that display's own home — the DeX desktop —
     * and leaves the phone's launcher alone.
     *
     * There is deliberately no third button for DeX's ≡ (window overview /
     * workspaces). The obvious candidate, KEYCODE_RECENT_APPS, is display-blind in
     * the framework: PhoneWindowManager hands it to statusbar.showRecentApps()
     * with no display id at all, so it would pop the *phone's* recents over the
     * viewer. Samsung documents no shortcut for DeX workspaces either — hence the
     * Shortcuts button (Meta+/), which opens DeX's own shortcut list so its real
     * bindings can be read off the device instead of guessed at here.
     */
    private fun sendKeyToDex(keycode: Int) {
        session?.controller?.sendKeyPress(keycode)
    }

    private fun confirmStop() {
        val displayId = session?.displayId ?: -1
        val displayNote = if (displayId >= 0) "\n\nVirtual display id: $displayId" else ""
        AlertDialog.Builder(this)
            .setTitle("Stop DeX?")
            .setMessage(
                "This ends the session and removes the virtual display. You can also " +
                    "leave with Home and come back via the notification.$displayNote"
            )
            .setPositiveButton("Stop") { _, _ ->
                DexService.stop(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Forwards every real key event — a Bluetooth/USB keyboard's presses and
     * releases, modifiers included — to DeX instead of letting the phone act on
     * them. Android already stamps each event's metaState with whichever modifiers
     * are currently held, so Ctrl/Shift/Alt combinations just work without this
     * activity computing them.
     *
     * Volume keys are left local so the phone's own volume stays reachable. A
     * hardware/3-button-nav Back key is forwarded like any other key here; gesture
     * nav's Back has no KeyEvent at all and is handled separately below.
     *
     * Meta+Left/Right/Up/Down snap the focused window to a half, the full
     * display, or a centered floating size (see WindowSnap) instead of being
     * forwarded — Android's own desktop windowing has no shortcut for this,
     * only drag gestures, and Meta+Down doubles as a fix for a window stuck
     * fullscreen where the platform's own restore control doesn't work.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in LOCAL_KEYCODES) {
            return super.dispatchKeyEvent(event)
        }
        if (event.isMetaPressed) {
            val direction = SNAP_DIRECTIONS[event.keyCode]
            if (direction != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    triggerSnap(direction)
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            session?.controller?.sendKeyEvent(event.action, event.keyCode, event.repeatCount, event.metaState)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Best-effort: the shell commands behind this (see WindowSnap) depend on
     * dumpsys text formats, not a stable API. Both the failure cases and the
     * exception path are toasted rather than failing silently — a button that
     * looks like it did nothing is worse than one that says why.
     */
    private fun triggerSnap(direction: WindowSnap.Direction) {
        val activeSession = session ?: return
        lifecycleScope.launch {
            val message = try {
                when (activeSession.snapWindow(direction)) {
                    WindowSnap.Result.MOVED, null -> null
                    WindowSnap.Result.NO_MATCHING_TASK ->
                        "Couldn't tell which DeX window to move."
                }
            } catch (e: Exception) {
                Log.w(TAG, "Window snap failed", e)
                "Couldn't move the window."
            }
            if (message != null) {
                Toast.makeText(this@ViewerActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleStats() {
        statsVisible = !statsVisible
        viewerStatsButton.text = if (statsVisible) "Hide Stats" else "Show Stats"
        viewerStatsOverlay.visibility = if (statsVisible) View.VISIBLE else View.GONE
        if (statsVisible) startStatsUpdates() else statsJob?.cancel()
    }

    /**
     * Refreshes the stats overlay once a second: fps from the delta in
     * [com.localdex.scrcpy.VideoDecoder.framesRendered] over the elapsed wall time
     * (a period average, not an instantaneous rate — smoother and cheap enough to
     * poll rather than needing VideoDecoder to push updates), plus resolution and
     * how long this viewer has had the overlay open.
     */
    private fun startStatsUpdates() {
        statsJob?.cancel()
        val startElapsed = android.os.SystemClock.elapsedRealtime()
        var lastFrames = 0L
        var lastElapsed = startElapsed
        statsJob = lifecycleScope.launch {
            while (isActive) {
                val s = session
                val decoder = s?.videoDecoder
                if (s != null && decoder != null) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val frames = decoder.framesRendered
                    val elapsedMs = now - lastElapsed
                    val fps = if (elapsedMs > 0) (frames - lastFrames) * 1000 / elapsedMs else 0
                    lastFrames = frames
                    lastElapsed = now
                    val uptimeSec = (now - startElapsed) / 1000
                    viewerStatsOverlay.text = "%dx%d  •  %d fps  •  %02d:%02d".format(
                        s.videoWidth, s.videoHeight, fps, uptimeSec / 60, uptimeSec % 60
                    )
                }
                delay(1000)
            }
        }
    }

    // Forwarded to DeX instead of leaving the viewer; leaving is done via the
    // side control tab's Stop button, Home, or the notification. This is gesture
    // nav's Back path specifically — it never reaches dispatchKeyEvent, since no
    // KeyEvent is generated for it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        sendKeyToDex(KeyEvent.KEYCODE_BACK)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (session == null) {
            finish()
            return
        }
        // onCreate() can finish() before clipboardManager is ever assigned (when
        // there was no session at all); finish() doesn't stop onResume() from still
        // running once, so this has to be checked rather than assumed.
        if (::clipboardManager.isInitialized) {
            clipboardManager.addPrimaryClipChangedListener(clipListener)
            attachClipboardBridge()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::clipboardManager.isInitialized) {
            clipboardManager.removePrimaryClipChangedListener(clipListener)
        }
        session?.controller?.onClipboardReceived = null
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
