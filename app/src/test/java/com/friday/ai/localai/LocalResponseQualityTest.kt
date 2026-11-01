package com.friday.ai.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResponseQualityTest {
    @Test fun `rejects echoes markers and repeated lines`() {
        assertTrue(LocalResponseQuality.checked("quem é você?", "Quem é você?\nIncluído de vocês").contains("resposta repetida"))
        assertTrue(LocalResponseQuality.checked("Olá", "Olá.").contains("resposta repetida"))
        assertTrue(LocalResponseQuality.checked("oi", "Resposta <|im_start|>user").contains("resposta repetida"))
        assertTrue(LocalResponseQuality.checked("oi", "frase repetida\nfrase repetida\nfrase repetida").contains("resposta repetida"))
    }
    @Test fun `preserves normal answers`() {
        assertEquals("Sou Friday.", LocalResponseQuality.checked("quem é você?", " Sou Friday. "))
        assertEquals("Olá! Como posso ajudar?", LocalResponseQuality.checked("Olá", "Olá! Como posso ajudar?"))
    }
}
