package com.friday.ai.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsFeedbackTest {
    @Test
    fun `voice enrollment prompts and results use Brazilian Portuguese`() {
        assertEquals("Diga: «Ligue para minha mãe»", SettingsFeedback.voicePrompt("Ligue para minha mãe"))
        assertEquals(
            "Concluído. Semelhança das gravações: 0,75",
            SettingsFeedback.enrolmentComplete(0.75f)
        )
        assertEquals("Não consegui ouvir.", SettingsFeedback.AUDIO_NOT_HEARD)
        assertEquals("Gravação muito curta.", SettingsFeedback.AUDIO_TOO_SHORT)
    }

    @Test
    fun `settings errors do not contain Cyrillic`() {
        val messages = listOf(
            SettingsFeedback.GMAIL_DISCONNECTED,
            SettingsFeedback.GMAIL_CONNECTED,
            SettingsFeedback.VOICE_MODEL_UNAVAILABLE,
            SettingsFeedback.INSUFFICIENT_SAMPLES,
            SettingsFeedback.LAZURI_MISSING_CREDENTIALS,
            SettingsFeedback.LAZURI_REGISTRATION_FAILED,
            SettingsFeedback.lazuriUnreachable("https://example.com")
        )
        messages.forEach { message ->
            assertFalse(message, Regex("[\\u0400-\\u04FF]").containsMatchIn(message))
        }
        assertEquals(
            "Não foi possível conectar ao Lazuri em https://example.com",
            SettingsFeedback.lazuriUnreachable("https://example.com")
        )
    }
}
