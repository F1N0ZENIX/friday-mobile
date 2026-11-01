package com.friday.ai.localai

import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
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
    fun `conversation keeps bounded history and does not repeat current user turn`() {
        val history = buildList {
            repeat(5) { index ->
                add(Message(role = MessageRole.USER, content = "pergunta $index"))
                add(Message(role = MessageRole.ASSISTANT, content = "resposta $index"))
            }
            add(Message(role = MessageRole.USER, content = "agora"))
        }

        val prompt = LocalPrompt.conversationTurn("agora", history)

        assertEquals(1, Regex("Usuário: agora").findAll(prompt).count())
        assertFalse(prompt.contains("pergunta 0"))
        assertTrue(prompt.contains("pergunta 4"))
        assertTrue(prompt.contains("resposta 4"))
    }

    @Test
    fun `conversation history is bounded to 3000 characters`() {
        val history = List(8) { index ->
            Message(role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, content = "x".repeat(2_400))
        }

        val prompt = LocalPrompt.conversationTurn("oi", history)

        assertTrue(prompt.length <= 3_000)
        assertTrue(prompt.endsWith("Usuário: oi\nFriday:"))
    }

    @Test
    fun `long current message cannot overflow conversation budget`() {
        val history = listOf(
            Message(role = MessageRole.SYSTEM, content = "system secret"),
            Message(role = MessageRole.USER, content = "pergunta"),
            Message(role = MessageRole.ASSISTANT, content = "resposta")
        )
        val prompt = LocalPrompt.conversationTurn("z".repeat(4_000), history)
        assertTrue(prompt.length <= 1_800)
        assertFalse(prompt.contains("system secret"))
        assertTrue(prompt.contains("resposta"))
    }

    @Test
    fun `local limits remain conservative`() {
        assertEquals(1_024, LocalInferenceLimits.CONTEXT_TOKENS)
        assertEquals(128, LocalInferenceLimits.MAX_REPLY_TOKENS)
        assertEquals(4, LocalInferenceLimits.MAX_CPU_THREADS)
    }
}
