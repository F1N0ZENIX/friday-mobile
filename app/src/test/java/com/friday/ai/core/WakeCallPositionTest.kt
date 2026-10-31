package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCallPositionTest {
    @Test fun `Friday at the start is a wake call`() {
        assertTrue(WakePhrases.isWakeCall("friday"))
        assertTrue(WakePhrases.isWakeCall("friday abra a câmera"))
    }
    @Test fun `one attention word before Friday is accepted`() {
        assertTrue(WakePhrases.isWakeCall("oi friday"))
        assertTrue(WakePhrases.isWakeCall("okay friday"))
    }
    @Test fun `two attention words are not accepted`() {
        assertFalse(WakePhrases.isWakeCall("oi okay friday"))
    }
    @Test fun `ordinary speech does not wake the assistant`() {
        assertFalse(WakePhrases.isWakeCall("qual é a previsão do tempo"))
        assertFalse(WakePhrases.isWakeCall(""))
    }
    @Test fun `wake call language is Brazilian Portuguese`() {
        assertEquals(WakePhrases.Language.PORTUGUESE, WakePhrases.detectLanguage("friday"))
        assertNull(WakePhrases.detectLanguage("bom dia"))
    }
}
