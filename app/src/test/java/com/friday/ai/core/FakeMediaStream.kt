package com.friday.ai.core

/**
 * STREAM_MUSIC as AudioService behaves: a few index steps, a mute flag kept
 * apart from the index, index 0 reading as muted, and a set above 0 taking
 * the mute off.
 */
class FakeMediaStream(
    var index: Int = 9,
    val max: Int = 15,
    val min: Int = 0,
    var muted: Boolean = false
) {
    var fixed = false
    var duringCall = false
    /** A vendor that accepts the call and changes nothing. */
    var ignoreWrites = false
    /** A policy that refuses the change outright. */
    var refuse = false
    /** What ADJUST_UNMUTE does to a stream sitting at 0: some builds lift it to 1. */
    var unmuteLiftsZero = false
    val calls = mutableListOf<String>()

    fun read() = MediaVolume(index, min, max, muted, fixed, duringCall)

    fun applyIndex(i: Int) {
        calls += "set $i"
        if (gate()) return
        index = i.coerceIn(min, max)
        muted = index == 0
    }

    fun applyStep(raise: Boolean) {
        calls += if (raise) "raise" else "lower"
        if (gate()) return
        index = (index + if (raise) 1 else -1).coerceIn(min, max)
        muted = index == 0
    }

    fun applyMuted(m: Boolean) {
        calls += if (m) "mute" else "unmute"
        if (gate()) return
        muted = m
        if (!m && index == 0 && unmuteLiftsZero) index = 1
    }

    private fun gate(): Boolean {
        if (refuse) throw SecurityException("Not allowed to change the volume")
        return ignoreWrites || fixed
    }
}
