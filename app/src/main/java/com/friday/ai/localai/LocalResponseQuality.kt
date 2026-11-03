package com.friday.ai.localai

import java.text.Normalizer
import java.util.Locale

/** Reject obvious echoes instead of showing a broken completion as a real answer. */
object LocalResponseQuality {
    fun checked(prompt: String, answer: String): String {
        val text = answer.trim()
        val invalidContent = text.isEmpty() || hasInvalidContent(prompt, text)
        return if (invalidContent || (normalized(prompt).isNotEmpty() &&
                normalized(text.lineSequence().firstOrNull().orEmpty()) == normalized(prompt))) rejection() else text
    }

    private fun hasInvalidContent(prompt: String, text: String): Boolean =
        leaksTurns(text) && !asksForDialogue(prompt) ||
            echoesInternalInstructions(text) ||
            hasUnexpectedCyrillic(prompt, text) ||
            repeatsLine(text) ||
            repeatsSentence(text) ||
            repeatsTokenSequence(prompt, text)

    /**
     * A grounded answer must retain at least two concrete terms from the supplied search data.
     * This rejects generic descriptions of a portal while allowing a source-derived summary.
     */
    fun isRelevantToSources(answer: String, sources: LocalInternetReply.SearchSources): Boolean {
        val sourceTerms = sources.items.flatMap { item -> groundedContentTerms("${item.title} ${item.snippet}") }.toSet()
        if (sourceTerms.isEmpty()) return false
        return groundedContentTerms(answer).toSet().intersect(sourceTerms).size >= MIN_GROUNDED_TERM_MATCHES
    }

    private fun leaksTurns(text: String): Boolean =
        CHAT_MARKERS.any(text::contains) || TURN_LABEL.find(text) != null

    private fun asksForDialogue(prompt: String): Boolean =
        DIALOGUE_REQUEST.containsMatchIn(normalized(prompt))

    private fun hasUnexpectedCyrillic(prompt: String, text: String): Boolean =
        CYRILLIC.containsMatchIn(text) && !CYRILLIC.containsMatchIn(prompt)

    private fun repeatsLine(text: String): Boolean {
        val lines = text.lines().map(::normalized).filter { it.length >= MIN_REPEATED_LENGTH }
        return lines.groupingBy { it }.eachCount().values.any { it >= MIN_REPEATED_LINES }
    }

    private fun repeatsSentence(text: String): Boolean {
        val sentences = text.split(SENTENCE_BOUNDARY)
            .map(::normalized)
            .filter { it.length >= MIN_REPEATED_LENGTH }
        return sentences.groupingBy { it }.eachCount().values.any { it > MAX_SAME_SENTENCES }
    }

    /**
     * Stops short looping completions such as "controle de controle de controle de".
     * Inspect only small adjacent token cycles so normal prose and arithmetic stay intact.
     */
    private fun repeatsTokenSequence(prompt: String, text: String): Boolean {
        if (asksForDialogue(prompt)) return false
        val tokens = normalized(text).split(' ').filter(String::isNotEmpty)
        for (sequenceSize in 1..MAX_REPEATED_SEQUENCE_TOKENS) {
            val repetitions = if (sequenceSize == 1) MIN_SINGLE_TOKEN_REPETITIONS else MIN_SEQUENCE_REPETITIONS
            val requiredTokens = sequenceSize * repetitions
            if (tokens.size < requiredTokens) continue
            for (start in 0..tokens.size - requiredTokens) {
                val sequence = tokens.subList(start, start + sequenceSize)
                if ((1 until repetitions).all { occurrence ->
                        tokens.subList(
                            start + occurrence * sequenceSize,
                            start + (occurrence + 1) * sequenceSize
                        ) == sequence
                    }
                ) return true
            }
        }
        return false
    }

    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .trim()

    private fun rejection(): String =
        "A resposta local travou em repetição e foi descartada. " +
            "Tente novamente ou reformule o pedido."

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SENTENCE_BOUNDARY = Regex("[.!?]+")
    private val CYRILLIC = Regex("\\p{IsCyrillic}")
    private val DIALOGUE_REQUEST = Regex("\\bdialogo\\b")
    private val TURN_LABEL = Regex(
        "(?im)^(?:usuario|usuário|user|assistant|sunday|uso)\\s*:"
    )
    private val CHAT_MARKERS = listOf("<|im_start|>", "<|im_end|>")
    private const val MIN_REPEATED_LENGTH = 8
    private const val MIN_REPEATED_LINES = 3
    private const val MAX_SAME_SENTENCES = 3
    private const val MAX_REPEATED_SEQUENCE_TOKENS = 4
    private const val MIN_SINGLE_TOKEN_REPETITIONS = 4
    private const val MIN_SEQUENCE_REPETITIONS = 3
    private const val MIN_GROUNDED_TERM_MATCHES = 2
}

/** Matches hidden local instructions without exposing the prompt to callers or logs. */
private fun echoesInternalInstructions(text: String): Boolean {
    val normalizedText = normalizeQualityText(text)
    return INTERNAL_INSTRUCTION_FRAGMENTS.count { normalizedText.contains(it) } >= 2
}

private fun groundedContentTerms(text: String): List<String> = normalizeQualityText(text).split(' ')
    .filter { it.length >= MIN_CONTENT_TERM_LENGTH && it !in GENERIC_TERMS }

private fun normalizeQualityText(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private val INTERNAL_INSTRUCTION_FRAGMENTS = listOf(
    "voce e friday uma assistente local neste celular",
    "responda em portugues do brasil",
    "use o historico como contexto",
    "responda somente a ultima pergunta",
    "nao repita a pergunta",
    "nao diga que pesquisou na internet",
    "nao invente dados atuais",
    "quando nao souber ou nao puder"
)
private val GENERIC_TERMS = setOf(
    "noticia", "noticias", "portal", "portais", "site", "sites", "pagina", "paginas",
    "internet", "busca", "pesquisa", "resultado", "resultados", "fonte", "fontes",
    "informacao", "informacoes", "atual", "atuais", "sobre", "para", "como", "mais"
)
private const val MIN_CONTENT_TERM_LENGTH = 4
