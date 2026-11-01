package com.friday.ai.service

import com.friday.ai.service.VoiceCalibrator.Outcome
import com.friday.ai.ui.settings.Calibration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCalibratorTest {

    /** 100 ms frames whose loudness [level] decides by frame number; -1 means the microphone died. */
    private class FakeMic(private val pauseMs: Long = 0, private val level: (Int) -> Int) : VoiceCalibrator.Microphone {
        override val frameSize = 1600
        val frames = AtomicInteger()
        val reading = CompletableDeferred<Unit>()

        @Volatile var closed = false
        private val stopped = CountDownLatch(1)

        override fun read(buffer: ShortArray): Int {
            val amplitude = level(frames.getAndIncrement())
            reading.complete(Unit)
            if (pauseMs > 0) Thread.sleep(pauseMs)
            // Like AudioRecord.read when the HAL stalls: blocked until stop().
            if (amplitude == HANG) stopped.await()
            if (amplitude < 0 || stopped.count == 0L) return -1
            // A square wave: its RMS is the amplitude.
            for (i in 0 until frameSize) buffer[i] = (if (i % 2 == 0) amplitude else -amplitude).toShort()
            return frameSize
        }

        override fun stop() {
            stopped.countDown()
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val HANG = -2
    }

    private val timing = VoiceCalibrator.Timing(silenceMs = 1000, speechWaitMs = 2000, speechMs = 1000)

    private fun calibrator(mic: FakeMic?, timing: VoiceCalibrator.Timing = this.timing) =
        VoiceCalibrator(openMicrophone = { mic }, timing = timing)

    @Test
    fun `speech after silence gives thresholds between noise and voice`() = runBlocking {
        // 10 quiet frames, 3 more of waiting, then speech.
        val mic = FakeMic { if (it < 13) 40 else 2000 }
        val outcome = calibrator(mic).calibrate()

        assertTrue(outcome is Outcome.Completed)
        val result = (outcome as Outcome.Completed).result
        assertEquals(40.0, result.ambientNoise, 0.5)
        assertEquals(2000.0, result.speechEnergy, 0.5)
        assertTrue(result.wakeWordThreshold in 40.0..2000.0)
        assertTrue(mic.closed)
    }

    @Test
    fun `silence ends the run after the deadline and frees the microphone`() = runBlocking {
        val mic = FakeMic { 40 }
        val outcome = withTimeout(5_000) { calibrator(mic).calibrate() }

        assertEquals(Outcome.TimedOut, outcome)
        // 10 frames of room noise, then 20 frames (2 s) of waiting.
        assertEquals(30, mic.frames.get())
        assertTrue(mic.closed)
    }

    @Test
    fun `cancelling stops the loop and frees the microphone`() = runBlocking {
        val mic = FakeMic(pauseMs = 5) { 40 }
        val endless = VoiceCalibrator.Timing(silenceMs = 100, speechWaitMs = Long.MAX_VALUE, speechMs = 100)
        // Outside runBlocking: a run that never ends fails the test instead of hanging the suite.
        val job = CoroutineScope(Dispatchers.IO).launch { calibrator(mic, endless).calibrate() }
        mic.reading.await()

        withTimeout(5_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
        assertTrue(mic.closed)
    }

    @Test
    fun `cancelling ends a read that is stuck in the platform`() = runBlocking {
        val mic = FakeMic { if (it < 3) 40 else HANG }
        // Outside runBlocking: a run that never ends fails the test instead of hanging the suite.
        val job = CoroutineScope(Dispatchers.IO).launch { calibrator(mic).calibrate() }
        withTimeout(5_000) { while (mic.frames.get() < 4) Thread.sleep(5) }

        withTimeout(5_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled)
        assertTrue(mic.closed)
    }

    @Test
    fun `a microphone that cannot open or dies mid-way is reported, not measured`() = runBlocking {
        assertEquals(Outcome.AudioUnavailable, calibrator(null).calibrate())

        val dying = FakeMic { if (it < 3) 40 else -1 }
        assertEquals(Outcome.AudioUnavailable, calibrator(dying).calibrate())
        assertTrue(dying.closed)

        val dyingWhileSpeaking = FakeMic { if (it < 13) 40 else if (it < 15) 2000 else -1 }
        assertEquals(Outcome.AudioUnavailable, calibrator(dyingWhileSpeaking).calibrate())
        assertTrue(dyingWhileSpeaking.closed)
    }

    @Test
    fun `settings save thresholds only from a complete run`() = runBlocking {
        val saved = mutableListOf<VoiceCalibrator.CalibrationResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        suspend fun run(mic: FakeMic): Calibration {
            val calibration = Calibration(scope, { calibrator(mic) }, { saved += it })
            assertTrue(calibration.start())
            withTimeout(5_000) { while (calibration.phase.value != null) Thread.sleep(5) }
            return calibration
        }

        val silent = run(FakeMic { 40 })
        assertEquals(Calibration.TIMED_OUT, silent.message.value)
        assertTrue(saved.isEmpty())

        val spoken = run(FakeMic { if (it < 13) 40 else 2000 })
        assertNull(spoken.message.value)
        assertEquals(1, saved.size)
        assertEquals(saved.single(), spoken.result.value)
        scope.cancelAndJoinChildren()
    }

    @Test
    fun `a cancelled run in settings says so and keeps the old thresholds`() = runBlocking {
        val saved = mutableListOf<VoiceCalibrator.CalibrationResult>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mic = FakeMic(pauseMs = 5) { 40 }
        val endless = VoiceCalibrator.Timing(silenceMs = 100, speechWaitMs = Long.MAX_VALUE, speechMs = 100)
        val calibration = Calibration(scope, { calibrator(mic, endless) }, { saved += it })

        assertTrue(calibration.start())
        // A second tap while running starts nothing.
        assertTrue(!calibration.start())
        mic.reading.await()
        calibration.cancel()
        withTimeout(5_000) { while (calibration.phase.value != null || !mic.closed) Thread.sleep(5) }

        assertEquals(Calibration.CANCELLED, calibration.message.value)
        assertTrue(saved.isEmpty())
        scope.cancelAndJoinChildren()
    }

    @Test
    fun `every outcome but success explains how to retry`() {
        assertNull(Calibration.messageFor(Outcome.Completed(VoiceCalibrator.CalibrationResult(1.0, 2.0, 3.0, 4.0))))
        assertTrue(Calibration.messageFor(Outcome.TimedOut)!!.contains("novamente"))
        assertTrue(Calibration.messageFor(Outcome.AudioUnavailable)!!.contains("tente novamente"))
    }

    private suspend fun CoroutineScope.cancelAndJoinChildren() {
        coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }
}
