package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #550: the coverage pair encodes WHICH over-count a night has, and until now that reading rule lived only
 * in a comment — so triaging an "HRV reads ~2x high" report required knowing it. These pin the rule.
 *
 * Kotlin twin of `RrCoverageVerdictTests`, same vectors and same expected verdicts. The real-capture pairs
 * come from #803 (WHOOP 4.0, two consecutive nights).
 */
class RrCoverageVerdictTest {

    /** A night whose beat-time fits the wall clock. #803's 2026-07-15. */
    @Test
    fun cleanNightIsPlausible() {
        assertEquals(
            HrvAnalyzer.RrCoverageVerdict.PLAUSIBLE,
            HrvAnalyzer.classifyCoverage(0.89, 0.88),
        )
    }

    /**
     * The night that prompted the report. #803's 2026-07-16: collapsing same-second duplicates drops it
     * 2.54 -> 1.99, still impossible — so the duplicates straddle second boundaries and a same-second
     * de-dup would NOT fix it. This is the case #550 needs to know about.
     */
    @Test
    fun realCrossSecondCaptureIsNotFixableBySameSecondDedup() {
        assertEquals(
            HrvAnalyzer.RrCoverageVerdict.CROSS_SECOND_OVER_COUNT,
            HrvAnalyzer.classifyCoverage(2.54, 1.99),
        )
    }

    /** Over-covered, but the collapse brings it back in range — the extra beats share a timestamp. */
    @Test
    fun collapseRecoveringRangeMeansSameSecond() {
        assertEquals(
            HrvAnalyzer.RrCoverageVerdict.SAME_SECOND_OVER_COUNT,
            HrvAnalyzer.classifyCoverage(1.60, 1.02),
        )
    }

    /** The ceiling is a rounding allowance: exactly at it still fits, a hair over does not. */
    @Test
    fun ceilingIsInclusive() {
        val ceil = HrvAnalyzer.COVERAGE_PLAUSIBLE_CEILING
        assertEquals(
            HrvAnalyzer.RrCoverageVerdict.PLAUSIBLE,
            HrvAnalyzer.classifyCoverage(ceil, ceil),
        )
        assertEquals(
            HrvAnalyzer.RrCoverageVerdict.SAME_SECOND_OVER_COUNT,
            HrvAnalyzer.classifyCoverage(ceil + 0.01, 0.5),
        )
    }

    /**
     * `rrCoverage` returns 0.0 for a window it cannot measure (< 2 beats / zero span). That is an absence
     * of evidence, not a clean night, but it must not read as over-covered either.
     */
    @Test
    fun unmeasurableWindowIsNotFlagged() {
        assertEquals(
            HrvAnalyzer.RrCoverageVerdict.PLAUSIBLE,
            HrvAnalyzer.classifyCoverage(0.0, 0.0),
        )
    }

    /** The verdict keys on coverage first; it must not depend on collapsed <= coverage holding. */
    @Test
    fun collapsedAboveCoverageStillClassifiesOnCoverageFirst() {
        assertEquals(
            HrvAnalyzer.RrCoverageVerdict.PLAUSIBLE,
            HrvAnalyzer.classifyCoverage(0.5, 9.9),
        )
    }
}
