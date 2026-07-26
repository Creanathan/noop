package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #520: `dynamic_acceleration@41` has been decoded on both platforms since the v18 layout was mapped, and
 * nothing has ever consumed it — so there is no evidence on whether it is a usable stillness signal or
 * redundant against the gravity deltas SleepStager already derives. These pin the diagnostic that collects
 * that evidence: a summary, not a stream, logged once per offload session.
 *
 * Kotlin twin of `DynAccelDiagTests.swift` — same vectors, same expected values, same log-line bytes.
 */
class DynAccelDiagTest {

    private val thr = 0.01   // mirrors DYN_ACCEL_STILL_THRESHOLD_G / SleepStager.gravityStillThresholdG

    /** Nothing arrived: derived values are null and the line is suppressed, so a WHOOP 4.0 stays quiet. */
    @Test
    fun emptyDiagIsSilent() {
        val d = DynAccelDiag()
        assertEquals(0, d.count)
        assertNull(d.mean)
        assertNull(d.stillFraction)
        assertNull(d.min)
        assertNull(d.max)
        assertNull(d.logLine(thr))
    }

    /** The real f32@41 values from the six captured v18 frames; three of the six fall under the cut. */
    @Test
    fun foldsRealOracleValues() {
        val d = DynAccelDiag()
        listOf(0.009160, 0.010708, 0.005963, 0.014449, 0.032788, 0.008421).forEach { d.add(it, thr) }
        assertEquals(6, d.count)
        assertEquals(3, d.still)
        assertEquals(0.5, d.stillFraction!!, 1e-12)
        assertEquals(0.005963, d.min!!, 1e-12)
        assertEquals(0.032788, d.max!!, 1e-12)
        assertEquals(0.081489 / 6, d.mean!!, 1e-9)
    }

    /** Strict `<`, matching the stager's own comparison — a value exactly at the cut is not still. */
    @Test
    fun thresholdIsExclusive() {
        val d = DynAccelDiag()
        d.add(thr, thr)
        assertEquals(0, d.still)
        d.add(thr - 1e-9, thr)
        assertEquals(1, d.still)
    }

    /** One NaN would otherwise poison mean/min/max for a whole session, so non-finite values are dropped. */
    @Test
    fun nonFiniteValuesAreIgnored() {
        val d = DynAccelDiag()
        d.add(0.02, thr)
        d.add(Double.NaN, thr)
        d.add(Double.POSITIVE_INFINITY, thr)
        assertEquals(1, d.count)
        assertEquals(0.02, d.mean!!, 1e-12)
        assertEquals(0.02, d.max!!, 1e-12)
    }

    /** A batch is an arbitrary slice of an offload; the still-fraction only means anything once merged. */
    @Test
    fun mergeCombinesBatches() {
        val a = DynAccelDiag()
        a.add(0.004, thr)
        a.add(0.006, thr)
        val b = DynAccelDiag()
        b.add(0.500, thr)
        a.merge(b)
        assertEquals(3, a.count)
        assertEquals(2, a.still)
        assertEquals(0.004, a.min!!, 1e-12)
        assertEquals(0.500, a.max!!, 1e-12)
        assertEquals(0.510 / 3, a.mean!!, 1e-12)
    }

    /** A console-only batch is common mid-offload and must not disturb the accumulator. */
    @Test
    fun mergingEmptyIsNoOp() {
        val a = DynAccelDiag()
        a.add(0.02, thr)
        a.merge(DynAccelDiag())
        assertEquals(1, a.count)
        assertEquals(0.02, a.min!!, 1e-12)
        assertEquals(0.02, a.max!!, 1e-12)
    }

    /** The session accumulator starts empty, so merging into empty must adopt the other side's bounds. */
    @Test
    fun mergeIntoEmptyAdoptsBounds() {
        val a = DynAccelDiag()
        val b = DynAccelDiag()
        b.add(0.03, thr)
        b.add(0.07, thr)
        a.merge(b)
        assertEquals(b, a)
    }

    /**
     * The byte-identical contract with Swift. Locale matters here: the formatter must use Locale.ROOT, or a
     * de/fr device would render `0,773` and silently produce a different corpus from macOS.
     */
    @Test
    fun logLineFormat() {
        val d = DynAccelDiag()
        d.add(0.004, thr)
        d.add(0.006, thr)
        d.add(2.310, thr)
        assertEquals(
            "Backfill: dynaccel n=3 still=67% mean=773mg range=4..2310mg " +
                "(thr 10mg) — diagnostic only, not stored or scored (#520)",
            d.logLine(thr),
        )
    }

    /**
     * The rounding trap. C (Swift) rounds ties half-to-EVEN, Java (Kotlin) rounds HALF_UP, so `%.3f` of
     * 0.0625 g is 0.062 on one platform and 0.063 on the other — and 0.0625 is exactly representable in the
     * f32 the strap sends. The line renders integers instead, rounded half-AWAY-from-zero, the one rule both
     * languages agree on for non-negative input. Same expectations as the Swift twin.
     */
    @Test
    fun tiesRoundAwayFromZeroNotPrintfStyle() {
        assertEquals(63, DynAccelDiag.mg(0.0625))
        assertEquals(14, DynAccelDiag.mg(0.0135))
        assertEquals(67, DynAccelDiag.pct(0.665))
        assertEquals(1, DynAccelDiag.pct(0.005))
    }

    /** The locale trap, asserted directly: the line must not change when the default locale is comma-decimal. */
    @Test
    fun logLineIsLocaleIndependent() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val d = DynAccelDiag()
            d.add(0.004, thr)
            d.add(0.006, thr)
            d.add(2.310, thr)
            assertEquals(
                "Backfill: dynaccel n=3 still=67% mean=773mg range=4..2310mg " +
                    "(thr 10mg) — diagnostic only, not stored or scored (#520)",
                d.logLine(thr),
            )
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
