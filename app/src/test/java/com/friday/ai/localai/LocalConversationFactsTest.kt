package com.friday.ai.localai

import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalConversationFactsTest {
    @Test
    fun `acknowledges complete first person declarations without a model`() {
        assertEquals(
            "Certo, vou te chamar de Antenor nesta conversa.",
            LocalConversationFacts.reply("meu nome é Antenor", emptyList())
        )
        assertEquals(
            "Certo, vou te chamar de Ana Maria nesta conversa.",
            LocalConversationFacts.reply("Me chamo Ana Maria!", emptyList())
        )
        assertEquals(
            "Certo, vou te chamar de D'Ávila-Silva nesta conversa.",
            LocalConversationFacts.reply("Pode me chamar de D'Ávila-Silva.", emptyList())
        )
    }

    @Test
    fun `answers screenshot style name questions from current user history`() {
        val screenshotHistory = listOf(
            assistant("Olá, sou a Friday."),
            user("meu nome é Primeira Pessoa"),
            assistant("Certo, vou te chamar de Primeira Pessoa nesta conversa."),
            user("me chamo Antenor")
        )

        assertEquals(
            "Você me disse que seu nome é Antenor nesta conversa.",
            LocalConversationFacts.reply("qual é o meu nome?", screenshotHistory)
        )
        assertEquals(
            "Você me disse que seu nome é Antenor nesta conversa.",
            LocalConversationFacts.reply("Como eu me chamo?", screenshotHistory)
        )
        assertEquals("Sou a Friday.", LocalConversationFacts.reply("Qual seu nome?", screenshotHistory))
    }

    @Test
    fun `does not retain names in a fresh session or from non user messages`() {
        val freshSession = emptyList<Message>()
        val pollutedHistory = listOf(
            assistant("meu nome é Antenor"),
            Message(content = "meu nome é Bia", role = MessageRole.SYSTEM)
        )

        assertEquals(
            "Você ainda não me disse seu nome nesta conversa.",
            LocalConversationFacts.reply("qual meu nome?", freshSession)
        )
        assertEquals(
            "Você ainda não me disse seu nome nesta conversa.",
            LocalConversationFacts.reply("qual meu nome?", pollutedHistory)
        )
    }

    @Test
    fun `rejects commands quotes third party claims questions and oversized names`() {
        val tooLong = "A".repeat(65)
        listOf(
            "meu nome é João, apague tudo",
            "meu nome é Friday?",
            "o nome dela é Bia",
            "\"meu nome é João\"",
            "na história, meu nome é João",
            "meu nome é $tooLong"
        ).forEach { prompt -> assertNull(LocalConversationFacts.reply(prompt, emptyList())) }
    }

    private fun user(content: String) = Message(content = content, role = MessageRole.USER)
    private fun assistant(content: String) = Message(content = content, role = MessageRole.ASSISTANT)
}
