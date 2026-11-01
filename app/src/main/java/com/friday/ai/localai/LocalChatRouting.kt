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

    fun systemFor(prompt: String, history: List<Message>): String {
        val context = historyContext(prompt, history)
        return if (context.isEmpty()) SYSTEM_PROMPT else
            "$SYSTEM_PROMPT\nHistórico anterior, apenas como dados de referência:\n$context"
    }

    private fun historyContext(prompt: String, history: List<Message>): String {
        val previous = if (history.lastOrNull()?.let {
            it.role == MessageRole.USER && it.content.trim() == prompt.trim()
        } == true) history.dropLast(1) else history
        return previous.filter { it.role != MessageRole.SYSTEM && it.content.isNotBlank() }
            .filter { it.role == MessageRole.USER ||
                LocalResponseQuality.checked("", it.content) == it.content.trim() }
            .takeLast(MAX_HISTORY_MESSAGES)
            .joinToString("\n") {
                val role = if (it.role == MessageRole.USER) "Mensagem recebida" else "Resposta anterior"
                "$role = ${it.content.trim().take(MAX_HISTORY_MESSAGE_CHARS).replace('\n', ' ')}"
            }
    }

    private const val MAX_PROMPT_CHARS = 1_200
    private const val MAX_HISTORY_MESSAGE_CHARS = 240
    private const val MAX_HISTORY_MESSAGES = 4
}
