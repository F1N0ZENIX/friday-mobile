package com.friday.ai.service.voice

import com.friday.ai.data.remote.GroqApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceErrorsTest {

    @Test
    fun `all Groq error cases are spoken in Brazilian Portuguese`() {
        val cases = listOf(
            GroqApiException(404, "model_not_found") to "O Groq mudou o modelo.",
            GroqApiException(401, "unauthorized") to "A chave do Groq foi recusada.",
            GroqApiException(429, "rate limited") to "O limite de solicitações",
            IOException("offline") to "Confira sua conexão",
            IllegalStateException("internal") to "Não consegui obter uma resposta"
        )
        for ((error, expected) in cases) {
            for (legacyRussianFlag in listOf(true, false)) {
                val spoken = VoiceErrors.spoken(error, legacyRussianFlag)
                org.junit.Assert.assertTrue("Resposta inesperada: $spoken", spoken.contains(expected))
            }
        }
    }

    @Test
    fun `empty response is localized regardless of legacy language flag`() {
        val expected = "O modelo não retornou nenhuma resposta. Tente novamente."
        assertEquals(expected, VoiceErrors.empty(true))
        assertEquals(expected, VoiceErrors.empty(false))
    }
}
