package com.friday.ai.core.memory

/** Explicit requests only: quoted examples and negated requests are never writes. */
internal object ExplicitMemoryIntent {
    private const val VERB = "(?:salv[ae]|salvar|guard[ae]|guardar|memorize|memoriza|memorizar|anote|anota)"
    private const val MEMORY = "(?:na|em sua|na sua|em minha|na minha)\\s+mem[oó]ria"
    private const val PREFIX = "^\\s*(?:Friday[,!]?[ ]+)?(?:por\\s+favor[, ]+)?" +
        "(?:(?:voc[eê]|vc)\\s+)?(?:(?:pode|poderia|consegue)\\s+)?"
    private const val SUFFIX = "(?:\\s*[,;]?\\s*(?:pra|para)\\s+testes?)?" +
        "(?:\\s*[,;]?\\s*por\\s+favor)?\\s*[!.]*\\s*$"
    private val options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val word = Regex(
        PREFIX + VERB + "\\s+a\\s+palavra(?:\\s+(?<value>.*?))?" +
            "(?:\\s+" + MEMORY + ")?" + SUFFIX,
        options
    )
    private val emptyWord = Regex(PREFIX + VERB + "\\s+a\\s+palavra\\s+" + MEMORY + SUFFIX, options)
    private val notes = listOf(
        Regex(PREFIX + VERB + "\\s+" + MEMORY + "\\s*[:,-]?\\s*(?:que\\s+)?(?<value>.+?)" + SUFFIX, options),
        Regex(PREFIX + VERB + "\\s+(?<value>.+?)\\s+" + MEMORY + SUFFIX, options),
        Regex(PREFIX + "(?:lembre|lembra|guarde|guarda|anote|anota)\\s+que\\s+(?<value>.+?)" + SUFFIX, options)
    )
    private val politeQuestion = Regex("\\b(?:pode|poderia|consegue)\\b", RegexOption.IGNORE_CASE)

    fun word(prompt: String): String? {
        val request = request(prompt) ?: return null
        return if (emptyWord.matches(request)) "" else capture(word, request)
    }

    fun note(prompt: String): String? {
        val request = request(prompt) ?: return null
        return notes.firstNotNullOfOrNull { capture(it, request) }?.takeIf { it.isNotBlank() }
    }

    private fun request(prompt: String): String? {
        val text = prompt.trim()
        if (!text.endsWith('?')) return text
        return if (politeQuestion.containsMatchIn(text)) text.dropLast(1).trimEnd() else null
    }

    private fun capture(pattern: Regex, text: String): String? =
        pattern.matchEntire(text)?.let { it.groups["value"]?.value.orEmpty().trim() }
}
