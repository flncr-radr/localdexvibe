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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fullscreen interactive view of the DeX display.
 *
 * Touch and a real hardware/Bluetooth keyboard are both forwarded to the mirrored
 * display; the system Back gesture/button is forwarded as a DeX Back key. Closing
 * happens through the side control tab's Stop button (with confirmation) or the
 * persistent notification's Stop action.
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
            // Also the fix for a window stuck fullscreen on platforms where the
            // built-in restore/un-maximize control doesn't actually shrink it back:
            // explicit bounds smaller than the display pull it out of fullscreen
            // windowing regardless of what the platform's own gesture does.
            KeyEvent.KEYCODE_DPAD_DOWN to WindowSnap.Direction.RESTORE,
        )
    }

    private lateinit var root: CoordinatorLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var controlPanel: View
    private lateinit var controlPanelGrip: View
    private lateinit var viewerStopButton: Button
    private lateinit var clipboardManager: ClipboardManager

    private var surfaceReady = false
    private var surfaceGivenToDecoder = false
    private var freeformWarningWatchStarted = false
    private var controlPanelExpanded = false
    private var controlPanelPositioned = false

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
        controlPanelGrip = findViewById(R.id.controlPanelGrip)
        viewerStopButton = findViewById(R.id.viewerStopButton)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        setupControlPanel()
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
     * Starts the panel collapsed to just the grip peeking from the right edge, and
     * wires the tap-to-toggle. Horizontal position (collapsed/expanded) is driven by
     * translationX, set once the panel has a measured width — it starts at 0 on the
     * very first layout pass, so setting it any earlier would just get overwritten
     * with 0. Vertical position is re-applied on every layout change instead (fold/
     * rotation can change root's height while the activity survives via
     * configChanges), which is safe since it doesn't interact with the toggle state.
     */
    private fun setupControlPanel() {
        val panelPositionFraction = Prefs.getPanelPositionFraction(this)
        controlPanel.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (root.height > 0) {
                // The layout centers the panel by default (layout_gravity center_vertical,
                // i.e. fraction 0.5); this is the offset from that baseline needed to
                // land at the configured fraction from the bottom instead.
                view.translationY = (0.5f - panelPositionFraction) * root.height
            }

            if (controlPanelPositioned) return@addOnLayoutChangeListener
            val hiddenOffset = view.width - controlPanelGrip.width
            if (hiddenOffset <= 0) return@addOnLayoutChangeListener
            controlPanelPositioned = true
            view.translationX = hiddenOffset.toFloat()
        }
        controlPanel.setOnClickListener { toggleControlPanel() }
    }

    private fun toggleControlPanel() {
        val hiddenOffset = (controlPanel.width - controlPanelGrip.width).toFloat()
        controlPanelExpanded = !controlPanelExpanded
        controlPanel.animate().translationX(if (controlPanelExpanded) 0f else hiddenOffset).start()
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
     * Best-effort: the two shell commands behind this (see WindowSnap) depend on
     * dumpsys text formats, not a stable API, so a failure here is logged and
     * toasted rather than surfaced any louder.
     */
    private fun triggerSnap(direction: WindowSnap.Direction) {
        val activeSession = session ?: return
        lifecycleScope.launch {
            try {
                activeSession.snapWindow(direction)
            } catch (e: Exception) {
                Log.w(TAG, "Window snap failed", e)
                Toast.makeText(this@ViewerActivity, "Couldn't snap the window.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Forwarded to DeX instead of leaving the viewer; leaving is done via the
    // side control tab's Stop button, Home, or the notification. This is gesture
    // nav's Back path specifically — it never reaches dispatchKeyEvent, since no
    // KeyEvent is generated for it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        session?.controller?.sendKeyPress(KeyEvent.KEYCODE_BACK)
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
