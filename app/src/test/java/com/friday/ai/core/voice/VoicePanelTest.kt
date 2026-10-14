@file:Suppress("MaxLineLength") // test scenes and spoken sentences, kept whole

package com.friday.ai.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePanelStoreTest {

    private var now = 1_000L
    private val store = VoicePanelStore { now }

    @Test
    fun `a session runs through its stages and ends`() {
        val s = store.begin(userInitiated = true)
        store.phase(VoicePhase.LISTENING)
        store.phase(VoicePhase.PROCESSING) { it.copy(heard = "какая погода") }
        store.phase(VoicePhase.SPEAKING) { it.copy(answer = "+18°") }
        assertEquals(VoicePhase.SPEAKING, store.state.value!!.phase)
        assertEquals("какая погода", store.state.value!!.heard)
        store.end(s)
        assertNull(store.state.value)
    }

    @Test
    fun `an update after the panel was closed does not bring it back`() {
        store.begin(userInitiated = true)
        store.end()
        store.phase(VoicePhase.SPEAKING) { it.copy(answer = "поздний ответ") }
        assertNull(store.state.value)
    }

    @Test
    fun `ending an older session leaves the newer one alone`() {
        val old = store.begin(userInitiated = true)
        store.end(old)
        val new = store.begin(userInitiated = true)
        store.end(old)
        assertEquals(new, store.state.value!!.session)
        assertTrue(new > old)
    }

    @Test
    fun `a second call during a session continues it, and an announcement becomes the owner's when they call`() {
        val a = store.begin(userInitiated = false)
        assertFalse(store.state.value!!.userInitiated)
        assertEquals(a, store.begin(userInitiated = true))
        assertTrue(store.state.value!!.userInitiated)
    }

    @Test
    fun `listening again is a new turn - last words cleared, input time renewed`() {
        store.begin(userInitiated = true)
        store.phase(VoicePhase.PROCESSING) { it.copy(heard = "прошлое") }
        now = 5_000
        store.phase(VoicePhase.LISTENING)
        assertNull(store.state.value!!.heard)
        assertEquals(5_000, store.state.value!!.lastInputAt)
        now = 6_000
        store.touched()
        assertEquals(6_000, store.state.value!!.lastInputAt)
    }

    @Test
    fun `the level is kept apart and reset on end`() {
        store.begin(userInitiated = true)
        store.level(2f)
        assertEquals(1f, store.level.value)
        store.end()
        assertEquals(0f, store.level.value)
    }
}

class VoicePresentationTest {

    private val secret = VoicePanel(
        session = 1, phase = VoicePhase.SPEAKING,
        heard = "Напиши Фердавсу Гетисбургу, что я свободен",
        answer = "Отправить SMS для «Firdavs Gettysburg»: «я свободен»? Да или нет.",
        source = "команда"
    )

    @Test
    fun `nothing said or answered reaches the lock screen by default`() {
        VoicePhase.entries.forEach { phase ->
            val view = VoicePresentation.locked(secret.copy(phase = phase), unlocked = false)
            assertTrue("$phase", view.body == null || view.body == VoicePresentation.HIDDEN)
            listOf("Фердавс", "Gettysburg", "свободен", "SMS").forEach { word ->
                assertFalse("$phase leaks $word", view.status.contains(word))
                assertFalse("$phase leaks $word", view.body.orEmpty().contains(word))
            }
        }
    }

    @Test
    fun `withheld content can be revealed only through unlocking`() {
        val locked = VoicePresentation.locked(secret, unlocked = false)
        assertTrue(locked.canReveal)
        assertEquals("Отвечаю", locked.status)
        val unlocked = VoicePresentation.locked(secret, unlocked = true)
        assertEquals(secret.answer, unlocked.body)
        assertFalse(VoicePresentation.locked(secret.copy(phase = VoicePhase.LISTENING, answer = null), false).canReveal)
    }

    @Test
    fun `the unlocked panel says the stage in words, never blank`() {
        val listening = VoicePresentation.open(secret.copy(phase = VoicePhase.LISTENING, answer = null))
        assertEquals("Слушаю", listening.status)
        assertEquals(PanelView.Tone.CYAN, listening.tone)
        val hearing = VoicePresentation.open(secret.copy(phase = VoicePhase.LISTENING, hearing = true))
        assertEquals("Слышу вас…", hearing.body)
        val transcribing = VoicePresentation.open(secret.copy(phase = VoicePhase.PROCESSING, heard = null, answer = null))
        assertEquals("Распознаю", transcribing.status)
        assertNull("no made-up transcript before there is one", transcribing.body)
        val thinking = VoicePresentation.open(secret.copy(phase = VoicePhase.PROCESSING, answer = null))
        assertEquals("«${secret.heard}»", thinking.body)
        assertEquals(PanelView.Tone.AMBER, thinking.tone)
        assertEquals("Пятница · команда", VoicePresentation.open(secret).status)
        val error = VoicePresentation.open(secret.copy(phase = VoicePhase.ERROR, answer = "Нет сети"))
        assertEquals(PanelView.Tone.ERROR, error.tone)
    }
}

class ScreenHoldTest {

    private fun panel(phase: VoicePhase, phaseAt: Long = 0, input: Long = 0) =
        VoicePanel(session = 1, phase = phase, lastInputAt = input, phaseAt = phaseAt)

    @Test
    fun `held while the conversation is live`() {
        listOf(VoicePhase.GREETING, VoicePhase.LISTENING, VoicePhase.PROCESSING, VoicePhase.SPEAKING).forEach {
            assertTrue("$it", ScreenHold.hold(panel(it), now = 30_000))
        }
    }

    @Test
    fun `after the answer, held for reading and then released`() {
        val result = panel(VoicePhase.RESULT, phaseAt = 20_000)
        assertTrue(ScreenHold.hold(result, 20_000 + ScreenHold.READ_MS - 1))
        assertFalse(ScreenHold.hold(result, 20_000 + ScreenHold.READ_MS))
        assertEquals(ScreenHold.READ_MS, ScreenHold.recheckIn(result, 20_000))
        assertNull(ScreenHold.recheckIn(result, 20_000 + ScreenHold.READ_MS))
        // A touch on the panel starts the reading time again.
        val touched = result.copy(lastInputAt = 25_000)
        assertTrue(ScreenHold.hold(touched, 25_000 + ScreenHold.READ_MS - 1))
    }

    @Test
    fun `never longer than the guard without the owner's input, even mid-answer`() {
        val long = panel(VoicePhase.SPEAKING, input = 0)
        assertTrue(ScreenHold.hold(long, ScreenHold.GUARD_MS - 1))
        assertFalse(ScreenHold.hold(long, ScreenHold.GUARD_MS))
        assertEquals(ScreenHold.GUARD_MS, ScreenHold.recheckIn(long, 0))
        // A new turn by the owner starts a new interval.
        assertTrue(ScreenHold.hold(long.copy(lastInputAt = ScreenHold.GUARD_MS), ScreenHold.GUARD_MS + 1))
    }

    @Test
    fun `no session, no hold`() {
        assertFalse(ScreenHold.hold(null, 0))
        assertNull(ScreenHold.recheckIn(null, 0))
    }
}

class SurfacesTest {

    private val base = Surfaces.Conditions(
        locked = false, appInForeground = false, overlayAllowed = true,
        fullScreenAllowed = true, notificationsAllowed = true, userInitiated = true
    )

    private fun pick(c: Surfaces.Conditions) = Surfaces.choose(c)

    @Test
    fun `unlocked - the floating panel, Friday's own layer when she is open, voice when no overlay`() {
        assertEquals(Surfaces.Surface.OVERLAY, pick(base))
        assertEquals(Surfaces.Surface.IN_APP, pick(base.copy(appInForeground = true)))
        assertEquals(Surfaces.Surface.VOICE_ONLY, pick(base.copy(overlayAllowed = false)))
    }

    @Test
    fun `locked - the panel when allowed, a private notification otherwise, voice when nothing is`() {
        val locked = base.copy(locked = true)
        assertEquals(Surfaces.Surface.LOCK_ACTIVITY, pick(locked))
        assertEquals(Surfaces.Surface.LOCK_NOTIFICATION, pick(locked.copy(fullScreenAllowed = false)))
        assertEquals(Surfaces.Surface.VOICE_ONLY, pick(locked.copy(notificationsAllowed = false)))
        // The overlay permission is irrelevant over the lock screen: overlays are hidden there.
        assertEquals(Surfaces.Surface.LOCK_ACTIVITY, pick(locked.copy(overlayAllowed = false)))
    }

    @Test
    fun `friday's own announcements never light the locked screen`() {
        assertEquals(Surfaces.Surface.VOICE_ONLY, pick(base.copy(locked = true, userInitiated = false)))
        assertEquals(Surfaces.Surface.OVERLAY, pick(base.copy(userInitiated = false)))
    }
}
