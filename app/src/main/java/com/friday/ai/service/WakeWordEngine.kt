package com.friday.ai.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.util.Log
import com.friday.ai.core.PcmAudio
import com.friday.ai.core.RollingAudio
import com.friday.ai.core.Singing
import com.friday.ai.core.VoiceGate
import com.friday.ai.core.WakeCheck
import com.friday.ai.core.WakePhrases
import com.friday.ai.service.voice.WakeFailure
import com.friday.ai.service.voice.WakeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Spots "Пятница" on the device: one recorder, a restricted Vosk grammar, a
 * voice gate with pre-roll, and checks that a call is neither too short nor
 * sung.
 *
 * Each [capture] owns its recorder and releases it before returning, so the
 * caller hands the microphone on only once it is free. [interrupt] stops the
 * recorder from any thread, which ends even a read blocked in the platform.
 */
class WakeWordEngine : WakeSource {

    companion object {
        private const val TAG = "WakeWordEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val DEFAULT_ENERGY_THRESHOLD = 150.0
        private const val MIN_BUFFER = 4096

        /**
         * How much audio to keep for speaker verification. Long enough that a
         * wake word plus its run-up always fits; short enough that the buffer
         * is trivial.
         */
        private const val RECENT_AUDIO_SECONDS = 3

        /** A frame this loud relative to the open gate still counts as speech. */
        private const val CLOSE_RATIO = 0.55

        /** Empty reads in a row that mean the recorder has stalled, not paused for a moment. */
        private const val MAX_EMPTY_READS = 20
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    /**
     * The loaded model, shared with the command recorder's word follower.
     * Vosk counts references: a recognizer made from it keeps it alive even
     * after [close].
     */
    val vosk: Model? get() = model

    /** The recorder of the running capture, for [interrupt]; null between captures. */
    @Volatile private var active: AudioRecord? = null
    @Volatile private var interrupted = false

    /**
     * Samples handed to [recognizer] since it was made. Vosk times its words
     * against this count — across resets — so it is what places a word in
     * the audio.
     */
    private var fedSamples = 0L

    /** The last name checked for singing, and the answer: the decoder repeats it frame after frame. */
    private var sungCheck: Pair<WakeCheck.Word, Boolean>? = null

    var energyThreshold: Double = DEFAULT_ENERGY_THRESHOLD

    fun loadModel(modelPath: String): Boolean {
        return try {
            model = Model(modelPath)
            // Grammar-constrained recognition: instead of transcribing free
            // speech (which requires guessing among the model's entire
            // vocabulary), the decoder only has to pick among these phrases
            // plus "[unk]". This is dramatically more accurate and lower
            // latency for wake-word spotting than substring-matching a
            // free-form transcript.
            val grammar = JSONArray(WakePhrases.grammarVocabulary() + "[unk]").toString()
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammar).apply {
                // Start, end and confidence of every word: how a call is told from a song.
                setWords(true)
                setPartialWords(true)
            }
            fedSamples = 0L
            Log.i(TAG, "Vosk model loaded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model: ${e.message}")
            false
        }
    }

    override suspend fun capture(onFrame: () -> Unit): WakeSource.End = withContext(Dispatchers.IO) {
        interrupted = false
        ensureActive()
        if (model == null || recognizer == null) return@withContext WakeSource.End.Failed(WakeFailure.MODEL)
        // An error code (negative) here means the format is unsupported: no recorder will open.
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return@withContext WakeSource.End.Failed(WakeFailure.RECORDER)
        // VOICE_RECOGNITION gets the device's echo cancellation, which
        // matters when this listens while Friday is speaking (barge-in) —
        // otherwise it hears the assistant's own voice through the speaker.
        val record = openVoiceRecord(SAMPLE_RATE, minBuffer.coerceAtLeast(MIN_BUFFER), TAG)
            ?: return@withContext WakeSource.End.Failed(WakeFailure.RECORDER)
        // The phone's own DSP. Road noise is exactly the stationary kind
        // hardware suppression removes, and none of it runs unless asked.
        val effects = AudioEffects.attach(record.audioSessionId)
        active = record
        try {
            if (interrupted) return@withContext WakeSource.End.Stopped
            record.startRecording()
            listen(record, minBuffer.coerceAtLeast(MIN_BUFFER) / 2, onFrame)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Recorder failed: ${e.message}")
            if (interrupted) WakeSource.End.Stopped else WakeSource.End.Failed(WakeFailure.RECORDER)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Listening error: ${e.javaClass.simpleName}")
            if (interrupted) WakeSource.End.Stopped else WakeSource.End.Failed(WakeFailure.UNEXPECTED)
        } finally {
            // Release the microphone before anyone else is told they may use
            // it — otherwise the command recorder races this teardown and can
            // fail to open the mic at all. The result above is returned only
            // after this has run.
            active = null
            effects.release()
            record.stopAndRelease(TAG)
        }
    }

    /** The read loop of one capture; returns how it ended. */
    private suspend fun listen(record: AudioRecord, frameSize: Int, onFrame: () -> Unit): WakeSource.End {
        val session = Session(frameSize)
        var empty = 0
        Log.i(TAG, "Silent wake word listening started")
        while (currentCoroutineContext().isActive && !interrupted) {
            val read = record.read(session.buffer, 0, frameSize)
            if (interrupted) break
            // An error code, or nothing over and over: the recorder is gone, not quiet.
            if (read == 0) empty++ else empty = 0
            val broken = when {
                read < 0 -> WakeFailure.READ
                empty >= MAX_EMPTY_READS -> WakeFailure.STALLED
                else -> null
            }
            if (broken != null) return WakeSource.End.Failed(broken)
            if (read == 0) continue
            onFrame()
            hear(session, read)?.let { return it }
        }
        return WakeSource.End.Stopped
    }

    /** One capture's buffers and gate. */
    private inner class Session(frameSize: Int) {
        val buffer = ShortArray(frameSize)
        // Everything heard in the last few seconds. On detection this is
        // handed over for speaker verification: the wake word is always
        // somewhere inside it, including the part that preceded the gate
        // opening.
        val recent = RollingAudio(SAMPLE_RATE * RECENT_AUDIO_SECONDS)
        // Only what the decoder heard, so a word's timing points into it.
        val fed = RollingAudio(SAMPLE_RATE * RECENT_AUDIO_SECONDS)
        val gate = VoiceGate(energyThreshold, (frameSize * 1000 / SAMPLE_RATE).coerceAtLeast(1))
        // The last few frames before the gate opens. Without these the
        // decoder joins the word already in progress and "пятница" arrives
        // as "ница", which matches nothing.
        val preRoll = ArrayDeque<ShortArray>(gate.preRollFrames)
    }

    /** Gates and decodes one frame; the detection, if this frame completed the call. */
    private fun hear(s: Session, read: Int): WakeSource.End.Detected? {
        val frame = s.buffer.copyOf(read)
        s.recent.append(s.buffer, read)
        val decision = s.gate.onFrame(PcmAudio.rms(s.buffer, read))
        if (decision.resetDecoder) {
            // A real gap between utterances. Clearing here — and only
            // here — keeps the partial transcript from accumulating
            // fragments of every earlier attempt.
            recognizer?.reset()
        }
        if (!decision.feed) {
            s.preRoll.addLast(frame)
            while (s.preRoll.size > s.gate.preRollFrames) s.preRoll.removeFirst()
            return null
        }
        if (decision.flushPreRoll) {
            s.preRoll.forEach { feed(it, s.fed) }
            s.preRoll.clear()
        }
        val json = feed(frame, s.fed)?.takeIf { containsWakePhrase(it) && believable(it, s.fed.snapshot()) }
            ?: return null
        recognizer?.reset()
        // Still loud this very frame: the command is following the name.
        val continuing = PcmAudio.rms(s.buffer, read) > s.gate.effectiveThreshold * CLOSE_RATIO
        Log.i(TAG, "Wake word detected (continuing=$continuing); releasing the microphone")
        return WakeSource.End.Detected(VoskResults.text(json), s.recent.snapshot(), continuing)
    }

    /** Ends the running capture from any thread: stopping the recorder makes a blocked read return. */
    override fun interrupt() {
        interrupted = true
        try {
            active?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Stop on interrupt failed: ${e.message}")
        }
    }

    /** Frees the decoder; the listener calls this only after the last capture has returned. */
    override fun close() {
        try {
            recognizer?.close()
            model?.close()
        } catch (_: Exception) {}
        recognizer = null
        model = null
    }

    /**
     * Pure check against a Vosk result — no side effects, so the caller stays
     * in control of teardown ordering (see the microphone handover above).
     */
    internal fun containsWakePhrase(jsonResult: String): Boolean {
        val text = VoskResults.text(jsonResult)
        return if (text.isBlank()) false
        else WakePhrases.isWakeCall(text)
            .also { if (it) Log.i(TAG, "Wake call matched in: $text") }
    }

    /** Hands one frame to the decoder and returns whatever it has so far. */
    private fun feed(frame: ShortArray, fed: RollingAudio): String? {
        val bytes = PcmAudio.toLittleEndian(frame, frame.size)
        fed.append(frame, frame.size)
        fedSamples += frame.size
        return if (recognizer?.acceptWaveForm(bytes, bytes.size) == true) recognizer?.result
        else recognizer?.partialResult
    }

    /**
     * Whether a matched call holds up: the name said in a normal length, with
     * confidence, and not sung. A partial result whose name is still being
     * said is not believed yet; the next frames decide.
     */
    private fun believable(json: String, fed: FloatArray): Boolean {
        val words = VoskResults.words(json)
        val verdict = WakeCheck.judge(words, fedSamples.toDouble() / SAMPLE_RATE, VoskResults.isFinal(json))
        if (verdict == WakeCheck.Verdict.REJECT) Log.i(TAG, "Wake call not believed: $words")
        val name = WakePhrases.nameIndexIn(words.map { it.text })?.let { words[it] }
        val sung = verdict == WakeCheck.Verdict.ACCEPT && name != null && sung(name, fed)
        if (sung) Log.i(TAG, "Wake call was sung, ignored: ${name?.text}")
        return when {
            // No timing (an older decoder): judged as before.
            words.isEmpty() -> true
            else -> verdict == WakeCheck.Verdict.ACCEPT && !sung
        }
    }

    private fun sung(name: WakeCheck.Word, fed: FloatArray): Boolean =
        sungCheck?.takeIf { it.first == name }?.second
            ?: Singing.isSung(VoskResults.wordAudio(fed, fedSamples, name)).also { sungCheck = name to it }
}

/** Reading Vosk's answers: the words with their timing, and whether the answer is final. */
internal object VoskResults {

    private const val SAMPLE_RATE = 16_000

    /** Judged when a word's timing doesn't fit the audio: the last second. */
    private const val FALLBACK_SAMPLES = SAMPLE_RATE

    /**
     * The stretch of [fed] that [word] was said in. [fed] ends at sample
     * [fedSamples] of everything the decoder has had, which is what Vosk's
     * times count from.
     */
    fun wordAudio(fed: FloatArray, fedSamples: Long, word: WakeCheck.Word): FloatArray {
        val fromEnd = (fedSamples - word.start * SAMPLE_RATE).toInt()
        val untilEnd = (fedSamples - word.end * SAMPLE_RATE).toInt().coerceAtLeast(0)
        return if (fromEnd in (untilEnd + 1)..fed.size) {
            fed.copyOfRange(fed.size - fromEnd, fed.size - untilEnd)
        } else {
            fed.copyOfRange(maxOf(0, fed.size - FALLBACK_SAMPLES), fed.size)
        }
    }

    /** Words with their timing, from a final ("result") or partial ("partial_result") answer. */
    fun words(jsonResult: String): List<WakeCheck.Word> = try {
        val obj = JSONObject(jsonResult)
        val list = obj.optJSONArray("result") ?: obj.optJSONArray("partial_result")
        if (list == null) {
            emptyList()
        } else {
            (0 until list.length()).map { i ->
                val w = list.getJSONObject(i)
                WakeCheck.Word(
                    w.optString("word"), w.optDouble("start"), w.optDouble("end"),
                    if (w.has("conf")) w.optDouble("conf") else null
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** The words behind a Vosk result, whether final or partial. */
    fun text(jsonResult: String): String = try {
        val obj = JSONObject(jsonResult)
        (obj.optString("text", "") + " " + obj.optString("partial", "")).trim()
    } catch (_: Exception) {
        ""
    }

    fun isFinal(jsonResult: String): Boolean = try {
        !JSONObject(jsonResult).has("partial")
    } catch (_: Exception) {
        false
    }
}
