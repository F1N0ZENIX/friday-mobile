package com.friday.ai.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.friday.ai.core.PcmAudio
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Measures the room and the owner's voice to set the wake thresholds.
 *
 * Every phase is bounded: the old version waited for speech forever, keeping
 * the microphone open for as long as the owner stayed silent. Now silence ends
 * the run with [Outcome.TimedOut], a dead microphone with
 * [Outcome.AudioUnavailable], and cancelling the caller stops the loop at the
 * next frame. The microphone and its effects are released on every path, and
 * thresholds come only from a complete measurement.
 */
class VoiceCalibrator(
    private val openMicrophone: () -> Microphone? = ::openVoiceRecognition,
    private val timing: Timing = Timing()
) {

    companion object {
        private const val TAG = "VoiceCalibrator"
        internal const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MIN_BUFFER = 4096
        private const val MS_PER_SECOND = 1000L
        private const val SPEECH_OVER_NOISE = 2.5
        private const val WAKE_SHARE = 0.15
        private const val WHISPER_SHARE = 0.12
        private const val MIN_WAKE = 50.0
        private const val MAX_WAKE = 600.0
        private const val MIN_WHISPER = 40.0
        private const val MAX_WHISPER = 450.0

        /**
         * Where the thresholds are kept. "_v2": values measured by older
         * versions came from a different microphone path and are wrong for
         * this one, so they are left unread until the owner calibrates again.
         */
        const val PREF_WAKE = "wake_threshold_v2"
        const val PREF_WHISPER = "whisper_threshold_v2"

        /**
         * Measured exactly the way Friday listens: the same source and the
         * same noise suppression and gain control. Calibrating on MIC (louder,
         * differently processed on Samsung) gave thresholds the real path's
         * speech hardly reached, so Friday seemed deaf to her own owner.
         *
         * Null when the microphone cannot be opened: no permission, or held
         * by a call or another app.
         */
        @Suppress("MissingPermission") // Callers check RECORD_AUDIO; a denial lands in the catch.
        private fun openVoiceRecognition(): Microphone? {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING).coerceAtLeast(MIN_BUFFER)
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
            } catch (e: SecurityException) {
                Log.w(TAG, "Microphone refused: ${e.message}")
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Microphone unavailable: ${e.message}")
                null
            }
            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                return null
            }
            return start(record, bufferSize)
        }

        private fun start(record: AudioRecord, bufferSize: Int): Microphone? {
            val effects = AudioEffects.attach(record.audioSessionId)
            return try {
                record.startRecording()
                RecordMicrophone(record, effects, bufferSize / 2)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Recording did not start: ${e.message}")
                effects.release()
                record.stopAndRelease(TAG)
                null
            }
        }
    }

    /** A source of 16 kHz mono PCM; closing it frees the microphone. */
    interface Microphone : Closeable {
        /** Samples per read. */
        val frameSize: Int

        /** Fills [buffer] with up to [frameSize] samples; 0 or less when the source has failed or stopped. */
        fun read(buffer: ShortArray): Int

        /** Ends a [read] blocked on another thread; [close] still frees the microphone. */
        fun stop()
    }

    private class RecordMicrophone(
        private val record: AudioRecord,
        private val effects: AudioEffects,
        override val frameSize: Int
    ) : Microphone {
        override fun read(buffer: ShortArray): Int = record.read(buffer, 0, frameSize)

        override fun stop() {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord.stop failed: ${e.message}")
            }
        }

        override fun close() {
            effects.release()
            record.stopAndRelease(TAG)
        }
    }

    /** How long each phase lasts; [speechWaitMs] is how long silence is tolerated before giving up. */
    data class Timing(
        val silenceMs: Long = 2_000,
        val speechWaitMs: Long = 10_000,
        val speechMs: Long = 3_000
    )

    data class CalibrationResult(
        val ambientNoise: Double,
        val speechEnergy: Double,
        val wakeWordThreshold: Double,
        val whisperThreshold: Double
    )

    /** How a run ended. Only [Completed] carries thresholds worth keeping. */
    sealed interface Outcome {
        data class Completed(val result: CalibrationResult) : Outcome
        /** Nobody spoke within [Timing.speechWaitMs]. */
        data object TimedOut : Outcome
        /** The microphone could not be opened or stopped delivering audio. */
        data object AudioUnavailable : Outcome
    }

    interface Listener {
        fun onPhase(phase: Phase)
        fun onEnergyUpdate(energy: Double)
    }

    enum class Phase { MEASURING_SILENCE, WAITING_FOR_SPEECH, MEASURING_SPEECH, DONE }

    /** Called from the recording thread; the settings screen only writes state flows. */
    var listener: Listener? = null

    private fun result(ambientNoise: Double, speechEnergy: Double): CalibrationResult {
        val wakeThreshold = ambientNoise + (speechEnergy - ambientNoise) * WAKE_SHARE
        val whisperThreshold = ambientNoise + (speechEnergy - ambientNoise) * WHISPER_SHARE
        return CalibrationResult(
            ambientNoise = ambientNoise,
            speechEnergy = speechEnergy,
            // Capped lower than before: a threshold above ordinary speech is deafness.
            wakeWordThreshold = wakeThreshold.coerceIn(MIN_WAKE, MAX_WAKE),
            whisperThreshold = whisperThreshold.coerceIn(MIN_WHISPER, MAX_WHISPER)
        )
    }

    /**
     * Runs one calibration. Cancelling the caller throws CancellationException
     * as usual, after the microphone is released.
     *
     * Checking for cancellation between frames is not enough on its own: a
     * read can block in the platform (a stalled audio HAL, the microphone
     * taken by a call). A watcher stops the recording the moment the run is
     * cancelled, which makes that read return.
     */
    suspend fun calibrate(): Outcome = withContext(Dispatchers.IO) {
        val mic = openMicrophone() ?: return@withContext Outcome.AudioUnavailable
        mic.use {
            coroutineScope {
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        mic.stop()
                    }
                }
                try {
                    measure(mic)
                } finally {
                    watcher.cancel()
                }
            }
        }
    }

    /** The microphone stopped delivering audio part-way. */
    private class MicrophoneFailed : Exception()

    private suspend fun measure(mic: Microphone): Outcome {
        val buffer = ShortArray(mic.frameSize)
        val frameMs = (mic.frameSize * MS_PER_SECOND / SAMPLE_RATE).coerceAtLeast(1)

        /** Reads one frame's energy; stops at the next frame once the caller is cancelled. */
        suspend fun frame(): Double {
            currentCoroutineContext().ensureActive()
            val read = mic.read(buffer)
            if (read <= 0) {
                // A read ended by the watcher is a cancellation, not a broken microphone.
                currentCoroutineContext().ensureActive()
                throw MicrophoneFailed()
            }
            return PcmAudio.rms(buffer, read).also { listener?.onEnergyUpdate(it) }
        }

        /** Average energy over [durationMs] of audio. */
        suspend fun average(durationMs: Long): Double {
            var sum = 0.0
            var frames = 0
            while (frames * frameMs < durationMs) {
                sum += frame()
                frames++
            }
            return if (frames > 0) sum / frames else 0.0
        }

        /** Whether something louder than [threshold] came within [Timing.speechWaitMs]. */
        suspend fun heard(threshold: Double): Boolean {
            var waited = 0L
            while (waited < timing.speechWaitMs) {
                if (frame() > threshold) return true
                waited += frameMs
            }
            return false
        }

        return try {
            listener?.onPhase(Phase.MEASURING_SILENCE)
            val ambientNoise = average(timing.silenceMs)

            listener?.onPhase(Phase.WAITING_FOR_SPEECH)
            if (!heard(ambientNoise * SPEECH_OVER_NOISE)) {
                Outcome.TimedOut
            } else {
                listener?.onPhase(Phase.MEASURING_SPEECH)
                val speechEnergy = average(timing.speechMs)
                listener?.onPhase(Phase.DONE)
                Outcome.Completed(result(ambientNoise, speechEnergy))
            }
        } catch (_: MicrophoneFailed) {
            Outcome.AudioUnavailable
        }
    }
}
