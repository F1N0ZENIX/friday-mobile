package com.friday.ai.service.voice

import android.media.AudioManager
import com.friday.ai.service.voice.WakeHealth.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakeListenerTest {

    /** How the next capture behaves. */
    private sealed interface Plan {
        /** Frames every 100 ms until ended from outside. */
        data object Listen : Plan
        /** Ends at once with this failure. */
        data class Fail(val reason: WakeFailure) : Plan
        /** No frames at all: a read stuck in the platform. */
        data object Stuck : Plan
        /** Ignores interrupt entirely: a capture that never returns. */
        data object Stubborn : Plan
        /** Runs [meanwhile] — another thread's transition — and then returns [end] in the same breath. */
        data class Race(val meanwhile: () -> Unit, val end: WakeSource.End) : Plan
    }

    /** A recorder plus decoder, as the listener sees it, with a log of what happened in which order. */
    private class FakeSource : WakeSource {
        val events = mutableListOf<String>()
        val plans = ArrayDeque<Plan>()
        var active = 0
        var maxActive = 0
        var captures = 0
        /** What an interrupt makes the capture return: normally Stopped, or a detection racing it. */
        var onInterrupt: WakeSource.End = WakeSource.End.Stopped
        private var ending: CompletableDeferred<WakeSource.End>? = null

        fun end(with: WakeSource.End) {
            ending?.complete(with)
        }

        /** Lets a [Plan.Stubborn] capture go at the end of its test. */
        val stubbornRelease = CompletableDeferred<WakeSource.End>()

        override suspend fun capture(onFrame: () -> Unit): WakeSource.End {
            captures++
            active++
            maxActive = maxOf(maxActive, active)
            events += "open"
            val done = CompletableDeferred<WakeSource.End>().also { ending = it }
            try {
                return when (val plan = plans.removeFirstOrNull() ?: Plan.Listen) {
                    is Plan.Fail -> WakeSource.End.Failed(plan.reason)
                    Plan.Stuck -> done.await()
                    // Like a native read that never comes back: not even cancellation ends it.
                    Plan.Stubborn -> {
                        ending = null
                        withContext(NonCancellable) { stubbornRelease.await() }
                    }
                    Plan.Listen -> frames(onFrame, done)
                    is Plan.Race -> {
                        plan.meanwhile()
                        plan.end
                    }
                }
            } catch (e: CancellationException) {
                // A real read is not ended by cancellation but by interrupt: what interrupt
                // decided — a detection racing the stop, say — is still what comes back.
                if (done.isCompleted) return done.getCompleted()
                throw e
            } finally {
                active--
                events += "release"
            }
        }

        private suspend fun frames(onFrame: () -> Unit, done: CompletableDeferred<WakeSource.End>): WakeSource.End {
            while (true) {
                onFrame()
                withTimeoutOrNull(FRAME_MS) { done.await() }?.let { return it }
            }
        }

        override fun interrupt() {
            events += "interrupt"
            ending?.complete(onInterrupt)
        }

        override fun close() {
            events += "close"
        }
    }

    private class Rig(test: TestScope) {
        val source = FakeSource()
        val shown = mutableListOf<String>()
        val wakes = mutableListOf<String>()
        var mic = true
        var owner = true
        var loaded: WakeListener.Loaded = WakeListener.Loaded.Ready(source, "/model")
        val listener = WakeListener(
            scope = test.backgroundScope,
            admits = { owner },
            show = { shown += it },
            load = { loaded },
            micAllowed = { mic },
            io = StandardTestDispatcher(test.testScheduler),
            clock = { test.testScheduler.currentTime }
        ).apply {
            onWake = { heard, _, _ ->
                source.events += "wake"
                wakes += heard
            }
        }
        val status get() = listener.health.value.status
    }

    private companion object {
        const val FRAME_MS = 100L
    }

    private fun detected() = WakeSource.End.Detected("пятница", FloatArray(4), continuing = false)

    private suspend fun TestScope.ready(): Rig {
        val rig = Rig(this)
        assertTrue(rig.listener.initialise(null))
        rig.listener.listen()
        runCurrent()
        return rig
    }

    @Test
    fun `listening is reported only once frames arrive`() = runTest {
        val rig = Rig(this)
        rig.source.plans += Plan.Stuck
        rig.listener.initialise(null)
        rig.listener.listen()
        runCurrent()
        assertEquals("a job and a recorder are not yet hearing", Status.STARTING, rig.status)
        rig.source.end(WakeSource.End.Stopped)
        runCurrent()
        assertTrue(rig.listener.health.value.status != Status.LISTENING)

        val live = ready()
        assertEquals(Status.LISTENING, live.status)
        assertTrue(live.listener.lastFrameAt >= 0)
    }

    @Test
    fun `a detection releases the wake recorder before the command is handed over`() = runTest {
        val rig = ready()
        rig.source.end(detected())
        runCurrent()
        assertEquals(listOf("пятница"), rig.wakes)
        val events = rig.source.events
        assertTrue(events.toString(), events.indexOf("release") < events.indexOf("wake"))
        assertEquals(0, rig.source.active)
        assertEquals(Status.PAUSED_FOR_COMMAND, rig.status)
    }

    @Test
    fun `stop unblocks the read, waits for it, and a pause is not a failure`() = runTest {
        val rig = ready()
        rig.listener.stop()
        assertEquals("the microphone is free when stop returns", 0, rig.source.active)
        assertEquals(listOf("open", "interrupt", "release"), rig.source.events)
        assertEquals(Status.PAUSED_FOR_COMMAND, rig.status)
        advanceTimeBy(60_000)
        assertEquals("no recovery after an intentional pause", 1, rig.source.captures)
    }

    @Test
    fun `two listens close together open one recorder`() = runTest {
        val rig = ready()
        rig.listener.listen()
        rig.listener.listen()
        runCurrent()
        assertEquals(1, rig.source.captures)
        assertEquals(1, rig.source.maxActive)
    }

    @Test
    fun `a detection that lands after stop or destroy is ignored`() = runTest {
        val rig = ready()
        rig.source.onInterrupt = detected()
        rig.listener.stop()
        runCurrent()
        assertTrue("late detection must not start a turn", rig.wakes.isEmpty())

        // The read returns a detection just as the service is destroyed on another thread:
        // nothing has been cancelled yet, and only the generation check stops the hand-over.
        val other = Rig(this)
        other.source.plans += Plan.Race({ other.listener.destroy() }, detected())
        other.listener.initialise(null)
        other.listener.listen()
        runCurrent()
        assertTrue(other.wakes.isEmpty())
    }

    @Test
    fun `a late resume after a newer turn or a call does not reopen the microphone`() = runTest {
        val rig = ready()
        val token = rig.listener.token
        rig.listener.stop() // a new turn took the microphone
        rig.listener.resume(token)
        runCurrent()
        assertEquals(1, rig.source.captures)

        rig.listener.resume() // the newer turn's own resume
        runCurrent()
        assertEquals(2, rig.source.captures)

        val stale = rig.listener.token
        rig.listener.pauseForCall()
        rig.listener.resume(stale)
        rig.listener.listen()
        runCurrent()
        assertEquals("nothing listens during a call", 2, rig.source.captures)
        assertEquals(Status.PAUSED_FOR_CALL, rig.status)
    }

    @Test
    fun `failures retry after 1, 2, 4, 8 and 16 seconds, then fail visibly`() = runTest {
        val rig = Rig(this)
        repeat(6) { rig.source.plans += Plan.Fail(WakeFailure.READ) }
        rig.listener.initialise(null)
        rig.listener.listen()
        runCurrent()
        assertEquals(Status.RECOVERING, rig.status)
        assertEquals(WakeFailure.READ, rig.listener.health.value.reason)
        val waits = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)
        waits.forEachIndexed { i, wait ->
            assertEquals(i + 1, rig.source.captures)
            assertEquals(wait, rig.listener.health.value.retryInMs)
            advanceTimeBy(wait)
            runCurrent()
        }
        assertEquals(6, rig.source.captures)
        assertEquals(Status.FAILED, rig.status)
        assertTrue(rig.shown.last(), rig.shown.last().contains("откройте Friday"))
        advanceTimeBy(120_000)
        assertEquals("no endless retry", 6, rig.source.captures)
    }

    @Test
    fun `a stalled read, a refused recorder and a thrown error all recover`() = runTest {
        val rig = Rig(this)
        rig.source.plans += Plan.Stuck
        rig.source.plans += Plan.Fail(WakeFailure.RECORDER)
        rig.source.plans += Plan.Fail(WakeFailure.UNEXPECTED)
        rig.listener.initialise(null)
        rig.listener.listen()
        advanceTimeBy(WakeListener.STALL_MS + 500)
        runCurrent()
        assertEquals(WakeFailure.STALLED, rig.listener.health.value.reason)
        assertTrue("the watchdog interrupted it", "interrupt" in rig.source.events)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(WakeFailure.RECORDER, rig.listener.health.value.reason)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(WakeFailure.UNEXPECTED, rig.listener.health.value.reason)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals("back to listening after the failures", Status.LISTENING, rig.status)
    }

    @Test
    fun `sustained healthy capture starts the retry count over`() = runTest {
        val rig = ready()
        advanceTimeBy(WakeListener.HEALTHY_MS + 1_000)
        rig.source.end(WakeSource.End.Failed(WakeFailure.READ))
        runCurrent()
        assertEquals(1, rig.listener.health.value.attempt)
        assertEquals(1_000L, rig.listener.health.value.retryInMs)
    }

    @Test
    fun `a revoked microphone permission stops retrying and says what to do`() = runTest {
        val rig = ready()
        rig.mic = false
        rig.source.end(WakeSource.End.Failed(WakeFailure.RECORDER))
        runCurrent()
        assertEquals(Status.FAILED, rig.status)
        assertEquals(WakeFailure.PERMISSION, rig.listener.health.value.reason)
        advanceTimeBy(60_000)
        assertEquals(1, rig.source.captures)
        assertTrue(rig.shown.last().contains("Нет доступа к микрофону"))
    }

    @Test
    fun `permission and model problems are reported before listening`() = runTest {
        val noMic = Rig(this).apply { mic = false }
        assertFalse(noMic.listener.initialise(null))
        assertEquals(Status.UNAVAILABLE, noMic.status)
        assertEquals(WakeFailure.PERMISSION, noMic.listener.health.value.reason)

        val noModel = Rig(this).apply { loaded = WakeListener.Loaded.Unavailable(WakeFailure.MODEL) }
        assertFalse(noModel.listener.initialise(null))
        assertTrue(noModel.shown.last().contains("модель"))
        noModel.listener.listen()
        runCurrent()
        assertEquals(0, noModel.source.captures)
    }

    @Test
    fun `destroy stops the capture before closing the decoder`() = runTest {
        val rig = ready()
        rig.listener.destroy()
        runCurrent()
        val events = rig.source.events
        assertTrue(events.toString(), events.indexOf("release") < events.indexOf("close"))
        assertEquals(Status.STOPPED, rig.status)
        rig.listener.listen()
        runCurrent()
        assertEquals("nothing starts after destroy", 1, rig.source.captures)
    }

    @Test
    fun `a capture that never returns is never closed under`() = runTest {
        val rig = Rig(this)
        rig.source.plans += Plan.Stubborn
        rig.listener.initialise(null)
        rig.listener.listen()
        runCurrent()
        rig.listener.destroy()
        advanceTimeBy(WakeListener.TEARDOWN_MS + 1_000)
        runCurrent()
        assertFalse("close" in rig.source.events)
        assertEquals(Status.FAILED, rig.status)
        assertEquals(WakeFailure.TEARDOWN, rig.listener.health.value.reason)
        rig.source.stubbornRelease.complete(WakeSource.End.Stopped)
    }

    @Test
    fun `ringing, phone and voip calls pause wake, which resumes after normal mode holds`() = runTest {
        val rig = ready()
        val modes = ArrayDeque(
            listOf(
                AudioManager.MODE_NORMAL, AudioManager.MODE_RINGTONE, AudioManager.MODE_NORMAL,
                AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_CALL, AudioManager.MODE_NORMAL,
                AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_NORMAL, AudioManager.MODE_NORMAL
            )
        )
        val changes = mutableListOf<Pair<Long, Boolean>>()
        val nextMode = { modes.removeFirstOrNull() ?: AudioManager.MODE_NORMAL }
        val watcher = CallWatcher(backgroundScope, nextMode) { inCall ->
            changes += testScheduler.currentTime to inCall
            if (inCall) rig.listener.pauseForCall() else rig.listener.callEnded()
        }
        watcher.start()
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("ringing pauses at once", Status.PAUSED_FOR_CALL, rig.status)
        assertEquals(0, rig.source.active)
        advanceTimeBy(20_000)
        runCurrent()
        // One pause for ring + call + VoIP: a single normal second in between is not an end.
        assertEquals(listOf(true, false), changes.map { it.second })
        assertTrue("resumed only after 2 s of normal", changes.last().first >= 8_000)
        assertEquals(Status.LISTENING, rig.status)
        assertEquals(2, rig.source.captures)
        watcher.stop()
    }

    @Test
    fun `a stranger's wake word is ignored and listening goes on`() = runTest {
        val rig = ready()
        rig.owner = false
        rig.source.end(detected())
        runCurrent()
        assertTrue(rig.wakes.isEmpty())
        assertEquals(2, rig.source.captures)
        assertEquals(Status.LISTENING, rig.status)
    }
}
