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
    fun `rejects copied local system instructions`() {
        assertRejected("oi", LocalPrompt.SYSTEM_PROMPT)
        assertRejected("oi", "Você é Friday, uma assistente local neste celular. " +
            "Responda em português do Brasil, com respostas curtas e honestas.")
    }

    @Test
    fun `rejects the two leaked instructions reported from the local reply`() {
        assertRejected(
            "o que você sabe fazer?",
            "Não diga que pesquisou na internet. Quando não souber ou não puder fazer algo, diga isso claramente."
        )
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
    fun `rejects short repeated token loops from local completion`() {
        assertRejected("como está o tempo?", "controle de controle de controle de controle de")
        assertRejected("oi", "ajuda ajuda ajuda ajuda")
        assertRejected("oi", "não sei não sei não sei")
        assertRejected("oi", "isso é um teste isso é um teste isso é um teste")
    }

    @Test
    fun `uses a helpful neutral fallback for rejected output`() {
        val fallback = LocalResponseQuality.checked("como está o tempo?", "controle de controle de controle de")

        assertEquals(
            "A resposta local travou em repetição e foi descartada. Tente novamente ou reformule o pedido.",
            fallback
        )
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
    fun `preserves normal answers and arithmetic`() {
        assertEquals("Sou Friday.", LocalResponseQuality.checked("quem é você?", " Sou Friday. "))
        assertEquals("Olá! Como posso ajudar?", LocalResponseQuality.checked("Olá", "Olá! Como posso ajudar?"))
        assertEquals("2 + 2 = 4.", LocalResponseQuality.checked("quanto é 2 + 2?", "2 + 2 = 4."))
        assertEquals("12 x 3 = 36.", LocalResponseQuality.checked("quanto é 12 x 3?", "12 x 3 = 36."))
    }

    @Test
    fun `requires grounded summaries to use concrete source terms`() {
        val sources = LocalInternetReply.SearchSources(listOf(SearchItem(
            "Chuva forte atinge Salvador", "Defesa Civil alerta para alagamentos nesta terça-feira.",
            "https://fonte.test/chuva"
        )))

        assertTrue(LocalResponseQuality.isRelevantToSources(
            "A Defesa Civil alertou para alagamentos em Salvador.", sources
        ))
        assertTrue(!LocalResponseQuality.isRelevantToSources(
            "Este portal traz notícias e informações atuais.", sources
        ))
    }

    private fun assertRejected(prompt: String, answer: String) {
        assertTrue(LocalResponseQuality.checked(prompt, answer).contains("travou em repetição"))
    }
}
