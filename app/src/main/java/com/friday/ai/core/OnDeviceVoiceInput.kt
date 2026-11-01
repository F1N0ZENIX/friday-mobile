package com.friday.ai.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private companion object {
        const val PORTUGUESE_BRAZIL = "pt-BR"
        const val SINGLE_RECOGNITION_RESULT = 1
    }

    private var recognizer: SpeechRecognizer? = null

    private val _recognizedText = MutableStateFlow<String?>(null)
    val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun startListening() {
        if (_isListening.value) return
        _error.value = null

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            unavailable()
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            unavailable()
            return
        }

        recognizer?.destroy()
        try {
            val onDeviceRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = onDeviceRecognizer
            onDeviceRecognizer.setRecognitionListener(listener)
            _isListening.value = true
            onDeviceRecognizer.startListening(recognitionIntent())
        } catch (exception: RuntimeException) {
            recognizer?.destroy()
            recognizer = null
            unavailable(exception.cause ?: exception)
        }
    }

    /** Ends the on-device recording now and asks it for the partial turn. */
    fun stopListening() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        _isListening.value = false
    }

    fun clearResult() {
        _recognizedText.value = null
    }

    private fun unavailable(cause: Throwable? = null) {
        _isListening.value = false
        val defaultMessage = "Reconhecedor de voz offline não disponível neste celular."
        _error.value = cause?.message?.takeIf { it.isNotBlank() } ?: defaultMessage
    }

    private fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, PORTUGUESE_BRAZIL)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, PORTUGUESE_BRAZIL)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, SINGLE_RECOGNITION_RESULT)
    }

    private val listener = object : RecognitionListener {
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
            _isListening.value = false
            recognizer?.destroy()
            recognizer = null
            if (text.isNullOrBlank()) {
                _error.value = "Não consegui ouvir. Tente de novo."
            } else {
                _recognizedText.value = text
            }
        }

        override fun onError(error: Int) {
            _isListening.value = false
            recognizer?.destroy()
            recognizer = null
            _error.value = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissão de microfone não concedida."
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Não consegui ouvir. Tente de novo."
                else -> "O reconhecedor de voz offline falhou. Tente de novo."
            }
        }
    }
}
