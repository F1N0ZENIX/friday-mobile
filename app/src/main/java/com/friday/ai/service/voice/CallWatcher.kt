package com.friday.ai.service.voice

import com.friday.ai.core.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the audio mode for calls: ringing, a phone call, or a VoIP call
 * (WhatsApp, Telegram). The audio mode needs no permission and covers all
 * three, on the speaker, a wired headset or Bluetooth alike.
 *
 * A call starting is reported at once. Its end is reported only after the
 * mode has stayed normal for [settleMs]: between ringing and answering, and
 * while a VoIP app hands audio over, the mode flickers through normal.
 */
class CallWatcher(
    private val scope: CoroutineScope,
    private val mode: () -> Int,
    private val pollMs: Long = POLL_MS,
    private val settleMs: Long = SETTLE_MS,
    private val onChange: suspend (inCall: Boolean) -> Unit
) {

    companion object {
        const val POLL_MS = 1_000L
        const val SETTLE_MS = 2_000L
    }

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var inCall = false
            var normalFor = 0L
            while (isActive) {
                val busy = CallState.holdsMicrophone(mode())
                when {
                    busy && !inCall -> {
                        inCall = true
                        onChange(true)
                    }
                    busy -> normalFor = 0
                    inCall -> {
                        normalFor += pollMs
                        if (normalFor >= settleMs) {
                            inCall = false
                            normalFor = 0
                            onChange(false)
                        }
                    }
                }
                delay(pollMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
