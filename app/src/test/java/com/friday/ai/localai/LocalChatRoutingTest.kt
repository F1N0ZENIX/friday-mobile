package com.friday.ai.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatRoutingTest {
    @Test
    fun `only explicit local mode selects local runtime`() {
        assertTrue(LocalChatRouting.useLocal(LocalAiPreferences.Mode.LOCAL))
        assertFalse(LocalChatRouting.useLocal(LocalAiPreferences.Mode.AUTOMATIC))
        assertFalse(LocalChatRouting.useLocal(LocalAiPreferences.Mode.GROQ))
        assertFalse(LocalChatRouting.useLocal(null))
    }

    @Test
    fun `local prompt is Portuguese and has no tool instructions`() {
        assertTrue(LocalPrompt.SYSTEM_PROMPT.contains("português do Brasil"))
        assertTrue(LocalPrompt.SYSTEM_PROMPT.contains("Não diga que pesquisou na internet"))
        assertFalse(LocalPrompt.SYSTEM_PROMPT.contains("função"))
    }

    @Test
    fun `user turn is trimmed and bounded`() {
        assertEquals("oi", LocalPrompt.userTurn("  oi  "))
        assertEquals(3_000, LocalPrompt.userTurn("x".repeat(4_000)).length)
    }

    @Test
    fun `local limits remain conservative`() {
        assertEquals(1_024, LocalInferenceLimits.CONTEXT_TOKENS)
        assertEquals(128, LocalInferenceLimits.MAX_REPLY_TOKENS)
        assertEquals(4, LocalInferenceLimits.MAX_CPU_THREADS)
    }
}
