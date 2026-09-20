package com.localdex.scrcpy

/**
 * The pure, dependency-free half of the scrcpy v4.1 server interface: packet
 * framing, control-message fixed-point encoding, and the server log lines the
 * session reads.
 *
 * Split out from [VideoDecoder]/[Controller]/[ScrcpySession] so it can be unit
 * tested on the JVM — the bugs this layer has produced (a "New display" line
 * straddling two socket reads, header field offsets) are all pure-function bugs
 * that need no device to catch.
 */
internal object ScrcpyProtocol {

    /** "h264" — the codec id the video stream opens with. */
    const val CODEC_ID_H264 = 0x68323634

    /** Every packet opens with a 12-byte header. */
    const val PACKET_HEADER_SIZE = 12

    /** Bit 62 of a frame packet's pts+flags marks codec config (SPS/PPS). */
    const val FLAG_CONFIG = 1L shl 62

    /** Bits 0..60 are the presentation timestamp; 61 is the key-frame flag. */
    const val PTS_MASK = (1L shl 61) - 1

    /** Beyond this a size field means the stream desynced, not a huge frame. */
    const val MAX_PACKET_SIZE = 16 * 1024 * 1024

    /** Finger travel (in video px) equal to one scroll-wheel notch. */
    const val SCROLL_PX_PER_TICK = 64f

    /** Control message type for SET_CLIPBOARD (client → device). */
    const val TYPE_SET_CLIPBOARD = 9

    /** Device message types (device → client), read off the same control socket. */
    const val DEVICE_MSG_TYPE_CLIPBOARD = 0
    const val DEVICE_MSG_TYPE_ACK_CLIPBOARD = 1
    const val DEVICE_MSG_TYPE_UHID_OUTPUT = 2

    /**
     * scrcpy's own cap on SET_CLIPBOARD's text field: SC_CONTROL_MSG_MAX_SIZE
     * (1 << 18) minus the 14 bytes the rest of that message occupies.
     */
    const val MAX_CLIPBOARD_TEXT_BYTES = 262_130

    /**
     * Sanity bound on an incoming device clipboard message's length prefix — large
     * enough for any real clipboard, small enough to reject a corrupted or desynced
     * stream rather than attempt a huge allocation on its say-so.
     */
    const val MAX_DEVICE_CLIPBOARD_BYTES = 4 * 1024 * 1024

    fun isPlausibleDeviceClipboardLength(len: Int): Boolean =
        len in 0..MAX_DEVICE_CLIPBOARD_BYTES

    /**
     * Encodes [text] as UTF-8, truncated to at most [maxBytes] without splitting a
     * multi-byte code point — a naive byte-array cut can leave a dangling lead byte
     * that turns the whole string invalid rather than just losing its tail.
     */
    fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val full = text.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full
        var end = maxBytes
        // Continuation bytes look like 10xxxxxx; back off until `end` lands on the
        // start of a character (or 0), never mid-sequence.
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end--
        return full.copyOf(end)
    }

    fun readInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }

    fun readLong(data: ByteArray, offset: Int): Long {
        return (readInt(data, offset).toLong() shl 32) or
            (readInt(data, offset + 4).toLong() and 0xffffffffL)
    }

    /**
     * The MSB of the first header byte marks a session packet — capture started or
     * restarted, carrying a (possibly new) width and height instead of a payload.
     */
    fun isSessionPacket(header: ByteArray): Boolean = (header[0].toInt() and 0x80) != 0

    fun isConfigPacket(ptsAndFlags: Long): Boolean = (ptsAndFlags and FLAG_CONFIG) != 0L

    fun ptsOf(ptsAndFlags: Long): Long = ptsAndFlags and PTS_MASK

    fun isPlausiblePacketSize(size: Int): Boolean = size > 0 && size <= MAX_PACKET_SIZE

    fun pressureToU16FixedPoint(pressure: Float): Short {
        val clamped = pressure.coerceIn(0f, 1f)
        return if (clamped == 1f) 0xffff.toShort() else (clamped * 0x10000).toInt().toShort()
    }

    /** The wire encodes scroll values as i16 fixed point over the range [-16, 16]. */
    fun scrollToI16FixedPoint(value: Float): Short {
        val clamped = (value / 16f).coerceIn(-1f, 1f)
        return if (clamped == 1f) 0x7fff.toShort() else (clamped * 0x8000).toInt().coerceIn(-0x8000, 0x7fff).toShort()
    }

    private val displayIdPattern = Regex("New display: .*\\(id=(\\d+)\\)")

    /**
     * Pulls the virtual display's id out of the server's log. Callers must pass the
     * accumulated log, not a single read: the line can straddle two socket reads,
     * and half a line matches nothing.
     */
    fun parseDisplayId(log: String): Int? =
        displayIdPattern.find(log)?.groupValues?.get(1)?.toIntOrNull()
}

/** The AOSP `wm` windowing modes this app cares about, and how to read them back. */
internal object WindowingMode {

    const val FREEFORM = 5

    // `wm get-display-windowing-mode` has no documented, stable output format — some
    // builds print the int, some the WINDOWING_MODE_* name — so both are accepted.
    // The name is matched as a bare substring because it arrives underscored
    // (WINDOWING_MODE_FREEFORM), where \b would never fire: `_` is a word character.
    // The numeric branch requires the value to follow "mode" so that a display id
    // that happens to be 5 is not mistaken for the mode being freeform.
    private val freeformReplyPattern = Regex(
        """FREEFORM|(?:windowing\s+mode|mode)\s*[:=]?\s*$FREEFORM\b""",
        RegexOption.IGNORE_CASE
    )

    fun isFreeform(reply: String): Boolean = freeformReplyPattern.containsMatchIn(reply)
}
