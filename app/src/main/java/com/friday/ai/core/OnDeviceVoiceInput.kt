package com.friday.ai.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PortugueseModelAction {
    START_RECOGNITION,
    WAIT_FOR_QUEUED_DOWNLOAD,
    DOWNLOAD_MODEL,
    UNSUPPORTED
}

internal fun brazilianPortugueseModelAction(
    installed: List<String>,
    pending: List<String>,
    supported: List<String>
): PortugueseModelAction = when {
    OnDeviceVoiceInput.supportsBrazilianPortuguese(installed) ->
        PortugueseModelAction.START_RECOGNITION
    OnDeviceVoiceInput.supportsBrazilianPortuguese(pending) ->
        PortugueseModelAction.WAIT_FOR_QUEUED_DOWNLOAD
    OnDeviceVoiceInput.supportsBrazilianPortuguese(supported) ->
        PortugueseModelAction.DOWNLOAD_MODEL
    else -> PortugueseModelAction.UNSUPPORTED
}

internal fun shouldFallbackToOnDeviceRecognition(error: Int, cannotCheckSupport: Int): Boolean =
    error == cannotCheckSupport

private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, OnDeviceVoiceInput.PORTUGUESE_BRAZIL)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, OnDeviceVoiceInput.PORTUGUESE_BRAZIL)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, OnDeviceVoiceInput.SINGLE_RECOGNITION_RESULT)
}

/**
 * Voice input for the chat screen.
 *
 * This intentionally never falls back to [SpeechRecognizer.createSpeechRecognizer]: that
 * recognizer may send microphone audio to a provider's servers. Android 12 / API 31 added
 * an explicit on-device factory; on older releases the chat plainly reports that no private
 * recognizer is available.
 */
class OnDeviceVoiceInput(
    private val context: Context
) {

    internal companion object {
        const val PORTUGUESE_BRAZIL = "pt-BR"
        const val SINGLE_RECOGNITION_RESULT = 1

        fun supportsBrazilianPortuguese(languages: List<String>): Boolean =
            languages.any { it.replace('_', '-').equals(PORTUGUESE_BRAZIL, ignoreCase = true) }
    }

    private var recognizer: SpeechRecognizer? = null
    private var supportCheckPending = false

    private val _recognizedText = MutableStateFlow<String?>(null)
    val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun startListening() {
        if (_isListening.value) return
        _error.value = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            _isListening.value = false
            _error.value = "Reconhecedor de voz offline não disponível neste celular."
            return
        }

        releaseRecognizer()
        try {
            val onDeviceRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = onDeviceRecognizer
            onDeviceRecognizer.setRecognitionListener(recognitionListener(onDeviceRecognizer))
            _isListening.value = true
            val intent = recognitionIntent()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                supportCheckPending = true
                onDeviceRecognizer.checkRecognitionSupport(
                    intent,
                    context.mainExecutor,
                    supportCallback(onDeviceRecognizer, intent)
                )
            } else {
                beginRecognition(onDeviceRecognizer, intent)
            }
        } catch (_: RuntimeException) {
            releaseRecognizer()
            _isListening.value = false
            _error.value = "Reconhecedor de voz offline não disponível neste celular."
        }
    }

    /** Ends the on-device recording now and asks it for the partial turn. */
    fun stopListening() {
        val activeRecognizer = recognizer ?: return
        if (supportCheckPending) {
            finish(activeRecognizer)
            return
        }
        try {
            activeRecognizer.stopListening()
        } catch (_: RuntimeException) {
            finish(activeRecognizer, "Não foi possível liberar o microfone. Feche outro app que esteja usando ele.")
        }
    }

    fun destroy() {
        releaseRecognizer()
        _isListening.value = false
    }

    fun clearResult() {
        _recognizedText.value = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun supportCallback(
        checkedRecognizer: SpeechRecognizer,
        intent: Intent
    ) = object : RecognitionSupportCallback {
        override fun onSupportResult(support: RecognitionSupport) {
            if (recognizer !== checkedRecognizer) return
            supportCheckPending = false
            when (
                brazilianPortugueseModelAction(
                    support.installedOnDeviceLanguages,
                    support.pendingOnDeviceLanguages,
                    support.supportedOnDeviceLanguages
                )
            ) {
                PortugueseModelAction.START_RECOGNITION -> beginRecognition(checkedRecognizer, intent)
                PortugueseModelAction.WAIT_FOR_QUEUED_DOWNLOAD -> finish(
                    checkedRecognizer,
                    "O reconhecimento offline em português (Brasil) já está sendo baixado. " +
                        "Toque no microfone de novo quando terminar."
                )
                PortugueseModelAction.DOWNLOAD_MODEL -> try {
                    checkedRecognizer.triggerModelDownload(intent)
                    finish(
                        checkedRecognizer,
                        "Baixando o reconhecimento offline em português (Brasil). " +
                            "Toque no microfone de novo quando terminar."
                    )
                } catch (_: RuntimeException) {
                    finish(
                        checkedRecognizer,
                        "O português (Brasil) offline precisa ser baixado nas configurações de voz do celular."
                    )
                }
                PortugueseModelAction.UNSUPPORTED -> finish(
                    checkedRecognizer,
                    "Português (Brasil) não é suportado pelo reconhecedor offline deste celular."
                )
            }
        }

        override fun onError(error: Int) {
            if (recognizer !== checkedRecognizer) return
            supportCheckPending = false
            if (shouldFallbackToOnDeviceRecognition(error, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)) {
                beginRecognition(checkedRecognizer, intent)
            } else {
                finish(
                    checkedRecognizer,
                    "Não foi possível verificar o modelo offline em português (Brasil)."
                )
            }
        }
    }

    private fun beginRecognition(onDeviceRecognizer: SpeechRecognizer, intent: Intent) {
        if (recognizer !== onDeviceRecognizer) return
        try {
            onDeviceRecognizer.startListening(intent)
        } catch (_: RuntimeException) {
            finish(
                onDeviceRecognizer,
                "Microfone ocupado ou reconhecedor offline indisponível. " +
                    "Feche outro app que esteja usando o microfone."
            )
        }
    }

    private fun finish(expectedRecognizer: SpeechRecognizer, message: String? = null): Boolean {
        if (recognizer !== expectedRecognizer) return false
        _isListening.value = false
        releaseRecognizer()
        if (message != null) _error.value = message
        return true
    }

    private fun releaseRecognizer() {
        val activeRecognizer = recognizer ?: return
        recognizer = null
        supportCheckPending = false
        try {
            activeRecognizer.cancel()
        } catch (_: RuntimeException) {
            // The framework can already have stopped the session.
        }
        try {
            activeRecognizer.destroy()
        } catch (_: RuntimeException) {
            // Destruction remains necessary even when cancellation failed.
        }
    }

    private fun recognitionListener(owner: SpeechRecognizer) = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

        override fun onResults(results: android.os.Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (!finish(owner)) return
            if (text.isNullOrBlank()) {
                _error.value = "Não consegui ouvir. Tente de novo."
            } else {
                _recognizedText.value = text
            }
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissão de microfone não concedida."
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Não consegui ouvir. Tente de novo."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "Microfone ocupado. Feche outro app ou desative a escuta contínua e tente de novo."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "O reconhecedor offline não ficou disponível. " +
                        "Verifique o modelo de voz em português (Brasil)."
                else -> "Reconhecedor offline falhou (código $error). Tente novamente."
            }
            finish(owner, message)
        }
    }
}
