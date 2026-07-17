package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [Whoop5RawV20.decode] — the WHOOP 5/MG 2140-byte v20 deep buffer (#423). Kotlin twin of the v20
 * half of the Swift Whoop5HistoricalV2021Tests, asserted against the SAME real captured buffer so the two
 * decoders are proven byte-identical on real hardware bytes rather than merely similar in shape.
 *
 * Asserts STRUCTURE only, never channel identity: no labelled/moving v20 capture exists, so the channels
 * stay neutrally named and unscaled (see [Whoop5RawV20]).
 */
class Whoop5RawV20Test {

    /** Build a valid-length v20 frame; the closure fills the body. Only the bytes [Whoop5RawV20] gates on
     *  (type\@8, version\@9) are pre-set — this decoder reads the record, not the CRC envelope. */
    private fun syntheticFrame(build: (ByteArray) -> Unit): ByteArray {
        val f = ByteArray(Whoop5RawV20.bufferLength)
        f[8] = 0x2F.toByte()          // type 47 = HISTORICAL_DATA
        f[9] = 20                     // layout version
        f[10] = 0x81.toByte()         // marker
        build(f)
        return f
    }

    private fun putI32(f: ByteArray, o: Int, v: Int) {
        f[o] = (v and 0xFF).toByte(); f[o + 1] = ((v shr 8) and 0xFF).toByte()
        f[o + 2] = ((v shr 16) and 0xFF).toByte(); f[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putU32(f: ByteArray, o: Int, v: Long) {
        f[o] = (v and 0xFF).toByte(); f[o + 1] = ((v shr 8) and 0xFF).toByte()
        f[o + 2] = ((v shr 16) and 0xFF).toByte(); f[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun channel(d: Whoop5V20Frame, name: String): Whoop5V20Channel? = d.channels.firstOrNull { it.name == name }

    // --- the real captured buffer (the parity assertion that matters) ---

    /**
     * Real 2140-B v20 buffer off a WHOOP 5.0, one line of the 29,203-buffer set behind the sample-count
     * fix (#423/#545). Every expected value below is copied from the Swift test's assertions on this exact
     * frame — if Kotlin and Swift ever diverge on real bytes, this fails.
     */
    @Test fun realFrameDecodesHeaderAndSixActiveChannels() {
        val f = hexToBytes(realFrameV20Hex)
        assertEquals(2140, f.size)
        val d = Whoop5RawV20.decode(f)
        assertNotNull("real v20 buffer must decode", d)
        d!!
        assertEquals(0x81, d.layoutMarker)
        assertEquals(11494060L, d.recordIndex)
        assertEquals(1784054004L, d.baseTs)
        assertEquals(25, d.sampleRateHz)

        // Exactly blocks 0/3/4 active on every captured buffer -> six channels; blocks 1/2 emit nothing.
        assertEquals(6, d.channels.size)
        assertEquals(
            listOf("channel_b0_0", "channel_b0_1", "channel_b3_0", "channel_b3_1", "channel_b4_0", "channel_b4_1"),
            d.channels.map { it.name },
        )
        assertNull(channel(d, "channel_b1_0"))
        assertNull(channel(d, "channel_b2_0"))

        // The six proven active channel slots, each decoding exactly 25 samples (the count #545 is about).
        assertEquals(listOf(47, 247, 1313, 1513, 1735, 1935), d.channels.map { it.startOffset })
        for (c in d.channels) assertEquals("${c.name} must decode 25 samples, not 50", 25, c.samples.size)

        // Spot-check decoded values against the Swift assertions on the same artifact.
        assertEquals(118434, channel(d, "channel_b0_0")!!.samples.first())
        assertEquals(147258, channel(d, "channel_b0_0")!!.samples.last())    // sample 24, not sample 49
        assertEquals(-22101, channel(d, "channel_b0_1")!!.samples.first())   // negative -> sign-extension check
        assertEquals(11318, channel(d, "channel_b4_1")!!.samples.last())
    }

    /**
     * The decisive artifact fact behind the 25-vs-50 fix: at each active channel start the i32 slots for
     * samples 25..49 are exactly 0 in the RAW frame, while sample 24 is a real value. Reading 50 was
     * reading padding. Asserted against the raw bytes, independent of the decoder.
     */
    @Test fun realFrameTailSlotsAreZeroPadding() {
        val f = hexToBytes(realFrameV20Hex)
        fun rawI32(o: Int): Int =
            (f[o].toInt() and 0xFF) or ((f[o + 1].toInt() and 0xFF) shl 8) or
                ((f[o + 2].toInt() and 0xFF) shl 16) or ((f[o + 3].toInt() and 0xFF) shl 24)
        for (start in listOf(47, 247, 1313, 1513, 1735, 1935)) {
            assertTrue("sample 24 at channel @$start is a real value", rawI32(start + 24 * 4) != 0)
            for (i in 25 until 50) {
                assertEquals("sample $i at channel @$start must be zero padding", 0, rawI32(start + i * 4))
            }
        }
    }

    // --- decode mechanics on synthetic frames ---

    /** Fill ALL 50 slots with a monotone sentinel so the test proves the decoder keeps only samples 0..24
     *  and DROPS 25..49 — the half-zeros bug a 50-sample read reintroduces. */
    @Test fun keepsFirst25SamplesAndDropsPaddingSlots() {
        val f = syntheticFrame { f ->
            putU32(f, 11, 0x01A8CF26L)
            putU32(f, 15, 1781556372L)
            f[0x1a] = 0x19                                                  // block 0 active
            for (i in 0 until 50) putI32(f, 0x2f + i * 4, 100000 + i)       // ch b0_0
            for (i in 0 until 50) putI32(f, 0xf7 + i * 4, 200000 - i)       // ch b0_1
            f[0x1c0] = 0x00                                                 // block 1 empty
            f[0x50c] = 0x19                                                 // block 3 active
            for (i in 0 until 50) putI32(f, 0x521 + i * 4, 140 + i)
            for (i in 0 until 50) putI32(f, 0x5e9 + i * 4, 130 + i)
        }
        val d = Whoop5RawV20.decode(f)!!
        assertEquals(0x01A8CF26L, d.recordIndex)
        assertEquals(1781556372L, d.baseTs)
        // Active blocks 0 and 3 -> 4 channels; the empty block 1 contributes none.
        assertEquals(4, d.channels.size)
        val b00 = channel(d, "channel_b0_0")!!.samples
        assertEquals(25, b00.size)                     // 25, not 50
        assertEquals(100000, b00.first())
        assertEquals(100024, b00.last())               // sample 24, not sample 49
        assertEquals(200000, channel(d, "channel_b0_1")!!.samples.first())
        assertEquals(140, channel(d, "channel_b3_0")!!.samples.first())
        assertNull(channel(d, "channel_b1_0"))
    }

    /** A block header is gated on != 0, not on == 0x19, so an active block with an unexpected count byte
     *  still decodes (mirrors the Swift `frame[blk.present] != 0` test). */
    @Test fun anyNonZeroBlockHeaderCountsAsActive() {
        val f = syntheticFrame { f ->
            f[0x1a] = 0x07                                            // not 0x19, but non-zero
            for (i in 0 until 25) putI32(f, 0x2f + i * 4, 7 + i)
        }
        val d = Whoop5RawV20.decode(f)!!
        assertEquals(2, d.channels.size)                              // both halves of block 0
        assertEquals(7, channel(d, "channel_b0_0")!!.samples.first())
    }

    /** An all-empty buffer decodes its header and zero channels — never null (the record is still real). */
    @Test fun allBlocksEmptyYieldsHeaderAndNoChannels() {
        val d = Whoop5RawV20.decode(syntheticFrame { putU32(it, 15, 1784054004L) })!!
        assertEquals(0, d.channels.size)
        assertEquals(1784054004L, d.baseTs)
    }

    /** Gating: wrong version, wrong packet type, or a short frame must all decline rather than misread.
     *  Notably the 1244-B v21 IMU buffer — the other deep buffer on the same wire — must not decode here. */
    @Test fun rejectsNonV20Buffers() {
        assertNull("v21 IMU buffer is not v20", Whoop5RawV20.decode(ByteArray(1244).also { it[8] = 0x2F; it[9] = 21 }))
        assertNull("wrong layout version", Whoop5RawV20.decode(syntheticFrame { it[9] = 18 }))
        assertNull("wrong packet type", Whoop5RawV20.decode(syntheticFrame { it[8] = 0x31 }))
        assertNull("short frame", Whoop5RawV20.decode(ByteArray(2139).also { it[8] = 0x2F; it[9] = 20 }))
    }

    /** Sample timestamps spread evenly across the record's second at 25 Hz. */
    @Test fun sampleTimestampsSpreadAcrossTheSecond() {
        val d = Whoop5RawV20.decode(syntheticFrame { putU32(it, 15, 1784054004L) })!!
        assertEquals(1784054004.0, d.ts(0), 1e-9)
        assertEquals(1784054004.96, d.ts(24), 1e-9)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }

    companion object {
        /** One real 2140-byte type-0x2F v20 buffer captured off a WHOOP 5.0 (fw 50.x), issue #423 — the
         *  same artifact the Swift Whoop5HistoricalV2021Tests uses. Kept as hex so the test is
         *  self-contained (no fixture file, runs on any machine with no strap). */
        private const val realFrameV20Hex =
            "aa0154080100b5b32f1481ac62af00f480566ab85e04001900001901160d042c1a0320000000000004200000002003a2ce0100abcb0100daca010004" +
            "cd0100ffd00100ded50100fedb010019e001003be201004ae50100d0e90100d9ef0100acf601004afd01005d040200ce0e0200db180200622402005f" +
            "2e020075320200be34020037390200e03c02004a3f02003a3f0200000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "00000000000000aba9ffff57a8ffff09a7ffff8ca5ffff25a3ffffeca1ffff30a1ffffe6a0ffff8fa1fffff6a3ffffeaa7fffffaacffff0db3ffffde" +
            "baffff78c0ffff70c5fffffdd6ffff3fe1ffffeae3ffff6eedffff0debffff64e0ffff17d9ffffbad4ffff39cfffff00000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000003ce3104000001100000000000021000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000002fa190400000120000000400602200000006009000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000000000000000000190200000400000320000000000004200000000000b9000000c00000" +
            "00ae000000bd000000b3000000b0000000ba000000bc000000c6000000a0000000b7000000b9000000b0000000bb000000af000000a6000000aa0000" +
            "00b9000000b8000000b7000000bb0000009f000000ae000000ac000000ab000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000c3000000b7000000c8000000bb000000a9000000bb000000c7000000ac000000ca000000a2000000bd000000bc0000" +
            "00b9000000bf000000b7000000bc000000b5000000b8000000ae000000b7000000c0000000b6000000ba000000be000000aa00000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000001902c8000400000320000000000001200000000000b0860100ca" +
            "8601001d87010018880100b5880100c08801005d8901006c89010016890100f688010010890100b4890100b18a0100fe8b01003d8d0100be8f0100e5" +
            "920100c29501001e980100ab9a0100bb9e010010a201008ba301002fa40100fba3010000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000f82600000f2700002d27000058270000a2270000b5270000cb270000c6270000a727000090270000892700009c" +
            "270000c0270000e7270000172800008a280000ca280000f8280000822900005f2a00008d2b00003f2c00008d2c0000942c0000362c00000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000a9a4a5c0"
    }
}
