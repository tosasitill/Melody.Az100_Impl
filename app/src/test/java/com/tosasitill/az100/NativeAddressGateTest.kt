package com.tosasitill.az100

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the address-matching contract of the native patch.
 *
 * `libaz100patch.so` replaces two OPPO functions with a comparison stub that
 * answers for the AZ100's addresses and falls through to the original body for
 * everything else.  The stub is hand-encoded AArch64, so it cannot be exercised
 * from a JVM test -- but the *decision table* it implements can be, and that is
 * the part a future edit is most likely to get wrong.
 *
 * [decide] is a transcription of the stub's comparisons, including its
 * tolerance of both byte orders.  [constantsInSource] additionally reads the C
 * source and asserts the address literals are still present, so changing the
 * addresses in one place without the other fails here.
 *
 * What this does NOT cover: the instruction encoding itself.  That was verified
 * by disassembling the built library and checking every branch target, and it
 * is re-verified on device through the `native: patch rc=` log line.
 */
class NativeAddressGateTest {

    /**
     * Model of the stub: `true` answers, `false` falls through to OPPO's code.
     *
     * The stub loads a 32-bit word at offset 0 and a 16-bit word at offset 4,
     * because `RawAddress` is six octets plus a type byte.  It accepts either
     * octet order, so both layouts are modelled here.
     */
    private fun decide(raw: ByteArray): Boolean {
        val w8 = ((raw[0].toInt() and 0xFF) or
                ((raw[1].toInt() and 0xFF) shl 8) or
                ((raw[2].toInt() and 0xFF) shl 16) or
                ((raw[3].toInt() and 0xFF) shl 24)).toUInt()
        val w9 = ((raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8)).toUInt()

        // Little-endian layout: the two high octets of the address land in w9.
        if (w9 == 0xB820u) {
            return w8 == 0x8EEE8485u || w8 == 0x8EEE3C62u
        }
        // Big-endian layout: the two high octets land in w8 instead.
        if (w8 == 0xEE8E20B8u) {
            return w9 == 0x8584u || w9 == 0x623Cu
        }
        return false
    }

    /** `RawAddress` stores octets least-significant first. */
    private fun rawLe(mac: String): ByteArray =
        mac.split(':').map { it.toInt(16).toByte() }.reversed().toByteArray()

    private fun rawBe(mac: String): ByteArray =
        mac.split(':').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun matchesBothEarbudsInEitherByteOrder() {
        for (mac in listOf(Az100Hook.AZ100_MAC, Az100Hook.AZ100_LE_MAC)) {
            assertTrue("$mac (LE order)", decide(rawLe(mac)))
            assertTrue("$mac (BE order)", decide(rawBe(mac)))
        }
    }

    @Test
    fun fallsThroughForEveryOtherDevice() {
        // A different headset, a car kit, and a HID peer: all must keep OPPO's
        // original answer.  This is the property that made the previous blanket
        // `mov w0,#1; ret` unacceptable.
        val others = listOf(
            "CC:88:26:11:22:33",
            "00:1B:DC:0F:1E:2D",
            "9C:AA:5D:34:7D:A6",
            "B8:20:8E:EE:84:86",
            "B8:20:8E:EE:3C:63",
            "B8:20:8E:EF:84:85",
        )
        for (mac in others) {
            assertFalse("$mac (LE order)", decide(rawLe(mac)))
            assertFalse("$mac (BE order)", decide(rawBe(mac)))
        }
    }

    @Test
    fun nearMissesOnTheSharedPrefixAreRejected() {
        // The two earbuds share `B8:20:8E:EE`; only the final two octets differ.
        // A stub that compared only the shared part would answer for any device
        // in that OUI block, so the tail is what the test leans on.
        assertTrue(decide(rawLe("B8:20:8E:EE:84:85")))
        assertTrue(decide(rawLe("B8:20:8E:EE:3C:62")))
        assertFalse(decide(rawLe("B8:20:8E:EE:00:00")))
        assertFalse(decide(rawLe("B8:20:8E:EE:FF:FF")))
    }

    @Test
    fun sourceStillCarriesBothAddresses() {
        val source = File("src/main/cpp/az100_patch.c")
        assertTrue(
            "az100_patch.c not found from ${File(".").absolutePath}",
            source.isFile
        )
        val text = source.readText()
        // The literals the stub compares against, per byte order.  A change to
        // the addresses must be made in both the Kotlin constants and here.
        for (literal in listOf("0x8EEE8485", "0x8EEE3C62", "0xB820", "0xEE8E20B8", "0x8584", "0x623C")) {
            assertTrue("az100_patch.c no longer contains $literal", text.contains(literal))
        }
    }

    @Test
    fun kotlinConstantsAreDistinct() {
        assertEquals("B8:20:8E:EE:84:85", Az100Hook.AZ100_MAC)
        assertEquals("B8:20:8E:EE:3C:62", Az100Hook.AZ100_LE_MAC)
        assertTrue(Az100Hook.isAz100Mac(Az100Hook.AZ100_MAC))
        assertTrue(Az100Hook.isAz100Mac(Az100Hook.AZ100_MAC.lowercase()))
        assertFalse(Az100Hook.isAz100Mac(Az100Hook.AZ100_LE_MAC))
        assertFalse(Az100Hook.isAz100Mac(null))
    }
}
