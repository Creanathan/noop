package com.noop.ble

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #791: a BUSY-refused write that was delivered anyway must not be repeated.
 *
 * A reporter on a Galaxy S24 Ultra sent one `GET_DATA_RANGE` and received THREE CRC-valid responses whose
 * strap-side sequence numbers advanced (0x7e/0x7f/0x80) while the origin-seq echo stayed at 0x0a — so the
 * strap really did receive the command three times. It happened only after a burst of `writeCharacteristic
 * busy; retry n/12` lines and never on ticks without them: the stack reported the write as refused, sent it
 * anyway, and [WhoopBleClient]'s retry duplicated it.
 *
 * The mechanism is that `pendingRetry` was only ever cleared when the drain CONSUMED it, so a write-completion
 * callback — the proof that the refused write actually landed — left the retry armed. Now a completion
 * cancels it.
 *
 * Pins the pure decision only. The instance-level sequencing cannot be exercised in this harness: the
 * constructor builds a `Handler(Looper.getMainLooper())` and calls `context.getSystemService(...)`, both of
 * which throw against the stub `android.jar`, and the project ships no Robolectric — the same constraint
 * [GattCrashSafetyTest] documents, and the same pattern it follows.
 */
class BusyRetryDuplicateWriteTest {

    private val whoop4Cmd = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6")
    private val whoop5Cmd = UUID.fromString("fd4b0002-cce1-4033-93ce-002d5875f58a")
    private val someOtherChar = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")   // standard HR

    /** The bug: a completion arrived for a frame still held for retry, and the retry fired anyway. */
    @Test
    fun completionWhileAFrameIsHeldForRetryCancelsIt() {
        assertTrue(WhoopBleClient.shouldCancelBusyRetryOnCompletion(whoop4Cmd, hasFrameHeldForRetry = true))
    }

    /** Both families write commands on their own characteristic; neither may duplicate. */
    @Test
    fun bothFamiliesCommandChannelsQualify() {
        assertTrue(WhoopBleClient.shouldCancelBusyRetryOnCompletion(whoop5Cmd, hasFrameHeldForRetry = true))
    }

    /**
     * The #77/#312 protection must survive. A write the stack genuinely refused produces no completion, so
     * nothing reaches this predicate and the retry fires — but even if a completion arrives with nothing
     * held, it must not be treated as a cancellation.
     */
    @Test
    fun completionWithNothingHeldIsANoOp() {
        assertFalse(WhoopBleClient.shouldCancelBusyRetryOnCompletion(whoop4Cmd, hasFrameHeldForRetry = false))
        assertFalse(WhoopBleClient.shouldCancelBusyRetryOnCompletion(whoop5Cmd, hasFrameHeldForRetry = false))
    }

    /**
     * A completion on some other characteristic says nothing about the command frame being held. Cancelling
     * on it would drop the frame for real, converting a duplicate into the silent loss #77/#312 is about.
     */
    @Test
    fun completionOnAnotherCharacteristicNeverCancels() {
        assertFalse(WhoopBleClient.shouldCancelBusyRetryOnCompletion(someOtherChar, hasFrameHeldForRetry = true))
        assertFalse(WhoopBleClient.shouldCancelBusyRetryOnCompletion(null, hasFrameHeldForRetry = true))
    }

    /** The refusal codes the log must be able to tell apart, since only one of them means "safe to retry". */
    @Test
    fun writeStatusLabelNamesTheRefusalThatMatters() {
        assertTrue(WhoopBleClient.writeStatusLabel(201).contains("ERROR_GATT_WRITE_REQUEST_BUSY"))
        assertTrue(WhoopBleClient.writeStatusLabel(200).contains("ERROR_GATT_WRITE_NOT_ALLOWED"))
        assertTrue(WhoopBleClient.writeStatusLabel(3).contains("ERROR_DEVICE_NOT_BONDED"))
        // Pre-Android-13 has no status to report, and an unseen code must still be legible.
        assertTrue(WhoopBleClient.writeStatusLabel(null).contains("legacy-api"))
        assertTrue(WhoopBleClient.writeStatusLabel(4242).contains("4242"))
    }
}
