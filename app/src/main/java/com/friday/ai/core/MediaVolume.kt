package com.friday.ai.core

import kotlin.math.roundToInt

/**
 * The media stream (STREAM_MUSIC) as Android reports it: an index between
 * [min] and [max] — 15 or 25 steps on most phones, not 100 — and a mute flag
 * kept apart from the index, so muting does not lose the level.
 *
 * Every volume command acts on this stream only. Call, ringtone, alarm and
 * notification volumes are separate streams and are never claimed changed.
 */
data class MediaVolume(
    val index: Int,
    val min: Int,
    val max: Int,
    val muted: Boolean,
    /** The device ignores volume changes (fixed-volume output, some cars and TVs). */
    val fixed: Boolean = false,
    /** A call or VoIP session is active: its own volume is a different stream. */
    val duringCall: Boolean = false
) {
    /** What Android's slider shows for [index]. */
    val percent: Int get() = percentOf(index)

    val atMax: Boolean get() = index >= max
    val atMin: Boolean get() = index <= min

    fun percentOf(i: Int): Int = if (max <= 0) 0 else (i * PERCENT.toDouble() / max).roundToInt()

    /**
     * The index nearest to [percent] of the full scale. A request above zero
     * never lands on silence, and nothing goes outside [min]..[max].
     */
    fun indexFor(percent: Int): Int {
        val p = percent.coerceIn(0, PERCENT)
        val nearest = (max * p.toDouble() / PERCENT).roundToInt()
        val audible = if (p > 0) maxOf(nearest, min, 1) else nearest
        return audible.coerceIn(min, max)
    }

    private companion object {
        const val PERCENT = 100
    }
}
