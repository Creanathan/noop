package com.noop.protocol

// Whoop5RawV20.kt — decoder for the WHOOP 5.0/MG 2140-byte "v20" deep-buffer offload record (#423).
// Kotlin twin of the v20 branch of WhoopProtocol's `decodeWhoop5HistoricalV2021` (Interpreter.swift) —
// byte-identical offsets, sample count, and block gating (parity contract).
//
// WHOOP 5.0/MG ONLY. v20 is a 5/MG concept: the 4.0 speaks v24/v25 and its type-0x2F frames are a
// different layout entirely (see WhoopBleClient: "R22 deep-data is a WHOOP 5/MG concept only").
//
// Frame layout (reassembled BLE frame = 8-byte puffin envelope + payload; offsets are FRAME-absolute):
//   @8   u8      packet type (47 = HISTORICAL_DATA)
//   @9   u8      layout version (20)
//   @10  u8      layout marker (0x81 on v20; 0x80 on the 1244-B v21 IMU buffer)
//   @11  u32 LE  monotonic lifetime record index (the same counter v18 carries)
//   @15  u32 LE  strap unix seconds for this record
//   then FIVE channel blocks, each preceded by a block-header byte (0x19 = active, 0x00 = empty):
//     header @0x1a / @0x1c0 / @0x366 / @0x50c / @0x6b2
//     an active block holds TWO channels of 25 i32 LE samples, at slot pairs
//     @0x2f/@0xf7, @0x1d5/@0x29d, @0x37b/@0x443, @0x521/@0x5e9, @0x6c7/@0x78f
//
// The record's TIME SPAN is not established (so neither is a sample rate) — see [Whoop5V20Frame].
//
// WHY 25 SAMPLES, NOT 50: across all 29,203 captured 2140-B buffers, exactly blocks 0/3/4 are active
// (channel slots @47/247/1313/1513/1735/1935) and, in every active channel, sample slots 25..49 are
// exactly 0 — only samples 0..24 carry data. Reading 50 emits arrays that are half zeros; the Swift
// decoder carried that bug until #545. The block-header byte itself reads 0x19 = 25 on every active
// block, consistent with a live per-block sample count. Each sample is a 4-byte LE container holding a
// 20-bit signed value (its upper 12 bits are only ever 0x000/0xFFF — pure sign extension — across all
// captures), so reading it as i32 recovers the correct signed magnitude with no masking.
//
// CHANNEL IDENTITY IS NOT PROVEN. No labelled/moving v20 capture exists in the tree, so channels stay
// neutrally named (`channel_b<block>_<half>`) and NO scale is applied — these are raw i32 counts with no
// absolute unit. RE notes only, NOT authoritative (issue #423): the buffer's config header [19:47] carries
// an LED-current field @28 and per-photodiode offset DACs @38/@45, and the six active channels are
// INFERRED to be optical (green/IR/red/ambient); the red/IR split is under active dispute, so no
// wavelength label is encoded here. Per the derived-signal rule this is decode-only instrumentation: it
// feeds no stored table, no migration, and no downstream gate.
//
// Pure/deterministic; no I/O, no strap. Deliberately has NO callers — see the class doc on [decode].

/** One decoded v20 channel: 25 raw i32 samples from one half of one active block. */
data class Whoop5V20Channel(
    val block: Int,             // 0..4 — which block header gated it
    val half: Int,              // 0 or 1 — which of the block's two channel slots
    val startOffset: Int,       // frame-absolute byte offset of sample 0
    val samples: List<Int>,     // 25 raw i32 counts; no scale, no unit (identity unproven)
) {
    /** The field name the Swift interpreter emits for this channel (`channel_b3_1`), so a Kotlin decode
     *  and a `whoop-decode` dump of the same buffer can be diffed key-for-key. */
    val name: String get() = "channel_b${block}_$half"
}

/**
 * One decoded 2140-B v20 buffer: the record header plus whichever channel blocks were active.
 *
 * Carries a sample COUNT and NO sample rate — deliberately, and unlike [Whoop5ImuFrame]. The v21 IMU
 * buffer earns its `sampleRateHz`/`ts(i)`: it is documented as a full second of inertial data and was
 * validated over 1423 real buffers. v20's record SPAN has never been established — "~25 Hz" is only an
 * inference from "25 samples, probably ~1 s", and PuffinDeepBufferLog's own note (2140 B = "~59
 * sub-records per timestamped second") would imply a far shorter span. The Swift decoder likewise emits
 * only `sensor_channel_samples`, never a rate. Publishing a rate here would let a future consumer bank
 * per-sample wall-clock timestamps derived from an unproven constant, so the span stays undecided until
 * a capture settles it (derived-signal rule; #423).
 */
data class Whoop5V20Frame(
    val baseTs: Long,               // strap unix seconds (@15; full u32 — Swift reads it into a 64-bit Int)
    val recordIndex: Long,          // monotonic lifetime record index (@11; full u32)
    val layoutMarker: Int,          // @10 (0x81 on every captured v20 buffer)
    val sampleCount: Int,           // 25 per active channel — the Swift `sensor_channel_samples` twin
    val channels: List<Whoop5V20Channel>,
)

object Whoop5RawV20 {

    const val bufferLength = 2140
    const val sampleCount = 25
    const val layoutVersion = 20
    const val historicalDataType = 47

    // FRAME-absolute offsets (8-byte puffin envelope + payload).
    private const val typeOff = 8
    private const val versionOff = 9
    private const val markerOff = 10
    private const val recordIndexOff = 11
    private const val tsOff = 15

    /** The five (block-header, channel-0, channel-1) offset triples, verbatim from the Swift decoder. */
    private val blocks = listOf(
        Triple(0x1a, 0x2f, 0xf7),
        Triple(0x1c0, 0x1d5, 0x29d),
        Triple(0x366, 0x37b, 0x443),
        Triple(0x50c, 0x521, 0x5e9),
        Triple(0x6b2, 0x6c7, 0x78f),
    )

    /**
     * Decode a v20 deep buffer, or null if [f] isn't one.
     *
     * Gates on the exact buffer length + the in-packet type (47) and layout version (20) bytes, NOT the
     * marker: the Swift decoder reports @10 rather than gating on it, so a buffer with an unexpected
     * marker must still decode identically here.
     *
     * Takes no [DeviceFamily] (matching [Whoop5RawImu.decode]) but reads WHOOP5-ABSOLUTE offsets — a 4.0
     * carries its type byte at @4, not @8. The 2140-B length gate makes a 4.0 misfire unreachable in
     * practice (its records are ~84-124 B), but a future caller should still resolve the family through
     * `DeviceFamily.forRegistryModel` and only reach here for WHOOP5, never string-compare a model label.
     *
     * Does NOT verify the envelope CRC, and that is the parity-faithful split rather than an omission:
     * Swift's `parseFrame` likewise decodes v20 fields on a CRC-bad frame and merely REPORTS `crcOK`,
     * leaving the gate to its callers (`rejectedHistoricalRecords` / `extractHistoricalStreams` both drop
     * on `crcOK == false`). A Kotlin caller must do the same — CRC-gate via `Framing.parseFrame` before
     * trusting or storing anything decoded here (BLE safety contract: CRC-gate every inbound frame).
     *
     * DELIBERATE, DOCUMENTED DIVERGENCE from Swift on MALFORMED input only: Swift's field layer decodes a
     * truncated v20 frame into however many channels fit, whereas this returns null for anything shorter
     * than 2140 B (the [Whoop5RawImu] gate idiom — the strap's v20 buffer is always exactly 2140 B). On
     * every real buffer the two agree byte-for-byte; the length gate only makes the Kotlin side stricter
     * about frames that could never come off a strap.
     *
     * NOTE: this decoder intentionally has NO production caller, and wiring one is NOT a free change.
     * v20 records currently reach `rejectedHistoricalRecords` on BOTH platforms (they carry `unix` but
     * neither heart_rate nor gravity), so the Backfiller archives their raw bytes before acking the trim —
     * which is what preserves them. Teaching [decodeHistorical] to return a v20 map would silently drop
     * them out of that archive while storing nothing, i.e. lose the data, and would also let a v20 frame
     * win `Backfiller`'s observed-`hist_version` probe.
     *
     * Nor is being callerless the house pattern — [Whoop5RawImu] shipped WITH its PuffinDeepBufferLog
     * caller in one commit (#481), as did its Swift side (#455). The reason is narrower: no parity-safe
     * consumer exists yet. v20 channel identity is unproven, and Swift deliberately keeps the 2140-B
     * buffer raw-only in its deep-buffer log, so wiring one on Android alone would re-open the parity gap
     * from the other side. A consumer wants both platforms at once, once #423 settles identity.
     */
    fun decode(f: ByteArray): Whoop5V20Frame? {
        if (f.size < bufferLength) return null
        if (u8(f, typeOff) != historicalDataType) return null
        if (u8(f, versionOff) != layoutVersion) return null

        val channels = ArrayList<Whoop5V20Channel>(blocks.size * 2)
        for ((b, blk) in blocks.withIndex()) {
            val (presentOff, ch0Off, ch1Off) = blk
            // Block-header byte: 0x19 = active, 0x00 = empty/zero-filled. Matches Swift's `!= 0` test
            // rather than an == 0x19 equality, so an active block with an unexpected count byte still
            // decodes instead of vanishing.
            if (u8(f, presentOff) == 0) continue
            for ((half, start) in listOf(0 to ch0Off, 1 to ch1Off)) {
                if (start + 4 * sampleCount > f.size) continue
                val samples = ArrayList<Int>(sampleCount)
                for (i in 0 until sampleCount) samples.add(i32(f, start + i * 4))
                channels.add(Whoop5V20Channel(block = b, half = half, startOffset = start, samples = samples))
            }
        }
        return Whoop5V20Frame(
            baseTs = u32(f, tsOff),
            recordIndex = u32(f, recordIndexOff),
            layoutMarker = u8(f, markerOff),
            sampleCount = sampleCount,
            channels = channels,
        )
    }

    // Little-endian readers (frame-absolute).
    private fun u8(f: ByteArray, o: Int): Int = f[o].toInt() and 0xFF

    private fun u32(f: ByteArray, o: Int): Long =
        (f[o].toLong() and 0xFF) or ((f[o + 1].toLong() and 0xFF) shl 8) or
            ((f[o + 2].toLong() and 0xFF) shl 16) or ((f[o + 3].toLong() and 0xFF) shl 24)

    /** Full 32-bit signed read — the sample container is 4 bytes holding a sign-extended 20-bit value,
     *  so no masking is applied (matching Swift `readI32`). */
    private fun i32(f: ByteArray, o: Int): Int =
        (f[o].toInt() and 0xFF) or ((f[o + 1].toInt() and 0xFF) shl 8) or
            ((f[o + 2].toInt() and 0xFF) shl 16) or ((f[o + 3].toInt() and 0xFF) shl 24)
}
