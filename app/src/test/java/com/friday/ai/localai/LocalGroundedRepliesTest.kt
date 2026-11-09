package com.friday.ai.localai

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalGroundedRepliesTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-01T12:34:00Z"), ZoneId.of("America/Bahia"))

    @Test
    fun `answers screenshot questions with grounded Portuguese replies`() {
        assertEquals(
            "Sou a Friday, uma assistente de IA local neste celular.",
            LocalGroundedReplies.reply("Quem é você?")
        )
        assertEquals(
            "Uso o modelo local selecionado nas configurações de IA local. Você pode conferir o nome lá.",
            LocalGroundedReplies.reply("Qual modelo você usa?")
        )
        assertEquals("Olá! Como posso ajudar?", LocalGroundedReplies.reply("Olá!!!"))
        assertEquals(
            "Não tenho acesso ao clima em tempo real.",
            LocalGroundedReplies.reply("Como está o tempo hoje?")
        )
        assertEquals("Agora são 09:34.", LocalGroundedReplies.reply("Que horas são agora?", clock))
        assertEquals(
            "Não consigo criar alarmes ou temporizadores no modo local.",
            LocalGroundedReplies.reply("Crie um alarme para 7 horas")
        )
    }

    @Test
    fun `does not intercept creative or generic alarm discussion`() {
        assertNull(LocalGroundedReplies.reply("escreva um poema sobre o tempo"))
        assertNull(LocalGroundedReplies.reply("por que os alarmes são importantes?"))
        assertNull(LocalGroundedReplies.reply("me fale sobre temporizadores"))
    }

    @Test
    fun `uses arithmetic replies when local mode is offline`() {
        assertEquals("O resultado é 14.", LocalGroundedReplies.reply("2 + 3 * 4"))
        assertEquals("Não é possível dividir por zero.", LocalGroundedReplies.reply("1 / 0"))
        assertNull(LocalGroundedReplies.reply("1 / 0 +"))
        assertNull(LocalGroundedReplies.reply("(1 / 0"))
    }
}
