package com.friday.ai.service.voice

import com.friday.ai.data.remote.GroqApiException
import java.io.IOException

/**
 * Mensagens faladas em português brasileiro para erros da conversa.
 * Mantém o parâmetro histórico para não quebrar os chamadores existentes.
 */
@Suppress("UnusedParameter")
object VoiceErrors {

    private const val UNAUTHORISED = 401
    private const val RATE_LIMITED = 429

    fun spoken(e: Throwable, russian: Boolean): String = when {
        e is GroqApiException && e.modelMissing ->
            "O Groq mudou o modelo. Já alterei a seleção. Pode repetir, por favor?"
        e is GroqApiException && e.code == UNAUTHORISED ->
            "A chave do Groq foi recusada. Confira a chave nas configurações."
        e is GroqApiException && e.code == RATE_LIMITED ->
            "O limite de solicitações do Groq foi atingido. Aguarde um pouco."
        e is IOException ->
            "Não consegui me conectar ao Groq. Confira sua conexão com a internet."
        else ->
            "Não consegui obter uma resposta do Groq. Tente novamente."
    }

    /** Mensagem falada quando o modelo não retorna texto. */
    @Suppress("FunctionOnlyReturningConstant")
    fun empty(russian: Boolean): String =
        "O modelo não retornou nenhuma resposta. Tente novamente."
}
