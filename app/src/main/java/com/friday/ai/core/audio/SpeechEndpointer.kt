package com.friday.ai.core.audio

import com.friday.ai.core.PcmAudio

/**
 * Decides when the owner has started and finished speaking, from the
 * microphone samples alone.
 *
 * What it replaces cut long commands short: every 128 ms read was compared
 * with one fixed loudness threshold, and 800 ms "below it" ended the turn.
 * In a long sentence the voice drops — between words, at the end of words,
 * as the phone's automatic gain settles — so the turn ended mid-thought and
 * "Transcribing…" appeared while the owner was still talking. Short words
 * ("да", "стоп") had the opposite problem: they had to fill 400 ms of
 * 128 ms chunks to count at all.
 *
 * So, here:
 *  - **Time is counted in samples**, in 20 ms frames, whatever size the
 *    reads come in.
 *  - **The threshold follows the room.** Speech is what stands clear of the
 *    quietest stretch of the last few seconds; the calibrated threshold is
 *    the ceiling, so it is never stricter than before — only quiet rooms
 *    and quiet voices get the benefit.
 *  - **Hysteresis.** Once speaking, a frame needs only part of the opening
 *    level to count, so soft word endings don't look like silence.
 *  - **Longer speech, longer patience.** A quick command ends after 0.8 s of
 *    quiet; after a few seconds of talking, a pause to think gets 1.4 s.
 *  - **The owner's own level.** Sound far quieter than the owner's voice — a
 *    video, a television, people talking across the room — does not keep
 *    the turn open. On the phone, a command was followed by ten seconds of
 *    someone else's words before this.
 *  - **Never below the room.** The calibration is a ceiling, and a
 *    calibration made in a quiet room is low: in a louder one (a fan, the
 *    street, the gain control lifting the noise in every pause) the room
 *    itself passed for speech and every turn ran to thirty seconds. Once the
 *    room has been heard, speech must stand clear of it, and only sound
 *    clearly above it keeps a turn open.
 *
 * The words themselves end a turn sooner (see SpokenEnd); this is the
 * acoustic side and the fallback.
 */
class SpeechEndpointer(
    /** The calibrated threshold: speech never has to be louder than this. */
    private val ceiling: Double,
    /** Speech already heard before recording began (a command said in one breath with the name). */
    leadMs: Long = 0,
    private val maxMs: Long = MAX_MS
) {

    enum class End { FINISHED, NO_SPEECH, MAX_DURATION, STOPPED }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME = 320 // 20 ms
        private const val FRAME_MS = 20L

        /** Nobody starts talking within this: the turn is over. */
        const val START_TIMEOUT_MS = 4_000L

        /** One turn is never longer: a long message, not a stuck microphone. */
        const val MAX_MS = 30_000L

        /** Speech shorter than this is a click or a breath, not "да". */
        const val MIN_SPEECH_MS = 200L

        /** Quiet that ends a short command… */
        const val END_SILENCE_MS = 800L

        /** …and a longer one, once the owner has been talking this long. */
        const val LONG_END_SILENCE_MS = 1_400L
        const val LONG_SPEECH_MS = 2_500L

        /** Consecutive voiced frames that start speech: a tap is shorter. */
        private const val START_FRAMES = 3

        /** Speech stands this far above the room's quietest moments. */
        private const val NOISE_MARGIN = 2.5

        /** Below this nothing is speech, however quiet the room. */
        const val MIN_THRESHOLD = 120.0

        /** Once speaking, this share of the opening level still counts as voice. */
        private const val HOLD_RATIO = 0.6

        /**
         * Sound below this share of the owner's level (−23 dB) is someone
         * else, not them. Replaying the owner's own voice notes, −16 dB cut
         * five of 45 short in their quieter stretches; −23 dB kept 99%.
         */
        private const val OWN_VOICE_RATIO = 0.07

        /** How quickly the owner's level follows their voice. */
        private const val LEVEL_SMOOTHING = 0.1

        /** The room's floor is the quietest 200 ms of the last 3 s. */
        private const val FLOOR_WINDOW = 10
        private const val FLOOR_HISTORY = 150

        /** Speech opens only this far above the room, whatever the calibration says. */
        private const val OPEN_OVER_ROOM = 1.8

        /** Once speaking, sound must stay this far above the room to count as voice. */
        private const val HOLD_OVER_ROOM = 1.4
        private const val VOICE_HOLD_CAP = 0.5

        /**
         * Speech that seemed to start before the room was known is checked
         * after this much audio: within a second a voice has pauses that show
         * the room beneath it, and steady noise has none.
         */
        private const val RECHECK_FRAMES = 50
    }

    var speechStarted = leadMs > 0
        private set

    /** Voiced time so far, including the lead. */
    var speechMs = leadMs
        private set

    /** Enough voice to be worth transcribing; stopped by hand, whatever was said is. */
    val usable: Boolean get() = speechStarted && (speechMs >= MIN_SPEECH_MS || end == End.STOPPED)

    var end: End? = null
        private set

    /** Audio heard so far, counted in whole frames. */
    val elapsedMs: Long get() = totalMs

    private var totalMs = 0L
    private var silenceMs = 0L
    private var voicedRun = 0
    private var runLevel = 0.0

    /** How loud the owner speaks in this turn; 0 until they start. */
    private var voiceLevel = 0.0

    /** Speech began before the room had been heard; checked again once it has. */
    private var provisional = false
    private val room = Room()
    private val leftover = ShortArray(FRAME)
    private var leftoverCount = 0

    /** Feeds [count] samples; returns how the turn ended, once it has. */
    fun push(samples: ShortArray, count: Int = samples.size): End? {
        var i = 0
        while (i < count && end == null) {
            val take = minOf(FRAME - leftoverCount, count - i)
            samples.copyInto(leftover, leftoverCount, i, i + take)
            leftoverCount += take
            i += take
            if (leftoverCount == FRAME) {
                frame(PcmAudio.rms(leftover, FRAME))
                leftoverCount = 0
            }
        }
        return end
    }

    /** The owner pressed stop: what was said so far is the turn. */
    fun stop(): End = end ?: End.STOPPED.also { end = it }

    /** True when [endByWords] ended the turn rather than the sound. */
    var endedByWords = false
        private set

    /** The words say the owner is done ([SpokenEnd]): finished, if they really spoke. */
    fun endByWords(): End? {
        if (end == null && speechStarted) {
            end = End.FINISHED
            endedByWords = true
        }
        return end
    }

    /** The level a frame must reach to open speech, given the room so far. */
    fun openThreshold(): Double = openOver(room.floor())

    private fun openOver(room: Double?): Double =
        room?.let { maxOf(calibrated(it), it * OPEN_OVER_ROOM) } ?: ceiling

    /** The calibration's view alone: never above the ceiling. Holding speech is measured against this. */
    private fun calibrated(room: Double?): Double =
        room?.let { minOf(ceiling, maxOf(MIN_THRESHOLD, it * NOISE_MARGIN)) } ?: ceiling

    /** The last 3 s of frame levels, and the quietest 200 ms in them. */
    private class Room {
        private val levels = ArrayDeque<Double>()
        val heard: Int get() = levels.size

        fun add(level: Double) {
            levels.addLast(level)
            if (levels.size > FLOOR_HISTORY) levels.removeFirst()
        }

        /** Null until 200 ms have been heard. */
        fun floor(): Double? {
            if (levels.size < FLOOR_WINDOW) return null
            var floor = Double.MAX_VALUE
            var sum = levels.take(FLOOR_WINDOW).sum()
            for (start in 0..levels.size - FLOOR_WINDOW) {
                if (start > 0) sum += levels[start + FLOOR_WINDOW - 1] - levels[start - 1]
                floor = minOf(floor, sum / FLOOR_WINDOW)
            }
            return floor
        }
    }

    private fun frame(level: Double) {
        val floor = room.floor()
        val open = openOver(floor)
        room.add(level)
        totalMs += FRAME_MS
        if (provisional && floor != null && room.heard >= RECHECK_FRAMES) recheck(floor)
        if (speechStarted) speaking(level, open, floor) else waiting(level, level > open)
        // Longer speech, longer patience: a pause to think is not the end.
        val endSilence = if (speechMs >= LONG_SPEECH_MS) LONG_END_SILENCE_MS else END_SILENCE_MS
        end = end ?: when {
            totalMs >= maxMs -> End.MAX_DURATION
            !speechStarted && totalMs >= START_TIMEOUT_MS -> End.NO_SPEECH
            // Not while it is still unclear whether this was the owner or the room.
            speechStarted && !provisional && silenceMs >= endSilence -> End.FINISHED
            else -> null
        }
    }

    private fun waiting(level: Double, voiced: Boolean) {
        voicedRun = if (voiced) voicedRun + 1 else 0
        runLevel = if (voiced) runLevel + level else 0.0
        if (voicedRun >= START_FRAMES) {
            speechStarted = true
            speechMs += voicedRun * FRAME_MS
            silenceMs = 0
            voiceLevel = runLevel / voicedRun
            provisional = room.heard <= FLOOR_WINDOW
        }
    }

    /**
     * Speech that "began" in the first 200 ms, judged only against the
     * calibration: if, a second in, it is no louder than the room turned out
     * to be, it was the room, and the turn is still waiting for the owner.
     */
    private fun recheck(room: Double) {
        provisional = false
        if (voiceLevel >= room * OPEN_OVER_ROOM) return
        speechStarted = false
        speechMs = 0
        silenceMs = 0
        voiceLevel = 0.0
        voicedRun = 0
        runLevel = 0.0
    }

    private fun speaking(level: Double, open: Double, room: Double?) {
        // Only sound near the owner's own level teaches it; background must not drag it down.
        if (level > open && level > voiceLevel / 2) {
            voiceLevel = if (voiceLevel == 0.0) level else voiceLevel + (level - voiceLevel) * LEVEL_SMOOTHING
        }
        // Capped by the owner's own level: during a long stretch without a pause the
        // "room" is the voice itself, and the voice must never count as silence.
        val roomHold = minOf((room ?: 0.0) * HOLD_OVER_ROOM, voiceLevel * VOICE_HOLD_CAP)
        val voiced = level > maxOf(calibrated(room) * HOLD_RATIO, voiceLevel * OWN_VOICE_RATIO, roomHold)
        if (voiced) {
            speechMs += FRAME_MS
            silenceMs = 0
        } else {
            silenceMs += FRAME_MS
        }
    }

}
