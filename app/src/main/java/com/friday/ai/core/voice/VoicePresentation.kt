package com.friday.ai.core.voice

/** What a panel says, decided apart from how it is drawn. */
data class PanelView(
    val status: String,
    val tone: Tone,
    val mood: Mood,
    /** The text under the status; null when there is none, or it is withheld. */
    val body: String?,
    /** Locked, with something withheld that unlocking would show. */
    val canReveal: Boolean = false
) {
    /** Cyan — listening or speaking; amber — working; red — a real failure. */
    enum class Tone { CYAN, AMBER, MUTED, ERROR }

    /** What the reactor does. */
    enum class Mood { LISTENING, THINKING, SPEAKING, IDLE }
}

/**
 * How a [VoicePanel] reads on each surface.
 *
 * On an unlocked phone the panel shows what was said and answered. Over the
 * lock screen anyone nearby can read it, so by default it shows only the
 * stage — never the owner's words, contact names, a dictated message or the
 * model's answer; "Разблокировать для деталей" asks Android to unlock first.
 * This is separate from [com.friday.ai.command.LockPolicy]: being allowed
 * to run a command is not being allowed to put its result on the lock
 * screen.
 */
object VoicePresentation {

    /** The unlocked phone: the floating panel, the in-app layer, the lock panel once unlocked. */
    fun open(p: VoicePanel): PanelView = when (p.phase) {
        VoicePhase.GREETING -> PanelView(
            "Friday", PanelView.Tone.CYAN, PanelView.Mood.SPEAKING, p.answer?.ifBlank { null } ?: "Friday está aqui"
        )
        VoicePhase.LISTENING -> PanelView(
            "Estou ouvindo", PanelView.Tone.CYAN, PanelView.Mood.LISTENING,
            if (p.hearing) "Estou ouvindo você…" else null
        )
        VoicePhase.PROCESSING -> when {
            !p.answer.isNullOrBlank() -> PanelView("Pensando", PanelView.Tone.AMBER, PanelView.Mood.THINKING, p.answer)
            p.heard.isNullOrBlank() -> PanelView("Reconhecendo", PanelView.Tone.AMBER, PanelView.Mood.THINKING, null)
            else -> PanelView("Pensando", PanelView.Tone.AMBER, PanelView.Mood.THINKING, "«${p.heard}»")
        }
        VoicePhase.SPEAKING -> PanelView(speaker(p), PanelView.Tone.CYAN, PanelView.Mood.SPEAKING, p.answer)
        VoicePhase.RESULT -> PanelView(speaker(p), PanelView.Tone.MUTED, PanelView.Mood.IDLE, p.answer)
        VoicePhase.ERROR -> PanelView("Não foi possível", PanelView.Tone.ERROR, PanelView.Mood.IDLE, p.answer)
    }

    /**
     * The lock screen. [unlocked]: the owner has just unlocked through
     * Android, so what was withheld may be shown.
     */
    fun locked(p: VoicePanel, unlocked: Boolean): PanelView {
        if (unlocked) return open(p)
        val withheld = !p.answer.isNullOrBlank()
        // Says that something is hidden, never what.
        val hint = if (withheld) HIDDEN else null
        return when (p.phase) {
            VoicePhase.GREETING -> PanelView("Friday está aqui", PanelView.Tone.CYAN, PanelView.Mood.SPEAKING, null)
            VoicePhase.LISTENING -> PanelView("Estou ouvindo", PanelView.Tone.CYAN, PanelView.Mood.LISTENING, null)
            VoicePhase.PROCESSING ->
                PanelView("Processando pedido", PanelView.Tone.AMBER, PanelView.Mood.THINKING, null)
            VoicePhase.SPEAKING ->
                PanelView("Respondendo", PanelView.Tone.CYAN, PanelView.Mood.SPEAKING, hint, canReveal = withheld)
            VoicePhase.RESULT ->
                PanelView("Resposta pronta", PanelView.Tone.MUTED, PanelView.Mood.IDLE, hint, canReveal = withheld)
            VoicePhase.ERROR -> PanelView(
                "Não foi possível", PanelView.Tone.ERROR, PanelView.Mood.IDLE, hint, canReveal = withheld
            )
        }
    }

    /** The only text a locked panel shows besides its stage. */
    const val HIDDEN = "Texto oculto enquanto o celular estiver bloqueado"

    private fun speaker(p: VoicePanel): String = p.source?.let { "Friday · $it" } ?: "Friday"
}

/**
 * Whether the lock-screen panel keeps the display on. Only that visible
 * activity ever holds it (FLAG_KEEP_SCREEN_ON); never a service, a wake
 * lock, or the system timeout.
 *
 *  - while Friday greets, listens, works or speaks — the owner is in the
 *    middle of it;
 *  - for [READ_MS] after the answer or a failure, to read it (a touch on the
 *    panel starts that again);
 *  - never longer than [GUARD_MS] after the owner last spoke or touched it:
 *    a long answer keeps playing, the screen may go dark.
 */
object ScreenHold {

    const val READ_MS = 10_000L
    const val GUARD_MS = 120_000L

    fun hold(p: VoicePanel?, now: Long): Boolean {
        if (p == null || now - p.lastInputAt >= GUARD_MS) return false
        return p.phase.active || now - maxOf(p.phaseAt, p.lastInputAt) < READ_MS
    }

    /** How long until [hold] may change without a new event; null when it never will. */
    fun recheckIn(p: VoicePanel?, now: Long): Long? {
        if (p == null || !hold(p, now)) return null
        val guard = p.lastInputAt + GUARD_MS - now
        val read = if (p.phase.active) Long.MAX_VALUE else maxOf(p.phaseAt, p.lastInputAt) + READ_MS - now
        return minOf(guard, read).coerceAtLeast(1)
    }
}

/** Where a session is shown, given what the phone and Android allow right now. */
object Surfaces {

    enum class Surface { IN_APP, OVERLAY, LOCK_ACTIVITY, LOCK_NOTIFICATION, VOICE_ONLY }

    data class Conditions(
        val locked: Boolean,
        val appInForeground: Boolean,
        val overlayAllowed: Boolean,
        val fullScreenAllowed: Boolean,
        val notificationsAllowed: Boolean,
        val userInitiated: Boolean
    )

    fun choose(c: Conditions): Surface = when {
        c.locked -> locked(c)
        // Friday's own screen is open: her own layer, not a second window over it.
        c.appInForeground -> Surface.IN_APP
        c.overlayAllowed -> Surface.OVERLAY
        else -> Surface.VOICE_ONLY
    }

    private fun locked(c: Conditions): Surface = when {
        // Announcements Friday makes on her own never light the screen.
        !c.userInitiated -> Surface.VOICE_ONLY
        !c.notificationsAllowed -> Surface.VOICE_ONLY
        c.fullScreenAllowed -> Surface.LOCK_ACTIVITY
        else -> Surface.LOCK_NOTIFICATION
    }
}
