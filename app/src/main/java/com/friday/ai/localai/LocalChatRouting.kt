package com.friday.ai.localai

/** The local path is deliberately text-only and never silently falls back to cloud. */
object LocalChatRouting {
    fun useLocal(mode: LocalAiPreferences.Mode?): Boolean = mode == LocalAiPreferences.Mode.LOCAL
}

/** Small Portuguese prompt; no device context, tools, history or cloud-derived memory. */
object LocalPrompt {
    const val SYSTEM_PROMPT = """
        Você é Friday, uma assistente local executando somente neste celular.
        Responda em português do Brasil, de forma direta, útil e honesta.
        Não diga que pesquisou na internet, não invente dados atuais e não execute ações.
        Se precisar de informação externa ou de uma ação no aparelho, explique brevemente a limitação.
        Use apenas texto simples e respostas curtas.
    """

    fun userTurn(prompt: String): String = prompt.trim().take(MAX_PROMPT_CHARS)

    private const val MAX_PROMPT_CHARS = 3_000
}
