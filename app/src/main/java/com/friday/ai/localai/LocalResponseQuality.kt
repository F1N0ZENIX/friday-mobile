package com.friday.ai.localai

import java.text.Normalizer
import java.util.Locale

/** Reject obvious echoes instead of showing a broken completion as a real answer. */
object LocalResponseQuality {
    fun checked(prompt: String, answer: String): String {
        val text = answer.trim()
        val question = normalized(prompt)
        val firstLine = normalized(text.lineSequence().firstOrNull().orEmpty())
        val echoesQuestion = question.isNotEmpty() && firstLine == question
        val leaksTurns = TURN_MARKERS.any(text::contains)
        val invalidContent = text.isEmpty() || leaksTurns || repeatsLine(text)
        return if (echoesQuestion || invalidContent) {
            "O modelo local gerou uma resposta repetida ou inválida. " +
                "Tente reformular o pedido. Nenhuma ação foi executada."
        } else {
            text
        }
    }

    private fun repeatsLine(text: String): Boolean {
        val lines = text.lines().map(::normalized).filter { it.length >= MIN_REPEATED_LENGTH }
        return lines.size >= MIN_REPEATED_LINES && lines.distinct().size == 1
    }

    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .trim()

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val TURN_MARKERS = listOf("<|im_start|>", "<|im_end|>", "\nUsuário:")
    private const val MIN_REPEATED_LENGTH = 8
    private const val MIN_REPEATED_LINES = 3
}
