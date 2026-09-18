package com.localdex.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the scrcpy wire format and the server/shell output the session parses.
 *
 * Several of these encode bugs that actually shipped: the display-id line
 * straddling two socket reads, and a windowing-mode reply whose display id
 * happened to be 5 being mistaken for freeform mode.
 */
class ScrcpyProtocolTest {

    // -- Header field reads ------------------------------------------------------

    @Test
    fun `readInt reads big endian`() {
        val data = byteArrayOf(0x00, 0x00, 0x07, 0x80.toByte())
        assertEquals(1920, ScrcpyProtocol.readInt(data, 0))
    }

    @Test
    fun `readInt honours the offset`() {
        val data = byteArrayOf(
            0x7f, 0x7f, 0x7f, 0x7f, // padding the read must skip
            0x00, 0x00, 0x05, 0xa0.toByte()
        )
        assertEquals(1440, ScrcpyProtocol.readInt(data, 4))
    }

    @Test
    fun `readInt treats bytes as unsigned`() {
        val all = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        assertEquals(-1, ScrcpyProtocol.readInt(all, 0))

        val high = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        assertEquals(Int.MIN_VALUE + 1, ScrcpyProtocol.readInt(high, 0))
    }

    @Test
    fun `readLong joins two big endian ints without sign extending the low half`() {
        // Low word has its top bit set: a naive or() would smear ones into the high word.
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()
        )
        assertEquals(0x00000001_ffffffffL, ScrcpyProtocol.readLong(data, 0))
    }

    // -- Packet classification ---------------------------------------------------

    @Test
    fun `session packet is flagged by the top bit of the first byte`() {
        val session = ByteArray(ScrcpyProtocol.PACKET_HEADER_SIZE).also { it[0] = 0x80.toByte() }
        assertTrue(ScrcpyProtocol.isSessionPacket(session))

        val frame = ByteArray(ScrcpyProtocol.PACKET_HEADER_SIZE).also { it[0] = 0x7f }
        assertFalse(ScrcpyProtocol.isSessionPacket(frame))
    }

    @Test
    fun `config packets are flagged by bit 62`() {
        assertTrue(ScrcpyProtocol.isConfigPacket(ScrcpyProtocol.FLAG_CONFIG))
        assertTrue(ScrcpyProtocol.isConfigPacket(ScrcpyProtocol.FLAG_CONFIG or 1234L))
        assertFalse(ScrcpyProtocol.isConfigPacket(1234L))
    }

    @Test
    fun `ptsOf strips the flag bits but keeps the timestamp`() {
        val keyFrameFlag = 1L shl 61
        val pts = 1_234_567L
        val ptsAndFlags = ScrcpyProtocol.FLAG_CONFIG or keyFrameFlag or pts

        assertEquals(pts, ScrcpyProtocol.ptsOf(ptsAndFlags))
    }

    @Test
    fun `packet sizes outside the plausible range are rejected`() {
        assertFalse(ScrcpyProtocol.isPlausiblePacketSize(0))
        assertFalse(ScrcpyProtocol.isPlausiblePacketSize(-1))
        assertFalse(ScrcpyProtocol.isPlausiblePacketSize(ScrcpyProtocol.MAX_PACKET_SIZE + 1))
        assertTrue(ScrcpyProtocol.isPlausiblePacketSize(1))
        assertTrue(ScrcpyProtocol.isPlausiblePacketSize(ScrcpyProtocol.MAX_PACKET_SIZE))
    }

    // -- Control message encoding ------------------------------------------------

    @Test
    fun `pressure encodes as u16 fixed point`() {
        assertEquals(0, ScrcpyProtocol.pressureToU16FixedPoint(0f).toInt())
        // 1.0 saturates to 0xffff, which is -1 read back as a signed short.
        assertEquals(0xffff, ScrcpyProtocol.pressureToU16FixedPoint(1f).toInt() and 0xffff)
        assertEquals(0x8000, ScrcpyProtocol.pressureToU16FixedPoint(0.5f).toInt() and 0xffff)
    }

    @Test
    fun `pressure is clamped to the unit range`() {
        assertEquals(0, ScrcpyProtocol.pressureToU16FixedPoint(-5f).toInt())
        assertEquals(0xffff, ScrcpyProtocol.pressureToU16FixedPoint(5f).toInt() and 0xffff)
    }

    @Test
    fun `scroll encodes as i16 fixed point over minus 16 to 16`() {
        assertEquals(0, ScrcpyProtocol.scrollToI16FixedPoint(0f).toInt())
        assertEquals(0x7fff, ScrcpyProtocol.scrollToI16FixedPoint(16f).toInt())
        assertEquals(-0x8000, ScrcpyProtocol.scrollToI16FixedPoint(-16f).toInt())
    }

    @Test
    fun `scroll saturates rather than wrapping past the range`() {
        assertEquals(0x7fff, ScrcpyProtocol.scrollToI16FixedPoint(1000f).toInt())
        assertEquals(-0x8000, ScrcpyProtocol.scrollToI16FixedPoint(-1000f).toInt())
    }

    // -- Server log parsing ------------------------------------------------------

    @Test
    fun `parseDisplayId reads the id out of the server log line`() {
        val log = "[server] INFO: New display: 1920x1440/240 (id=7)\n"
        assertEquals(7, ScrcpyProtocol.parseDisplayId(log))
    }

    @Test
    fun `parseDisplayId ignores logs without the line`() {
        assertNull(ScrcpyProtocol.parseDisplayId(""))
        assertNull(ScrcpyProtocol.parseDisplayId("[server] INFO: Device: SM-F998B\n"))
    }

    /**
     * Regression: the reader used to hand each 4KB socket read to the parser on its
     * own, so a line split across two reads matched neither half and the display id
     * — and with it freeform forcing — was silently never found.
     */
    @Test
    fun `parseDisplayId finds a line split across two reads once they are joined`() {
        val firstRead = "[server] INFO: New display: 1920x1440/240 (id="
        val secondRead = "5)\n"

        assertNull(ScrcpyProtocol.parseDisplayId(firstRead))
        assertNull(ScrcpyProtocol.parseDisplayId(secondRead))
        assertEquals(5, ScrcpyProtocol.parseDisplayId(firstRead + secondRead))
    }

    @Test
    fun `parseDisplayId takes the first id when the log has scrolled on`() {
        val log = "New display: 1920x1440/240 (id=3)\nsome later line\n"
        assertEquals(3, ScrcpyProtocol.parseDisplayId(log))
    }

    // -- Windowing mode replies --------------------------------------------------

    @Test
    fun `freeform is recognised from a numeric reply`() {
        assertTrue(WindowingMode.isFreeform("Windowing mode: 5"))
        assertTrue(WindowingMode.isFreeform("windowing mode=5"))
    }

    @Test
    fun `freeform is recognised from a named reply`() {
        assertTrue(WindowingMode.isFreeform("WINDOWING_MODE_FREEFORM"))
        assertTrue(WindowingMode.isFreeform("Windowing mode: freeform"))
    }

    @Test
    fun `other windowing modes are not freeform`() {
        assertFalse(WindowingMode.isFreeform("Windowing mode: 1"))
        assertFalse(WindowingMode.isFreeform("WINDOWING_MODE_FULLSCREEN"))
        assertFalse(WindowingMode.isFreeform(""))
    }

    /**
     * Regression: matching a bare "5" anywhere in the reply meant a display whose id
     * was 5 read as freeform no matter what mode it was actually in.
     */
    @Test
    fun `a display id of 5 is not mistaken for freeform mode`() {
        assertFalse(WindowingMode.isFreeform("Display 5: windowing mode: 1"))
        assertFalse(WindowingMode.isFreeform("display id=5 mode=1"))
    }
}
