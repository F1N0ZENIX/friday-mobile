package com.friday.ai.service

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The time Edge's token is computed for. The token is tied to a 5-minute
 * window, and a phone clock a few minutes off falls outside it: Microsoft
 * then answers 403 with its own time in the Date header, which corrects the
 * next attempt.
 */
object EdgeClock {

    private const val WORTH_RETRY_MS = 60_000L

    /** Server time minus ours. */
    @Volatile var skewMillis = 0L

    /** Our clock, corrected by [skewMillis]. */
    fun now(): Long = System.currentTimeMillis() + skewMillis

    /**
     * Learns the skew from a 403's Date header. True when the clock was off
     * by more than a minute — worth one more try; false when the clock was
     * not the problem.
     */
    fun learn(serverDate: String?, localMillis: Long): Boolean {
        val server = serverDate
            ?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull() }
            ?.toInstant()?.toEpochMilli() ?: return false
        val skew = server - localMillis
        if (kotlin.math.abs(skew) < WORTH_RETRY_MS) return false
        skewMillis += skew
        return true
    }
}
