package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhrasesTest {
    @Test fun `Friday name wakes the assistant`() {
        listOf("friday", "Friday", "FRAIDAY", "fraide").forEach {
            assertTrue("'$it' should wake", WakePhrases.isWakeCall(it))
        }
    }
    @Test fun `wake word followed by a command still wakes`() {
        assertTrue(WakePhrases.isWakeCall("friday abre a câmera"))
        assertTrue(WakePhrases.isWakeCall("friday qual é a previsão"))
    }
    @Test fun `attention prefix is accepted`() {
        assertTrue(WakePhrases.isWakeCall("oi friday"))
        assertTrue(WakePhrases.isWakeCall("okay friday"))
        assertTrue(WakePhrases.isWakeCall("hey friday"))
    }
    @Test fun `weekday or unrelated speech does not wake`() {
        assertFalse(WakePhrases.isWakeCall("hoje é sexta-feira"))
        assertFalse(WakePhrases.isWakeCall("olá como vai"))
        assertFalse(WakePhrases.isWakeCall(""))
    }
    @Test fun `language is Brazilian Portuguese for wake calls`() {
        assertEquals(WakePhrases.Language.PORTUGUESE, WakePhrases.detectLanguage("friday abre a câmera"))
        assertNull(WakePhrases.detectLanguage("bom dia"))
    }
    @Test fun `grammar vocabulary contains supported wake words`() {
        val vocabulary = WakePhrases.grammarVocabulary()
        assertTrue(vocabulary.contains("friday"))
        assertTrue(vocabulary.contains("oi"))
        assertTrue(vocabulary.all { it.all { char -> char.isLetter() } })
    }
    @Test fun `unknown tokens do not wake`() {
        assertFalse(WakePhrases.isWakeCall("[unk]"))
        assertFalse(WakePhrases.isWakeCall("[unk] [unk] friday"))
    }
}
