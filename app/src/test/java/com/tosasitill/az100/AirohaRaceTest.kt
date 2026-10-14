package com.tosasitill.az100

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free tests for the Airoha H4 / RACE wire format.
 *
 * This is the transport the module uses for every write to the headset over
 * SPP -- the outside-control mode, the adaptive flag, the battery queries and
 * the first-connect read-only sync all go through [AirohaRace.command] and are
 * read back through [AirohaRace.Parser].  A framing regression here writes an
 * unparsable packet or silently drops a response, which on the device looks
 * like "the tile stopped working" or "the battery froze", so the exact bytes
 * are pinned down here instead of only on hardware.
 */
class AirohaRaceTest {

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }

    private fun collect(vararg chunks: ByteArray): List<AirohaRace.Frame> {
        val out = ArrayList<AirohaRace.Frame>()
        val parser = AirohaRace.Parser()
        val sink = AirohaRace.Sink { out.add(it) }
        for (chunk in chunks) parser.feed(chunk, 0, chunk.size, sink)
        return out
    }

    /* ---------------- framing ---------------- */

    @Test
    fun commandFrame_isH4RaceWithPayload() {
        // Race id 10, payload [1, 100, 100] -> len = 2 + 3 = 5.
        val frame = AirohaRace.command(AirohaRace.RID_GET_OUTSIDE, bytes(1, 100, 100))
        assertEquals(
            "05 5a 05 00 0a 00 01 64 64",
            AirohaRace.hex(frame)
        )
        assertEquals(5 + 4, frame.size)
    }

    @Test
    fun commandFrame_encodesLowThenHighIdByte() {
        // 3286 = 0x0CD6, so the id is stored little-endian as D6 0C.
        val frame = AirohaRace.command(AirohaRace.RID_GET_BATTERY, null)
        assertEquals("05 5a 02 00 d6 0c", AirohaRace.hex(frame))
    }

    @Test
    fun hex_handlesNullAndEmpty() {
        assertEquals("null", AirohaRace.hex(null))
        assertEquals("", AirohaRace.hex(ByteArray(0)))
    }

    /* ---------------- parsing ---------------- */

    @Test
    fun parser_readsOneCommandFrame() {
        val frames = collect(AirohaRace.command(AirohaRace.RID_GET_OUTSIDE, bytes(2, 100, 100)))
        assertEquals(1, frames.size)
        val frame = frames[0]
        assertTrue(frame.isRace)
        assertEquals(AirohaRace.TYPE_CMD, frame.type)
        assertEquals(AirohaRace.RID_GET_OUTSIDE, frame.raceId)
        // Byte 6 of a RACE frame is the first payload byte -- for a command that
        // is not a status.  Pinned here so the accessor's meaning cannot drift.
        assertEquals(2, frame.status)
        assertEquals(100, frame.u8(7))
    }

    @Test
    fun parser_reassemblesFrameAcrossChunks() {
        val frame = AirohaRace.command(AirohaRace.RID_SET_OUTSIDE, bytes(1, 100, 100))
        // Split inside the length field, inside the payload, and at the last byte.
        val frames = collect(
            frame.copyOfRange(0, 1),
            frame.copyOfRange(1, 3),
            frame.copyOfRange(3, frame.size - 1),
            frame.copyOfRange(frame.size - 1, frame.size)
        )
        assertEquals(1, frames.size)
        assertEquals(AirohaRace.RID_SET_OUTSIDE, frames[0].raceId)
    }

    @Test
    fun parser_readsTwoFramesFromOneChunk() {
        val first = AirohaRace.command(AirohaRace.RID_GET_AGENT, null)
        val second = AirohaRace.command(AirohaRace.RID_GET_CRADLE, null)
        val frames = collect(first + second)
        assertEquals(2, frames.size)
        assertEquals(AirohaRace.RID_GET_AGENT, frames[0].raceId)
        assertEquals(AirohaRace.RID_GET_CRADLE, frames[1].raceId)
    }

    @Test
    fun parser_resyncsAfterLeadingNoise() {
        val frame = AirohaRace.command(AirohaRace.RID_GET_OUTSIDE, null)
        val frames = collect(bytes(0x99) + frame)
        assertEquals(1, frames.size)
        assertEquals(AirohaRace.RID_GET_OUTSIDE, frames[0].raceId)
    }

    @Test
    fun parser_treatsImpossibleRaceLengthAsNoise() {
        // 05 5A 00 00 ... : declared length 0 makes the frame 4 bytes, below the
        // 6 byte floor, so the byte is dropped and the parser keeps going.
        val bogus = bytes(5, AirohaRace.TYPE_CMD, 0, 0, 0, 0, 0, 0)
        val good = AirohaRace.command(AirohaRace.RID_GET_OUTSIDE, null)
        val frames = collect(bogus + good)
        assertEquals(1, frames.size)
        assertEquals(AirohaRace.RID_GET_OUTSIDE, frames[0].raceId)
    }

    @Test
    fun parser_acceptsAllThreeRaceChannels() {
        for (channel in intArrayOf(5, 0x15, 7)) {
            val frame = AirohaRace.command(AirohaRace.RID_GET_OUTSIDE, null).copyOf()
            frame[0] = channel.toByte()
            val frames = collect(frame)
            assertEquals("channel $channel", 1, frames.size)
            assertTrue("channel $channel", frames[0].isRace)
        }
    }

    /* ---------------- non-RACE channels ---------------- */

    @Test
    fun aclFrame_isNotRace() {
        // 02 00 0F <bodyLen LE16> <body>: a 4 byte body makes the frame 9 bytes.
        val acl = bytes(2, 0x00, 0x0f, 4, 0, 0x11, 0x22, 0x33, 0x44)
        val frames = collect(acl)
        assertEquals(1, frames.size)
        assertFalse(frames[0].isRace)
        assertEquals(-1, frames[0].raceId)
    }

    @Test
    fun hciEventFrame_isNotRace() {
        // 04 <event> <len> <payload>: event 0x0E is allowed by the parser.
        val event = bytes(4, 0x0e, 2, 0x01, 0x02)
        val frames = collect(event)
        assertEquals(1, frames.size)
        assertFalse(frames[0].isRace)
        assertEquals(-1, frames[0].raceId)
    }

    /* ---------------- the indication/status hazard ---------------- */

    @Test
    fun indicationFrame_statusIsActuallyTheFirstPayloadByte() {
        // 05 5D 05 00 D6 0C <b6> <role> <level>: nine bytes, so `status` (byte 6)
        // and `u8(7)`/`u8(8)` (role, level) are all readable.  For an indication
        // byte 6 is payload, not a status -- a consumer that gates on
        // `frame.status == 0` therefore also gates on the first payload byte.
        val zero = collect(bytes(5, AirohaRace.TYPE_IND, 5, 0, 0xd6, 0x0c, 0x00, 0x00, 0x40))
        assertEquals(1, zero.size)
        assertEquals(0, zero[0].status)
        assertEquals(0x40, zero[0].u8(8))

        val one = collect(bytes(5, AirohaRace.TYPE_IND, 5, 0, 0xd6, 0x0c, 0x01, 0x00, 0x40))
        assertEquals(1, one.size)
        assertEquals(1, one[0].status)
        // The role byte at index 7 is the same in both cases: only the accessor
        // named `status` differs, which is exactly the trap.
        assertEquals(one[0].u8(7), zero[0].u8(7))
    }

    @Test
    fun frameAccessors_areBoundsSafeOnShortInput() {
        // A one byte "frame" can only happen if a caller builds one by hand; the
        // accessors must not throw a StringIndexOutOfBounds on it.
        val tiny = AirohaRace.Frame(bytes(5))
        assertTrue(tiny.isRace)
        assertEquals(0, tiny.type)
        assertEquals(-1, tiny.raceId)
        assertEquals(-1, tiny.status)
    }
}
