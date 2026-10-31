package com.friday.ai.service.voice

import com.friday.ai.data.remote.GroqApiException
import java.io.IOException

/**
 * Turns a failed answer into something worth hearing.
 *
 * "Something went wrong" was all Friday ever said, whatever the cause — so when
 * Groq retired her model there was no way to tell from the phone. Each cause
 * the user can act on gets its own sentence.
 */
object VoiceErrors {

    private const val UNAUTHORISED = 401
    private const val RATE_LIMITED = 429

    fun spoken(e: Throwable, russian: Boolean): String = when {
        e is GroqApiException && e.modelMissing ->
            "A Groq mudou o modelo; já selecionei outro. Diga novamente, por favor."
            "A Groq mudou os modelos; selecionei outro. Diga novamente, por favor."
        e is GroqApiException && e.code == UNAUTHORISED ->
            "A chave da Groq foi rejeitada. Confira-a nas configurações."
            "A chave da Groq foi rejeitada. Confira-a nas configurações."
        e is GroqApiException && e.code == RATE_LIMITED ->
            "A Groq pediu para aguardar: o limite de solicitações foi atingido."
            "A Groq pediu para aguardar: o limite de solicitações foi atingido."
        e is IOException ->
            "Não consegui conectar à Groq. Verifique sua internet."
            "Não consegui conectar à Groq. Verifique sua internet."
        else ->
            "A Groq não respondeu: ${e.message ?: e::class.simpleName}"
            "A Groq não respondeu: ${e.message ?: e::class.simpleName}"
    }

    /** Said when the model answered with nothing at all. */
    fun empty(russian: Boolean): String =
        "A IA não retornou uma resposta. Tente novamente."
}
