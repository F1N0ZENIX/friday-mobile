package com.friday.ai.service.voice

import android.os.SystemClock
import com.friday.ai.core.voice.VoicePanelStore

/**
 * The one voice session every surface shows: the floating panel, the layer
 * inside Friday's own screen, and the lock-screen panel. The voice service
 * writes it ([com.friday.ai.service.FridayOverlayManager]); the surfaces
 * only read it, and ask through [close] to end it.
 */
object VoiceUi {

    val store = VoicePanelStore(SystemClock::elapsedRealtime)

    /** Set by the voice service: stop speaking and listening, and close the session. */
    @Volatile var onClose: (() -> Unit)? = null

    /** The owner closed the panel. What was already done stays done. */
    fun close() {
        onClose?.invoke() ?: store.end()
    }
}
