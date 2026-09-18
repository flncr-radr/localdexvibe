package com.localdex

import android.annotation.SuppressLint
import android.os.Bundle
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.localdex.scrcpy.ScrcpySession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fullscreen interactive view of the DeX display.
 *
 * Touch is forwarded to the mirrored display; the system Back gesture/button is
 * forwarded as a DeX Back key. Closing happens through the swipe-up panel's Stop
 * button (with confirmation) or the persistent notification's Stop action.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var root: CoordinatorLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var controlPanel: View
    private lateinit var viewerStopButton: Button

    private var surfaceReady = false
    private var surfaceGivenToDecoder = false
    private var freeformWarningWatchStarted = false

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
        viewerStopButton = findViewById(R.id.viewerStopButton)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Starts collapsed to the grip peeking at the bottom edge; drag it up to
        // reveal the Stop button.
        BottomSheetBehavior.from(controlPanel).state = BottomSheetBehavior.STATE_COLLAPSED
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

    // Forward Back (hardware key and gesture alike) to DeX instead of leaving the
    // viewer; leaving is done via the swipe-up panel's Stop button, Home, or the
    // notification.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            session?.controller?.sendKeyPress(KeyEvent.KEYCODE_BACK)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        session?.controller?.sendKeyPress(KeyEvent.KEYCODE_BACK)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (session == null) finish()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
