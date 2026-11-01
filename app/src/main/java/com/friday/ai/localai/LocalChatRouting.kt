package com.friday.ai.localai

import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole

/** Local mode never silently falls back to cloud. */
object LocalChatRouting {
    fun useLocal(mode: LocalAiPreferences.Mode?): Boolean = mode == LocalAiPreferences.Mode.LOCAL
}

/** Bounded conversation text; the native library applies the model's chat template. */
object LocalPrompt {
    const val SYSTEM_PROMPT = """
        Você é Friday, uma assistente local neste celular.
        Responda em português do Brasil, com respostas curtas e honestas.
        Use o histórico como contexto e responda somente à última pergunta.
        Não repita a pergunta nem invente outras falas do usuário.
        Não diga que pesquisou na internet, não invente dados atuais e não execute ações.
        Quando não souber ou não puder fazer algo, diga isso claramente.
    """

    fun userTurn(prompt: String): String = prompt.trim().take(MAX_PROMPT_CHARS)

    fun conversationTurn(prompt: String, history: List<Message>): String {
        val current = userTurn(prompt).take(MAX_CURRENT_CHARS)
        val previous = if (history.lastOrNull()?.let {
            it.role == MessageRole.USER && it.content.trim() == prompt.trim()
        } == true) history.dropLast(1) else history
        val recent = previous.filter { it.role != MessageRole.SYSTEM && it.content.isNotBlank() }
            .takeLast(MAX_HISTORY_MESSAGES)
        if (recent.isEmpty()) return current
        val lastTurn = "Usuário: $current\nFriday:"
        val budget = MAX_CONVERSATION_CHARS - lastTurn.length - 1
        val context = recent.joinToString("\n") {
            val role = if (it.role == MessageRole.USER) "Usuário" else "Friday"
            "$role: ${it.content.trim().take(MAX_HISTORY_MESSAGE_CHARS)}"
        }.takeLast(budget.coerceAtLeast(0))
        return "$context\n$lastTurn"
    }

    private const val MAX_PROMPT_CHARS = 3_000
    private const val MAX_CURRENT_CHARS = 1_200
    private const val MAX_CONVERSATION_CHARS = 1_800
    private const val MAX_HISTORY_MESSAGE_CHARS = 240
    private const val MAX_HISTORY_MESSAGES = 4
}
