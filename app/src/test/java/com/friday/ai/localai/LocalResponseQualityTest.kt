package com.friday.ai.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResponseQualityTest {
    @Test
    fun `rejects screenshot echo with or without punctuation`() {
        assertRejected("quem é você?", "Quem é você?\nIncluído de vocês")
        assertRejected("Olá", "Olá.")
    }

    @Test
    fun `rejects screenshot invented dialogue and turn labels`() {
        val screenshot = "Uso: que horas são?\nSunday: Não consigo informar a hora agora."
        assertRejected("que horas são?", screenshot)
        assertRejected("oi", "Usuario: Olá\nAssistant: Oi")
        assertRejected("oi", "User: Hi\nSunday: Hello")
    }

    @Test
    fun `rejects repeated local model output`() {
        assertRejected("oi", "frase repetida\nfrase repetida\nfrase repetida")
        val repeatedSunday = "Sunday: Não consigo ajudar agora.\n" +
            "Sunday: Não consigo ajudar hoje.\n" +
            "Sunday: Não consigo ajudar mais tarde."
        assertRejected("oi", repeatedSunday)
        assertRejected("oi", "Repita isto. Repita isto. Repita isto. Repita isto.")
    }

    @Test
    fun `rejects cyrillic only when prompt did not use it`() {
        assertRejected("oi", "Привет")
        assertEquals("Привет", LocalResponseQuality.checked("Escreva Привет", "Привет"))
    }

    @Test
    fun `preserves times lists and requested dialogue`() {
        assertEquals("Horário: 10:20", LocalResponseQuality.checked("que horas são?", "Horário: 10:20"))
        val schedule = "08:00 café\n10:20 reunião"
        assertEquals(schedule, LocalResponseQuality.checked("agenda", schedule))
        val dialogue = "Usuário: Boa noite.\nSunday: Boa noite!"
        assertEquals(dialogue, LocalResponseQuality.checked("escreva um diálogo literário", dialogue))
    }

    @Test
    fun `preserves normal answers`() {
        assertEquals("Sou Friday.", LocalResponseQuality.checked("quem é você?", " Sou Friday. "))
        assertEquals("Olá! Como posso ajudar?", LocalResponseQuality.checked("Olá", "Olá! Como posso ajudar?"))
    }

    private fun assertRejected(prompt: String, answer: String) {
        assertTrue(LocalResponseQuality.checked(prompt, answer).contains("resposta repetida"))
    }
}
