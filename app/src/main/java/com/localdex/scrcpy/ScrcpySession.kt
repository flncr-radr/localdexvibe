package com.localdex.scrcpy

import android.content.Context
import android.util.Log
import com.localdex.Adb
import com.localdex.Prefs
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * One DeX session: starts scrcpy-server on the device (over the device's own wireless
 * debugging), which creates a virtual display, and owns the video/control sockets
 * until stopped.
 *
 * A virtual display shows no preview window on the main screen (unlike an overlay
 * display) and touches no global display settings. On Android 16 QPR2+ / One UI 8.5+
 * such displays default to fullscreen windowing (scrcpy issue #6143), so once the
 * display id is known the session forces it to freeform with
 * `wm set-display-windowing-mode` — giving movable, resizable windows.
 */
class ScrcpySession(
    private val context: Context,
    private val displaySpec: String,
) {
    sealed class State {
        data class Starting(val message: String) : State()
        data class Running(val videoWidth: Int, val videoHeight: Int, val displayId: Int) : State()
        data class Stopped(val error: String?) : State()
    }

    companion object {
        private const val TAG = "ScrcpySession"

        private const val SERVER_ASSET = "scrcpy-server"
        private const val SERVER_REMOTE_PATH = "/data/local/tmp/localdex-scrcpy-server.jar"
        private const val SERVER_VERSION = "4.1"

        private const val CONNECT_RETRIES = 40
        private const val CONNECT_RETRY_DELAY_MS = 250L

        private const val FREEFORM_FORCE_ATTEMPTS = 5
        private const val FREEFORM_FORCE_RETRY_DELAY_MS = 300L

        /**
         * The system creates an overlay display a moment after the setting is
         * written, not synchronously, so its id has to be polled for.
         */
        private const val OVERLAY_DISPLAY_ATTEMPTS = 20
        private const val OVERLAY_DISPLAY_RETRY_DELAY_MS = 250L

        /** Guards read-check-then-write access to [current] from [startIfNeeded] and [stop]. */
        private val lock = Any()

        /** The one live session, owned by DexService. */
        @Volatile
        var current: ScrcpySession? = null
            private set

        /**
         * Starts a new session and installs it as [current], unless one is already
         * running. Safe to call concurrently: at most one session is ever created for
         * overlapping calls.
         */
        fun startIfNeeded(context: Context, displaySpec: String): ScrcpySession {
            synchronized(lock) {
                current?.let { return it }
                val session = ScrcpySession(context, displaySpec)
                current = session
                session.start()
                return session
            }
        }
    }

    private val _state = MutableStateFlow<State>(State.Starting("Connecting…"))
    val state: StateFlow<State> = _state

    // Survives cancellation of callers: stop() must always run to completion so
    // nothing (server process, streams) leaks past the session.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stopped = AtomicBoolean(false)

    private var manager: AbsAdbConnectionManager? = null
    private var shellStream: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var controlStream: AdbStream? = null

    var videoDecoder: VideoDecoder? = null
        private set
    var controller: Controller? = null
        private set

    @Volatile
    var videoWidth = 0
        private set

    @Volatile
    var videoHeight = 0
        private set

    /** The Android display id of the virtual display, for `scrcpy --display-id=N`. */
    @Volatile
    var displayId = -1
        private set

    /** True while [forceFreeform] is still trying (or hasn't started yet). */
    @Volatile
    var freeformForceInProgress = true
        private set

    /**
     * True once forcing freeform mode on [displayId] has been given up on after
     * [FREEFORM_FORCE_ATTEMPTS] tries. Apps on the display open fullscreen with no
     * window controls when this is true. Only meaningful once
     * [freeformForceInProgress] is false.
     */
    @Volatile
    var freeformForceFailed = false
        private set

    /** Tail of the server's stdout/stderr, kept for error reporting. */
    private val serverLog = StringBuilder()

    /**
     * `enable_freeform_support`'s value before this session overwrote it, so [stop]
     * can put it back — "null" (the literal string `settings get` prints for an
     * unset key) means restoring it means deleting the key, not writing "null".
     * Null here means we never successfully read/changed it, so there's nothing to
     * restore.
     */
    private var originalFreeformSetting: String? = null

    /**
     * `dex_on_external_display`'s value before this session set it, so [stop] can
     * put it back rather than leaving the phone believing a DeX display exists.
     */
    private var originalDexOnExternalDisplay: String? = null

    /**
     * `overlay_display_devices`' value before this session set it, so [stop] can put
     * it back. Left null when the session did not use an overlay display, so there
     * is nothing to restore — this is a global setting that survives the app.
     */
    private var originalOverlayDisplayDevices: String? = null

    /**
     * True once this session is running on a display the *system* created. It gates
     * [forceFreeform]: see there for why forcing a windowing mode on such a display
     * is both pointless and dangerous.
     */
    private var usingOverlayDisplay = false

    fun start() {
        scope.launch {
            try {
                runSession()
            } catch (e: Exception) {
                Log.e(TAG, "Session failed", e)
                stop(errorForUser(e))
            }
        }
    }

    private suspend fun runSession() {
        val manager = Adb.createManager(context)
        this.manager = manager

        if (!manager.autoConnect(context, 10_000)) {
            throw IOException("Could not connect to ADB. Is wireless debugging on and paired?")
        }

        recoverStaleOverlayDisplay(manager)

        setStarting("Preparing display…")
        // Read into memory (~700 KB): AssetFileDescriptor can't report the size of a
        // compressed asset, and pushFile needs the exact byte count for `head -c`.
        val serverBytes = context.assets.open(SERVER_ASSET).use { it.readBytes() }
        Adb.pushFile(manager, serverBytes.inputStream(), serverBytes.size.toLong(), SERVER_REMOTE_PATH)

        // "Enable freeform windows" (a standard developer option). Without it, apps on
        // the DeX display open full screen with no window controls. Takes effect for
        // newly started apps; some builds want a reboot. This is a device-wide
        // setting, not scoped to this session's display, so its original value is
        // captured first and put back in stop() rather than left as 1 forever.
        if (Prefs.getForceFreeform(context)) {
            try {
                originalFreeformSetting =
                    Adb.runShell(manager, "settings get global enable_freeform_support").trim()
                Adb.runShell(manager, "settings put global enable_freeform_support 1")
            } catch (e: Exception) {
                Log.w(TAG, "Could not enable freeform windows", e)
            }
        } else {
            Log.i(TAG, "Freeform forcing off; leaving enable_freeform_support alone")
        }

        // Two captures from the reporting device, one with another on-device DeX app
        // running and one with this one, differed in exactly this setting:
        //
        //     app whose DeX works fully   dex_on_external_display=1
        //     this app                    dex_on_external_display=0
        //
        // In the working capture, app windows on the DeX display sat inside a root
        // task named "Desk" (visible as `mLaunchRootTask=... Task{#1051 name=Desk}`),
        // where ours are bare root tasks with no such parent. That container is the
        // likeliest thing DeX's taskbar enumerates, which would explain why its
        // taskbar never lists our running apps, its circle minimizes nothing, and
        // its desktop selector opens empty — all of DeX's own session-level UI,
        // while everything plain-framework works.
        //
        // Set before the display is created, since the system has to see it when the
        // display appears. Whether writing it actually enters DeX mode, or whether
        // it is only a flag the system sets once DeX is already up, is exactly what
        // this is here to find out — it is cheap to try and restored on stop.
        try {
            originalDexOnExternalDisplay =
                Adb.runShell(manager, "settings get system dex_on_external_display").trim()
            Adb.runShell(manager, "settings put system dex_on_external_display 1")
            Log.i(
                TAG,
                "dex_on_external_display: was '$originalDexOnExternalDisplay', set to 1"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not set dex_on_external_display", e)
        }

        // Either have scrcpy create the display, or have the system create it and
        // mirror it by id. See Prefs.getUseOverlayDisplay for why the second exists.
        val overlayDisplayId =
            if (Prefs.getUseOverlayDisplay(context)) setUpOverlayDisplay(manager) else null

        val scid = Random.nextInt(1, Int.MAX_VALUE)
        val scidHex = String.format("%08x", scid)
        val displayArg =
            if (overlayDisplayId != null) "display_id=$overlayDisplayId"
            else "new_display=$displaySpec"
        val command = "CLASSPATH=$SERVER_REMOTE_PATH app_process / com.genymobile.scrcpy.Server " +
            "$SERVER_VERSION scid=$scidHex log_level=info " +
            "video=true audio=false control=true video_codec=h264 " +
            "tunnel_forward=true send_device_meta=false send_dummy_byte=false " +
            displayArg

        // With new_display the id only turns up in the server's log, so it is parsed
        // out of there; mirroring an overlay display, it is already known, and
        // nothing in the log will ever announce it.
        if (overlayDisplayId != null) useDisplayId(overlayDisplayId)

        val shell = manager.openStream("shell:$command")
        shellStream = shell
        checkNotStopped(shell)
        startServerLogReader(shell)

        setStarting("Waiting for display…")
        val socketName = "localabstract:scrcpy_$scidHex"
        val video = connectWithRetry(manager, socketName)
        videoStream = video
        checkNotStopped(video)
        val control = connectWithRetry(manager, socketName, retries = 8)
        controlStream = control
        checkNotStopped(control)

        val decoder = VideoDecoder(
            input = video.openInputStream(),
            onVideoSize = { w, h ->
                videoWidth = w
                videoHeight = h
                _state.value = State.Running(w, h, displayId)
            },
            onError = { message -> stop(message) },
        )
        videoDecoder = decoder
        decoder.start()

        val ctrl = Controller(control.openOutputStream(), control.openInputStream())
        controller = ctrl
        ctrl.start()

        setStarting("Waiting for display…")
    }

    /**
     * If stop() ran while a stream was being opened, its cleanup may have missed the
     * stream that was just assigned — close it here and bail out of startup.
     */
    private fun checkNotStopped(stream: AdbStream) {
        if (stopped.get()) {
            try {
                stream.close()
            } catch (e: Exception) {
                // Best effort.
            }
            throw CancellationException("Session stopped during startup")
        }
    }

    private fun setStarting(message: String) {
        if (_state.value !is State.Stopped) {
            _state.value = State.Starting(message)
        }
    }

    private suspend fun connectWithRetry(
        manager: AbsAdbConnectionManager,
        socketName: String,
        retries: Int = CONNECT_RETRIES,
    ): AdbStream {
        var lastError: Exception? = null
        repeat(retries) {
            try {
                val stream = withTimeoutOrNull(3000L) {
                    runInterruptible { manager.openStream(socketName) }
                }
                if (stream != null) return stream
            } catch (e: Exception) {
                lastError = e
            }
            delay(CONNECT_RETRY_DELAY_MS)
        }
        throw IOException(
            "The display did not start. Server said:\n${serverLogTail()}",
            lastError
        )
    }

    private fun startServerLogReader(shell: AdbStream) {
        Thread({
            try {
                val input = shell.openInputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val text = String(buffer, 0, read)
                    val snapshot = synchronized(serverLog) {
                        serverLog.append(text)
                        if (serverLog.length > 8192) serverLog.delete(0, serverLog.length - 8192)
                        serverLog.toString()
                    }
                    // Scan the accumulated buffer, not just this chunk: the "New
                    // display" line can straddle two reads.
                    parseDisplayId(snapshot)
                    Log.i(TAG, "[server] ${text.trim()}")
                }
            } catch (e: IOException) {
                // Stream closed on stop; done.
            }
        }, "localdex-server-log").start()
    }

    private fun parseDisplayId(log: String) {
        if (displayId != -1) return
        val id = ScrcpyProtocol.parseDisplayId(log) ?: return
        useDisplayId(id)
    }

    /**
     * Adopts [id] as this session's display. Reached two ways: parsed out of the
     * server's log when scrcpy created the display, or known up front when the
     * system created it and we are only mirroring it. First call wins.
     */
    private fun useDisplayId(id: Int) {
        if (displayId != -1) return
        displayId = id
        Log.i(TAG, "DeX display id: $id")
        forceFreeform(id)

        // If video is already running, re-emit so observers pick up the id.
        val current = _state.value
        if (current is State.Running) {
            _state.value = current.copy(displayId = id)
        }
    }

    /**
     * Has the *system* create the display, by writing `overlay_display_devices`, and
     * returns its logical display id for scrcpy to mirror. Returns null if the
     * setting could not be written or the display never appeared, in which case the
     * caller falls back to scrcpy creating a VirtualDisplay as before — a failed
     * experiment should cost a worse DeX, not a dead session.
     *
     * The display spec is already in the `WIDTHxHEIGHT/DENSITY` form this setting
     * wants, so it is passed through unchanged.
     */
    private suspend fun setUpOverlayDisplay(manager: AbsAdbConnectionManager): Int? {
        try {
            val original =
                Adb.runShell(manager, "settings get global overlay_display_devices").trim()
            originalOverlayDisplayDevices = original
            // Recorded before the write, not after: if this process or the system
            // dies between the two, the next session still knows what to put back.
            Prefs.setPendingOverlayRestore(context, original)
            Adb.runShell(manager, "settings put global overlay_display_devices \"$displaySpec\"")
            Log.i(
                TAG,
                "overlay_display_devices: was '$originalOverlayDisplayDevices', " +
                    "set to '$displaySpec'"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not set overlay_display_devices", e)
            originalOverlayDisplayDevices = null
            return null
        }

        setStarting("Waiting for the overlay display…")
        repeat(OVERLAY_DISPLAY_ATTEMPTS) {
            val id = try {
                WindowSnapParser.parseOverlayDisplayId(Adb.runShell(manager, "dumpsys display"))
            } catch (e: Exception) {
                null
            }
            // Never 0: mirroring the built-in screen would put the phone's own
            // display inside the viewer, which looks enough like a working session
            // to waste a whole test round.
            if (id != null && id != 0) {
                usingOverlayDisplay = true
                return id
            }
            delay(OVERLAY_DISPLAY_RETRY_DELAY_MS)
        }

        Log.w(TAG, "Overlay display never appeared; falling back to a virtual display")
        restoreOverlayDisplayDevices()
        originalOverlayDisplayDevices = null
        return null
    }

    /**
     * Forces the per-display windowing mode to freeform, when [Prefs.getForceFreeform]
     * allows it. The mode can flip back briefly while the display is still being
     * registered, so this sets it, verifies it took, and retries a few times before
     * giving up.
     *
     * Being checked before all desktop-mode heuristics (see
     * DisplayWindowSettings.getWindowingModeLocked) is what makes this work at all,
     * and is also its cost: it takes DeX down the legacy freeform path rather than
     * real desktop windowing, and on that path minimize and show-desktop cannot
     * work. See Prefs.getForceFreeform for the mechanism.
     */
    private fun forceFreeform(id: Int) {
        scope.launch {
            try {
                // Never on a system-created display, whatever the switch says.
                //
                // Forcing is a retry loop of `wm set-display-windowing-mode` against
                // a display that, in this mode, DeX is configuring at the same
                // moment — two things writing the same display's windowing mode
                // while the window manager holds its lock. On a device where DeX
                // really did engage this way, the phone froze and then restarted,
                // which is the signature of a system_server watchdog rather than of
                // anything this app can catch.
                //
                // It is also pointless here. Forcing exists for the case where DeX
                // never engages and apps would otherwise open fullscreen with no
                // window controls; a display where DeX *has* engaged manages its own
                // windowing, and forcing only drags it back onto the legacy freeform
                // path where minimize and show-desktop cannot work — the thing this
                // mode is trying to escape.
                if (usingOverlayDisplay) {
                    Log.i(
                        TAG,
                        "Display $id was created by the system and DeX manages it; " +
                            "not forcing its windowing mode"
                    )
                    return@launch
                }
                if (!Prefs.getForceFreeform(context)) {
                    Log.i(
                        TAG,
                        "Freeform forcing off; leaving display $id's windowing mode alone so " +
                            "DeX's own desktop-mode heuristics decide"
                    )
                    return@launch
                }
                val manager = this@ScrcpySession.manager ?: return@launch
                repeat(FREEFORM_FORCE_ATTEMPTS) { attempt ->
                    try {
                        Adb.runShell(manager, "wm set-display-windowing-mode -d $id ${WindowingMode.FREEFORM}")
                        val reply = Adb.runShell(manager, "wm get-display-windowing-mode -d $id")
                        if (WindowingMode.isFreeform(reply)) {
                            Log.i(TAG, "Forced freeform on display $id (attempt ${attempt + 1}): $reply")
                            return@launch
                        }
                        Log.w(TAG, "Display $id not yet freeform after attempt ${attempt + 1}: $reply")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not force freeform on display $id (attempt ${attempt + 1})", e)
                    }
                    delay(FREEFORM_FORCE_RETRY_DELAY_MS)
                }
                freeformForceFailed = true
                Log.e(
                    TAG,
                    "Giving up forcing freeform on display $id after $FREEFORM_FORCE_ATTEMPTS attempts; " +
                        "apps will open fullscreen with no window controls"
                )
            } finally {
                freeformForceInProgress = false
            }
        }
    }

    /**
     * Moves the focused window on this session's display between fullscreen and a
     * bounded window. Returns null if the display id or video size (needed to
     * compute the target bounds) aren't known yet, or if there is no ADB
     * connection anymore.
     */
    suspend fun snapWindow(direction: WindowSnap.Direction): WindowSnap.Result? {
        val mgr = manager ?: return null
        if (displayId == -1 || videoWidth <= 0 || videoHeight <= 0) return null
        return WindowSnap.snap(mgr, displayId, videoWidth, videoHeight, direction)
    }

    /** Every app window on this session's display, hidden ones included. */
    internal suspend fun listWindows(): List<WindowSnapParser.TaskWindow> {
        val mgr = manager ?: return emptyList()
        if (displayId == -1) return emptyList()
        return WindowSnap.listWindows(mgr, displayId)
    }

    /** Brings [task] back to the front of this session's display. */
    internal suspend fun focusWindow(task: WindowSnapParser.TaskWindow) {
        val mgr = manager ?: return
        if (displayId == -1) return
        WindowSnap.focusWindow(mgr, task, displayId)
    }

    /** Puts `enable_freeform_support` back to what it was before this session touched it. */
    private suspend fun restoreFreeformSetting() {
        val original = originalFreeformSetting ?: return
        val mgr = manager ?: return
        try {
            if (original == "null") {
                Adb.runShell(mgr, "settings delete global enable_freeform_support")
            } else {
                Adb.runShell(mgr, "settings put global enable_freeform_support $original")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore enable_freeform_support to '$original'", e)
        }
    }

    /** Puts `dex_on_external_display` back to what it was before this session. */
    private suspend fun restoreDexOnExternalDisplay() {
        val original = originalDexOnExternalDisplay ?: return
        val mgr = manager ?: return
        try {
            if (original == "null") {
                Adb.runShell(mgr, "settings delete system dex_on_external_display")
            } else {
                Adb.runShell(mgr, "settings put system dex_on_external_display $original")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore dex_on_external_display to '$original'", e)
        }
    }

    /** Puts `overlay_display_devices` back, removing any display this session added. */
    private suspend fun restoreOverlayDisplayDevices() {
        val original = originalOverlayDisplayDevices ?: return
        val mgr = manager ?: return
        if (writeOverlayDisplayDevices(mgr, original)) {
            originalOverlayDisplayDevices = null
            Prefs.setPendingOverlayRestore(context, null)
        }
    }

    /**
     * Undoes an overlay display left behind by a session that never got to stop
     * cleanly — the app was killed, or the system restarted under it. Runs before
     * anything else in a session, whichever display mode this one is going to use,
     * because the leftover is device-wide and comes back on every boot until
     * something clears it.
     */
    private suspend fun recoverStaleOverlayDisplay(manager: AbsAdbConnectionManager) {
        val pending = Prefs.getPendingOverlayRestore(context) ?: return
        Log.w(TAG, "Last session left overlay_display_devices set; restoring it to '$pending'")
        if (writeOverlayDisplayDevices(manager, pending)) {
            Prefs.setPendingOverlayRestore(context, null)
        }
    }

    /**
     * Writes [value] to `overlay_display_devices`, where the literal string "null" —
     * what `settings get` prints for an unset key — means delete rather than write
     * the word. Returns whether it went through, so a caller only forgets the value
     * it was holding once it is actually applied.
     */
    private suspend fun writeOverlayDisplayDevices(
        manager: AbsAdbConnectionManager,
        value: String,
    ): Boolean = try {
        if (value == "null" || value.isEmpty()) {
            Adb.runShell(manager, "settings delete global overlay_display_devices")
        } else {
            Adb.runShell(manager, "settings put global overlay_display_devices \"$value\"")
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not restore overlay_display_devices to '$value'", e)
        false
    }

    private fun serverLogTail(): String = synchronized(serverLog) {
        serverLog.toString().trim().takeLast(500).ifEmpty { "(no output)" }
    }

    private fun errorForUser(e: Exception): String {
        return e.message ?: e.javaClass.simpleName
    }

    /** Idempotent; safe from any thread. Tears everything down, then reports [error]. */
    fun stop(error: String? = null) {
        // The stopped-check and clearing `current` must be one atomic step under
        // the same lock startIfNeeded() uses — otherwise a start() on another
        // thread can slip in between them and be handed a session that has
        // already committed to stopping.
        synchronized(lock) {
            if (!stopped.compareAndSet(false, true)) return
            if (current === this@ScrcpySession) current = null
        }

        scope.launch {
            videoDecoder?.stop()
            controller?.stop()
            restoreFreeformSetting()
            restoreDexOnExternalDisplay()
            restoreOverlayDisplayDevices()

            listOf(videoStream, controlStream, shellStream).forEach { stream ->
                try {
                    stream?.close()
                } catch (e: Exception) {
                    // Best effort.
                }
            }

            try {
                manager?.close()
            } catch (e: Exception) {
                // Best effort.
            }

            _state.value = State.Stopped(error)
            scope.cancel()
        }
    }
}
