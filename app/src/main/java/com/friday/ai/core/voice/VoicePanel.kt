package com.friday.ai.core.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The stages of one spoken exchange, as every surface shows them. */
enum class VoicePhase {
    /** Called; Friday greets. */
    GREETING,
    LISTENING,
    /** Recording ended: transcribing, then working on it. */
    PROCESSING,
    SPEAKING,
    /** The answer is in; the panel lingers for reading, then closes. */
    RESULT,
    /** A real failure: no key, no network, no microphone. */
    ERROR;

    /** The microphone or the speaker is busy: the owner is in the middle of it. */
    val active: Boolean get() = this == GREETING || this == LISTENING || this == PROCESSING || this == SPEAKING
}

/**
 * One voice session, as data — the single source every surface draws from:
 * the floating panel, the in-app layer and the lock-screen panel. Nothing
 * here is read back from a View; a panel that failed to appear loses
 * nothing.
 */
data class VoicePanel(
    /** Increases with every session; an update for an older one is ignored. */
    val session: Long,
    val phase: VoicePhase,
    /** What the owner said, only once it has been transcribed. */
    val heard: String? = null,
    /** Friday's words: the greeting, the answer, a notice. */
    val answer: String? = null,
    /** "Слышу вас" while the owner is speaking. */
    val hearing: Boolean = false,
    /** Who answered: "команда" or "ИИ". */
    val source: String? = null,
    /** The owner called Friday (word, button) — not an announcement she made on her own. */
    val userInitiated: Boolean = true,
    /** When the owner last spoke or touched the panel (ms, monotonic). */
    val lastInputAt: Long = 0,
    /** When the phase last changed (ms, monotonic). */
    val phaseAt: Long = 0
)

/**
 * Holds the current [VoicePanel]. All changes go through here, one at a
 * time; the surfaces only observe.
 */
class VoicePanelStore(private val clock: () -> Long) {

    private val _state = MutableStateFlow<VoicePanel?>(null)
    val state: StateFlow<VoicePanel?> = _state.asStateFlow()

    /** Live microphone level, 0..1, kept apart so 20 updates a second never redraw the text. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var sessions = 0L

    /**
     * Starts a session unless one is open. A call by the owner during one
     * Friday started herself (an announcement) makes it the owner's.
     */
    fun begin(userInitiated: Boolean): Long {
        val now = clock()
        _state.update { current ->
            when {
                current == null -> VoicePanel(
                    session = ++sessions, phase = VoicePhase.GREETING,
                    userInitiated = userInitiated, lastInputAt = now, phaseAt = now
                )
                userInitiated && !current.userInitiated -> current.copy(userInitiated = true, lastInputAt = now)
                else -> current
            }
        }
        return _state.value?.session ?: sessions
    }

    /**
     * Moves the open session to [phase]. With none open nothing happens: a
     * callback arriving after the owner closed the panel must not bring it back.
     */
    fun phase(phase: VoicePhase, change: (VoicePanel) -> VoicePanel = { it }) {
        val now = clock()
        _state.update { current ->
            current ?: return@update null
            val moved = if (current.phase == phase) current else current.copy(phase = phase, phaseAt = now)
            // Listening again is the owner's new turn: their own input, for the screen's sake.
            val turn = if (phase == VoicePhase.LISTENING && current.phase != VoicePhase.LISTENING) {
                moved.copy(lastInputAt = now, hearing = false, heard = null)
            } else {
                moved
            }
            change(turn)
        }
    }

    /** The owner touched the panel: they are reading it. */
    fun touched() {
        val now = clock()
        _state.update { it?.copy(lastInputAt = now) }
    }

    fun level(value: Float) {
        _level.value = value.coerceIn(0f, 1f)
    }

    fun source(label: String?) {
        _state.update { it?.copy(source = label) }
    }

    /** Ends [session] — or whatever is open when null. A stale end leaves a newer session alone. */
    fun end(session: Long? = null) {
        _state.update { current ->
            val stale = current != null && session != null && current.session != session
            if (stale) current else null
        }
        _level.value = 0f
    }
}
