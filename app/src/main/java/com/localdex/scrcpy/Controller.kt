package com.localdex.scrcpy

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Sends scrcpy control messages (v4.1 wire format) and reads device messages back
 * — clipboard changes in particular; clipboard-set acks and UHID output are parsed
 * (their byte counts have to be consumed to keep the stream in sync) but otherwise
 * ignored, since nothing here creates a UHID device or needs paste correlation.
 *
 * Touch input is translated to MOUSE events (scrcpy's "sdk mouse" model:
 * pointerId -1, hover moves, button state), not finger touches. This matters:
 * One UI 9's desktop-mode shell crashes (SystemUI NPE in
 * DesktopModeVisualIndicator) when a window caption is dragged by a
 * touchscreen pointer, while the mouse drag path works — it's the same reason
 * window dragging works from desktop scrcpy.
 *
 * Gestures: one finger = mouse click/drag; two fingers = scroll wheel, or a
 * right click if they lift again without moving (a stationary two-finger tap).
 *
 * Writes happen on a dedicated thread so touch handling never blocks the UI
 * thread on a socket.
 */
class Controller(
    private val output: OutputStream,
    private val input: InputStream,
) {
    companion object {
        private const val TAG = "Controller"

        private const val TYPE_INJECT_KEYCODE = 0
        private const val TYPE_INJECT_TOUCH_EVENT = 2
        private const val TYPE_INJECT_SCROLL_EVENT = 3

        private const val POINTER_ID_MOUSE = -1L
        private const val BUTTON_PRIMARY = 1 // MotionEvent.BUTTON_PRIMARY
        private const val BUTTON_SECONDARY = 2 // MotionEvent.BUTTON_SECONDARY

        /**
         * A two-finger gesture that never moves more than this (in video px) before
         * lifting is a tap (right click), not an aborted scroll.
         */
        private const val TAP_MAX_MOVEMENT_PX = 20
    }

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val clipboardSequence = AtomicLong(0)

    @Volatile
    private var running = true

    /**
     * Set by the viewer while it's in the foreground; delivers the device's
     * clipboard text whenever it changes. Read on [receiverThread] — the callback
     * itself must hop to the UI thread for anything Android requires it on.
     */
    @Volatile
    var onClipboardReceived: ((String) -> Unit)? = null

    private val senderThread = Thread({
        try {
            while (running) {
                val message = queue.take()
                output.write(message)
                output.flush()
            }
        } catch (e: InterruptedException) {
            // stop() interrupts us; done.
        } catch (e: IOException) {
            if (running) Log.w(TAG, "Control socket write failed", e)
        }
    }, "localdex-control-send")

    // The server pushes device messages (clipboard changes, clipboard-set acks) on
    // this socket; they must be consumed or the server's writer eventually blocks.
    private val receiverThread = Thread({ runDeviceMessageReader() }, "localdex-control-recv")

    fun start() {
        senderThread.start()
        receiverThread.start()
    }

    fun stop() {
        running = false
        senderThread.interrupt()
    }

    /** Sends a full key press (down + up) to the mirrored display's focus. */
    fun sendKeyPress(keycode: Int) {
        sendKey(KeyEvent.ACTION_DOWN, keycode, repeat = 0, metaState = 0)
        sendKey(KeyEvent.ACTION_UP, keycode, repeat = 0, metaState = 0)
    }

    /**
     * Forwards a real key event as-is — a hardware/Bluetooth keyboard's presses and
     * releases, modifiers included. Android already stamps [metaState] with
     * whichever modifiers are currently held on every KeyEvent it delivers, so a
     * Ctrl/Shift/Alt combination just works without this app computing it.
     */
    fun sendKeyEvent(action: Int, keycode: Int, repeat: Int, metaState: Int) {
        sendKey(action, keycode, repeat, metaState)
    }

    private fun sendKey(action: Int, keycode: Int, repeat: Int, metaState: Int) {
        val buffer = ByteBuffer.allocate(14)
        buffer.put(TYPE_INJECT_KEYCODE.toByte())
        buffer.put(action.toByte())
        buffer.putInt(keycode)
        buffer.putInt(repeat)
        buffer.putInt(metaState)
        queue.offer(buffer.array())
    }

    /**
     * Pushes [text] into the mirrored display's own clipboard without also
     * injecting a paste — the point is that a later Ctrl+V from a real keyboard
     * finds it there, not that this call pastes anything itself.
     */
    fun sendClipboard(text: String) {
        val textBytes = ScrcpyProtocol.truncateUtf8(text, ScrcpyProtocol.MAX_CLIPBOARD_TEXT_BYTES)
        val buffer = ByteBuffer.allocate(1 + 8 + 1 + 4 + textBytes.size)
        buffer.put(ScrcpyProtocol.TYPE_SET_CLIPBOARD.toByte())
        buffer.putLong(clipboardSequence.getAndIncrement())
        buffer.put(0) // paste = false
        buffer.putInt(textBytes.size)
        buffer.put(textBytes)
        queue.offer(buffer.array())
    }

    private fun sendMouse(
        action: Int,
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
    ) {
        val buffer = ByteBuffer.allocate(32)
        buffer.put(TYPE_INJECT_TOUCH_EVENT.toByte())
        buffer.put(action.toByte())
        buffer.putLong(POINTER_ID_MOUSE)
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(videoWidth.toShort())
        buffer.putShort(videoHeight.toShort())
        buffer.putShort(ScrcpyProtocol.pressureToU16FixedPoint(pressure))
        buffer.putInt(actionButton)
        buffer.putInt(buttons)
        queue.offer(buffer.array())
    }

    private fun sendRightClick(x: Int, y: Int, videoWidth: Int, videoHeight: Int) {
        sendMouse(MotionEvent.ACTION_HOVER_MOVE, x, y, videoWidth, videoHeight, 0f, 0, 0)
        sendMouse(MotionEvent.ACTION_DOWN, x, y, videoWidth, videoHeight, 1f, BUTTON_SECONDARY, BUTTON_SECONDARY)
        sendMouse(MotionEvent.ACTION_UP, x, y, videoWidth, videoHeight, 0f, BUTTON_SECONDARY, 0)
    }

    private fun sendScroll(
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        hScroll: Float,
        vScroll: Float,
    ) {
        val buffer = ByteBuffer.allocate(21)
        buffer.put(TYPE_INJECT_SCROLL_EVENT.toByte())
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(videoWidth.toShort())
        buffer.putShort(videoHeight.toShort())
        buffer.putShort(ScrcpyProtocol.scrollToI16FixedPoint(hScroll))
        buffer.putShort(ScrcpyProtocol.scrollToI16FixedPoint(vScroll))
        buffer.putInt(0) // buttons
        queue.offer(buffer.array())
    }

    // -- Gesture translation ----------------------------------------------------------

    private enum class Gesture { NONE, MOUSE, SCROLL, DONE }

    private var gesture = Gesture.NONE
    private var lastX = 0
    private var lastY = 0

    /** Where the current two-finger gesture started, to tell a tap from a scroll. */
    private var scrollStartX = 0
    private var scrollStartY = 0

    /**
     * Forwards a [MotionEvent] from a view of size [viewWidth]x[viewHeight] that shows
     * the video letterbox-free (the viewer sizes its SurfaceView to the exact aspect
     * ratio, so a plain scale maps view space to video space).
     */
    fun forwardMotionEvent(
        event: MotionEvent,
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ) {
        if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) return

        fun videoX(pointerIndex: Int) =
            (event.getX(pointerIndex) * videoWidth / viewWidth).toInt().coerceIn(0, videoWidth - 1)

        fun videoY(pointerIndex: Int) =
            (event.getY(pointerIndex) * videoHeight / viewHeight).toInt().coerceIn(0, videoHeight - 1)

        fun centroidX(): Int {
            var sum = 0
            for (i in 0 until event.pointerCount) sum += videoX(i)
            return sum / event.pointerCount
        }

        fun centroidY(): Int {
            var sum = 0
            for (i in 0 until event.pointerCount) sum += videoY(i)
            return sum / event.pointerCount
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = Gesture.MOUSE
                lastX = videoX(0)
                lastY = videoY(0)
                // Move the pointer to the spot first, like a real mouse would.
                sendMouse(MotionEvent.ACTION_HOVER_MOVE, lastX, lastY, videoWidth, videoHeight, 0f, 0, 0)
                sendMouse(MotionEvent.ACTION_DOWN, lastX, lastY, videoWidth, videoHeight, 1f, BUTTON_PRIMARY, BUTTON_PRIMARY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (gesture == Gesture.MOUSE) {
                    // Second finger: this is a scroll, not a drag — release the button.
                    sendMouse(MotionEvent.ACTION_UP, lastX, lastY, videoWidth, videoHeight, 0f, BUTTON_PRIMARY, 0)
                    gesture = Gesture.SCROLL
                    lastX = centroidX()
                    lastY = centroidY()
                    scrollStartX = lastX
                    scrollStartY = lastY
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (gesture) {
                    Gesture.MOUSE -> {
                        lastX = videoX(0)
                        lastY = videoY(0)
                        sendMouse(MotionEvent.ACTION_MOVE, lastX, lastY, videoWidth, videoHeight, 1f, 0, BUTTON_PRIMARY)
                    }
                    Gesture.SCROLL -> {
                        val cx = centroidX()
                        val cy = centroidY()
                        val dx = cx - lastX
                        val dy = cy - lastY
                        if (dx != 0 || dy != 0) {
                            sendScroll(
                                cx, cy, videoWidth, videoHeight,
                                dx / ScrcpyProtocol.SCROLL_PX_PER_TICK,
                                dy / ScrcpyProtocol.SCROLL_PX_PER_TICK,
                            )
                            lastX = cx
                            lastY = cy
                        }
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesture == Gesture.SCROLL && event.pointerCount <= 2) {
                    // Dropping back to one finger. If the two fingers never really
                    // moved, treat the whole gesture as a tap (right click) instead
                    // of a scroll that just happened to cover no distance.
                    if (kotlin.math.abs(lastX - scrollStartX) <= TAP_MAX_MOVEMENT_PX &&
                        kotlin.math.abs(lastY - scrollStartY) <= TAP_MAX_MOVEMENT_PX
                    ) {
                        sendRightClick(lastX, lastY, videoWidth, videoHeight)
                    }
                    gesture = Gesture.DONE
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.MOUSE) {
                    sendMouse(MotionEvent.ACTION_UP, videoX(0), videoY(0), videoWidth, videoHeight, 0f, BUTTON_PRIMARY, 0)
                }
                gesture = Gesture.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (gesture == Gesture.MOUSE) {
                    sendMouse(MotionEvent.ACTION_UP, lastX, lastY, videoWidth, videoHeight, 0f, BUTTON_PRIMARY, 0)
                }
                gesture = Gesture.NONE
            }
        }
    }

    /**
     * Reads device messages (clipboard changes, clipboard-set acks, UHID output)
     * until the socket closes. Every type has to be parsed and its exact byte count
     * consumed, even ones we don't act on — anything left unread desyncs every
     * message that follows it on this stream.
     */
    private fun runDeviceMessageReader() {
        try {
            val dis = DataInputStream(input)
            while (running) {
                when (val type = dis.readUnsignedByte()) {
                    ScrcpyProtocol.DEVICE_MSG_TYPE_CLIPBOARD -> {
                        val len = dis.readInt()
                        if (!ScrcpyProtocol.isPlausibleDeviceClipboardLength(len)) {
                            throw IOException("Implausible clipboard length $len — stream out of sync")
                        }
                        val bytes = ByteArray(len)
                        dis.readFully(bytes)
                        onClipboardReceived?.invoke(String(bytes, Charsets.UTF_8))
                    }
                    ScrcpyProtocol.DEVICE_MSG_TYPE_ACK_CLIPBOARD -> {
                        // 8-byte sequence number. Unused: we don't correlate a
                        // paste with its ack, we just fire-and-forget sync.
                        dis.readLong()
                    }
                    ScrcpyProtocol.DEVICE_MSG_TYPE_UHID_OUTPUT -> {
                        dis.readUnsignedShort() // device id
                        val size = dis.readUnsignedShort()
                        dis.skipBytes(size)
                    }
                    else -> throw IOException("Unknown device message type $type — stream out of sync")
                }
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "Device message reader stopped", e)
        }
    }
}
