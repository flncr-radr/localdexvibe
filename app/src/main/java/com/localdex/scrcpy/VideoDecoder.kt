package com.localdex.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch

/**
 * Reads the scrcpy v4.1 video stream and decodes it onto a [Surface].
 *
 * Stream layout (send_dummy_byte=false, send_device_meta=false, defaults otherwise):
 *   [4B codec id ("h264")]
 *   then packets, each starting with a 12-byte header:
 *     - session packet (MSB of first byte set): [4B flags][4B width][4B height], no payload
 *     - frame packet: [8B pts+flags][4B size][size bytes of H.264]
 *       flags: bit62 = codec config (SPS/PPS), bit61 = key frame
 */
class VideoDecoder(
    private val input: InputStream,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "VideoDecoder"
    }

    @Volatile
    private var surface: Surface? = null
    private val surfaceLatch = CountDownLatch(1)

    @Volatile
    private var renderEnabled = false

    @Volatile
    private var running = true

    private var codec: MediaCodec? = null
    private var outputThread: Thread? = null

    private var videoWidth = 0
    private var videoHeight = 0
    private var needsReconfigure = false

    /**
     * Last SPS/PPS payload seen. Config packets only arrive at the start of a
     * capture session, so if the codec has to be dropped while no surface is
     * attached (see [usableSurfaceSnapshot]), this lets it be rebuilt once a
     * surface returns without waiting for the server to resend one.
     */
    private var pendingConfigPayload: ByteArray? = null

    private val readerThread = Thread({ runReader() }, "localdex-video")

    fun start() {
        readerThread.start()
    }

    /** Must be called (once the viewer's surface exists) before decoding can begin. */
    fun setSurface(surface: Surface) {
        this.surface = surface
        val currentCodec = codec
        if (currentCodec != null) {
            // Viewer came back after being backgrounded: point the codec at the new surface.
            try {
                currentCodec.setOutputSurface(surface)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not switch output surface", e)
            }
        }
        renderEnabled = true
        surfaceLatch.countDown()
    }

    /** The viewer's surface is going away; keep decoding but stop rendering. */
    fun clearSurface() {
        renderEnabled = false
        // The Surface itself is being destroyed by the caller right after this,
        // so drop the reference — using it later (e.g. to configure a codec on
        // a resolution change) would throw against an already-released Surface.
        surface = null
    }

    /**
     * A stable reference to the currently attached surface, or null if none is
     * attached/valid right now. Callers must configure the codec against this same
     * reference rather than re-reading [surface] later — the field can be nulled out
     * by [clearSurface] on the UI thread at any time.
     */
    private fun usableSurfaceSnapshot(): Surface? = surface?.takeIf { it.isValid }

    fun stop() {
        running = false
        readerThread.interrupt()
        try {
            input.close()
        } catch (e: IOException) {
            // Closing is what unblocks the reader; nothing to do.
        }
    }

    private fun runReader() {
        try {
            val dis = DataInputStream(input)

            val codecId = dis.readInt()
            if (codecId != ScrcpyProtocol.CODEC_ID_H264) {
                throw IOException("Unexpected codec id 0x${Integer.toHexString(codecId)}")
            }

            val header = ByteArray(ScrcpyProtocol.PACKET_HEADER_SIZE)
            while (running) {
                dis.readFully(header)

                if (ScrcpyProtocol.isSessionPacket(header)) {
                    // Session packet: capture (re)started, possibly with a new size.
                    val width = ScrcpyProtocol.readInt(header, 4)
                    val height = ScrcpyProtocol.readInt(header, 8)
                    Log.i(TAG, "Video session: ${width}x$height")
                    if (videoWidth != 0 && (width != videoWidth || height != videoHeight)) {
                        needsReconfigure = true
                    }
                    videoWidth = width
                    videoHeight = height
                    onVideoSize(width, height)
                    continue
                }

                val ptsAndFlags = ScrcpyProtocol.readLong(header, 0)
                val size = ScrcpyProtocol.readInt(header, 8)
                if (!ScrcpyProtocol.isPlausiblePacketSize(size)) {
                    throw IOException("Implausible packet size $size — stream out of sync")
                }
                val payload = ByteArray(size)
                dis.readFully(payload)

                val isConfig = ScrcpyProtocol.isConfigPacket(ptsAndFlags)
                if (isConfig) {
                    // Config packets (SPS/PPS) open every capture session; this is the
                    // safe moment to (re)create the codec.
                    pendingConfigPayload = payload
                    surfaceLatch.await()
                    if (codec == null || needsReconfigure) {
                        val snapshot = usableSurfaceSnapshot()
                        if (snapshot != null && recreateCodec(snapshot)) {
                            needsReconfigure = false
                        } else {
                            // No surface to configure against right now (viewer
                            // backgrounded), or it died mid-reconfigure. Drop any
                            // stale-size codec instead of crashing; needsReconfigure
                            // is (re)set so this is retried below once a surface
                            // returns.
                            releaseCodec()
                            needsReconfigure = true
                        }
                    }
                    if (codec != null) {
                        submit(payload, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    }
                } else {
                    if (codec == null && needsReconfigure) {
                        val config = pendingConfigPayload
                        val snapshot = usableSurfaceSnapshot()
                        if (config != null && snapshot != null && recreateCodec(snapshot)) {
                            needsReconfigure = false
                            submit(config, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        }
                    }
                    if (codec != null) {
                        submit(payload, ScrcpyProtocol.ptsOf(ptsAndFlags), 0)
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Video stream ended", e)
                onError("Video stream ended: ${e.message}")
            }
        } finally {
            releaseCodec()
        }
    }

    /**
     * Rebuilds the codec against [surface]. Returns false (leaving codec == null) if
     * that surface died in the narrow window between the caller's usability check and
     * this call — the caller retries once a fresh surface arrives.
     */
    private fun recreateCodec(surface: Surface): Boolean {
        releaseCodec()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
        val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            newCodec.configure(format, surface, null, 0)
            newCodec.start()
        } catch (e: Exception) {
            Log.w(TAG, "Surface became invalid while (re)creating the codec", e)
            newCodec.release()
            return false
        }
        codec = newCodec

        outputThread = Thread({ drainOutput(newCodec) }, "localdex-video-out").also { it.start() }
        return true
    }

    private fun submit(data: ByteArray, ptsUs: Long, flags: Int) {
        val currentCodec = codec ?: return
        while (running) {
            val index = currentCodec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = currentCodec.getInputBuffer(index) ?: continue
                buffer.clear()
                if (data.size > buffer.remaining()) {
                    // Bigger than the codec's negotiated input buffer (an unusually
                    // large frame). Drop it rather than overflow, but still hand the
                    // buffer back so the codec doesn't starve for input buffers.
                    Log.w(TAG, "Dropping oversized frame: ${data.size}B > ${buffer.remaining()}B buffer")
                    currentCodec.queueInputBuffer(index, 0, 0, ptsUs, flags)
                    return
                }
                buffer.put(data)
                currentCodec.queueInputBuffer(index, 0, data.size, ptsUs, flags)
                return
            }
        }
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 50_000)
                if (index >= 0) {
                    // Render immediately: the source display is live, latency beats pacing.
                    codec.releaseOutputBuffer(index, renderEnabled)
                }
            }
        } catch (e: IllegalStateException) {
            // Codec was released under us during stop/reconfigure; expected.
        }
    }

    private fun releaseCodec() {
        val oldCodec = codec
        codec = null
        outputThread?.interrupt()
        outputThread = null
        if (oldCodec != null) {
            try {
                oldCodec.stop()
            } catch (e: IllegalStateException) {
                // Already stopped.
            }
            oldCodec.release()
        }
    }

}
