package com.friday.ai.localai

import java.text.Normalizer
import java.util.Locale

/** Reject obvious echoes instead of showing a broken completion as a real answer. */
object LocalResponseQuality {
    fun checked(prompt: String, answer: String): String {
        val text = answer.trim()
        val invalidContent = text.isEmpty() || hasInvalidContent(prompt, text)
        return if (invalidContent || echoesPrompt(prompt, text)) rejection() else text
    }

    private fun hasInvalidContent(prompt: String, text: String): Boolean =
        leaksTurns(text) && !asksForDialogue(prompt) ||
            hasUnexpectedCyrillic(prompt, text) ||
            repeatsLine(text) ||
            repeatsSentence(text)

    private fun echoesPrompt(prompt: String, text: String): Boolean {
        val question = normalized(prompt)
        val firstLine = normalized(text.lineSequence().firstOrNull().orEmpty())
        return question.isNotEmpty() && firstLine == question
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

    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .trim()

    private fun rejection(): String =
        "O modelo local gerou uma resposta repetida ou inválida. " +
            "Tente reformular o pedido. Nenhuma ação foi executada."

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
}
