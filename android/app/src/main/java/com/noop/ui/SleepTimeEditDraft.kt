package com.noop.ui

import com.noop.analytics.SleepEditGuard
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The two endpoints edited by Android's sleep-time dialog (#515).
 * Enhanced for feature request "sleep log edit time and DAY".
 */
internal data class SleepTimeEditDraft(
    val startTs: Long,
    val endTs: Long
) {
    fun withBedCandidate(
        candidateBedTs: Long,
        nowTs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): SleepTimeEditDraft = copy(
        startTs = SleepEditGuard.autoCorrectedBed(
            previousBedTs = startTs,
            candidateBedTs = candidateBedTs,
            originalWakeTs = endTs,
            nowTs = nowTs,
            zone = zone
        )
    )

    /** Resolve a picked bedtime with an explicit calendar date (year, month, day, hour, minute). */
    fun withBedDateAndTime(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): SleepTimeEditDraft {
        val newBed = LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toEpochSecond()
        val duration = endTs - startTs
        val newWake = if (duration > 0) newBed + duration else newBed + 28800L
        return copy(startTs = newBed, endTs = newWake)
    }

    /** Resolve a picked wake time to the first occurrence strictly after the drafted bedtime. */
    fun withWakeTime(
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): SleepTimeEditDraft {
        val bed = Instant.ofEpochSecond(startTs).atZone(zone)
        var wake = bed.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!wake.isAfter(bed)) wake = wake.plusDays(1)
        return copy(endTs = wake.toEpochSecond())
    }

    fun validatedWindow(
        nowTs: Long,
        slackSec: Long = 300L
    ): Pair<Long, Long>? = SleepEditGuard.clampedEditWindow(startTs, endTs, nowTs, slackSec)
}
