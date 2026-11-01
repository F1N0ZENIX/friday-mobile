package com.friday.ai.service.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why wake capture ended or could not start. No audio, words or keys: categories only. */
enum class WakeFailure { PERMISSION, MODEL, RECORDER, READ, STALLED, UNEXPECTED, TEARDOWN }

/**
 * What the wake listener is really doing. [Status.LISTENING] is reported
 * only once the recorder delivers frames — a running service, a job or a
 * notification prove nothing about the microphone.
 */
data class WakeHealth(
    val status: Status,
    val reason: WakeFailure? = null,
    /** Retry number while [Status.RECOVERING]; 0 otherwise. */
    val attempt: Int = 0,
    /** Delay before that retry. */
    val retryInMs: Long = 0
) {
    enum class Status {
        DISABLED, UNAVAILABLE, LOADING, STARTING, LISTENING,
        PAUSING, PAUSED_FOR_COMMAND, PAUSED_FOR_CALL, RECOVERING, FAILED, STOPPED
    }

    /** The notification's status line: what is happening, and what to do about it. */
    fun describe(): String = when (status) {
        Status.DISABLED -> "Ativação por voz desativada"
        Status.UNAVAILABLE, Status.FAILED -> failureText(reason)
        Status.LOADING -> "Carregando modelo de voz..."
        Status.STARTING -> "Iniciando microfone..."
        Status.LISTENING -> "Ouvindo a palavra \"Friday\""
        Status.PAUSING, Status.PAUSED_FOR_COMMAND -> "Aguardando comando..."
        Status.PAUSED_FOR_CALL -> "Pausado durante a chamada"
        Status.RECOVERING ->
            "Microfone indisponível. Tentativa em ${retryInMs / MS_PER_SECOND}s ($attempt/$MAX_SHOWN)"
        Status.STOPPED -> "Parado"
    }

    private fun failureText(reason: WakeFailure?): String = when (reason) {
        WakeFailure.PERMISSION -> "Sem acesso ao microfone. Abra o Friday e conceda a permissão."
        WakeFailure.MODEL -> "Modelo de voz não instalado. Baixe-o nas configurações."
        else -> "Friday não está ouvindo. Abra o aplicativo para reiniciar."
    }

    companion object {
        private const val MS_PER_SECOND = 1000
        /** The default schedule's length, for "2/5" in the status line. */
        const val MAX_SHOWN = 5
    }
}

/**
 * The latest wake health and service-start failure, for settings and
 * diagnostics. A start Android refused (background restrictions, a
 * microphone service from the background on Android 14+) lands here, not
 * only in logcat.
 */
object WakeStatus {
    private val _health = MutableStateFlow(WakeHealth(WakeHealth.Status.DISABLED))
    val health: StateFlow<WakeHealth> = _health.asStateFlow()

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    fun report(health: WakeHealth) {
        _health.value = health
    }

    fun startFailed(message: String?) {
        _startError.value = message
    }
}

/**
 * One wake-capture session's source: a recorder plus the decoder.
 * [WakeWordEngine][com.friday.ai.service.WakeWordEngine] on the phone; a fake in tests.
 */
interface WakeSource {

    /** How a capture ended. The recorder is already released when this is returned. */
    sealed interface End {
        data class Detected(val heard: String, val audio: FloatArray, val continuing: Boolean) : End
        /** Ended by [interrupt] or cancellation. */
        data object Stopped : End
        data class Failed(val reason: WakeFailure) : End
    }

    /** Captures until the wake call, [interrupt], or a failure. Blocking; call off the main thread. */
    suspend fun capture(onFrame: () -> Unit): End

    /** Ends a capture from any thread, unblocking a pending read. Idempotent. */
    fun interrupt()

    /** Frees the decoder. Only once no capture is running. */
    fun close()
}
