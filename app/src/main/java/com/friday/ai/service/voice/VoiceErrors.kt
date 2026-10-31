package com.friday.ai.service.voice

import com.friday.ai.data.remote.GroqApiException
import java.io.IOException

/** Converts failures into concise Brazilian Portuguese speech. */
object VoiceErrors {

    private const val UNAUTHORISED = 401
    private const val RATE_LIMITED = 429

    fun spoken(e: Throwable, russian: Boolean): String = when {
        e is GroqApiException && e.modelMissing ->
            "A Groq mudou o modelo; já selecionei outro. Diga novamente, por favor."
        e is GroqApiException && e.code == UNAUTHORISED ->
            "A chave da Groq foi rejeitada. Confira-a nas configurações."
        e is GroqApiException && e.code == RATE_LIMITED ->
            "A Groq pediu para aguardar: o limite de solicitações foi atingido."
        e is IOException ->
            "Não consegui conectar à Groq. Verifique sua internet."
        else ->
            "A Groq não respondeu: ${e.message ?: e::class.simpleName}"
    }

    /** Said when the model answered with nothing at all. */
    fun empty(russian: Boolean): String =
        "A IA não retornou uma resposta. Tente novamente."
}
