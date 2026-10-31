package com.friday.ai.core

/** Detects when the user addresses Friday using Brazilian Portuguese speech recognition. */
object WakePhrases {
    private val FRIDAY_NAMES = listOf("friday", "fraide", "fráide", "fradi")
    private val ATTENTION_PREFIXES = listOf("ok", "okay", "ei", "hey", "oi")

    private val ALL_NAMES = FRIDAY_NAMES.sortedByDescending { it.length }

    fun grammarVocabulary(): List<String> = (ALL_NAMES + ATTENTION_PREFIXES).distinct()

    fun isWakeCall(heard: String): Boolean = nameIndex(normalize(heard)) != null

    fun nameIndexIn(words: List<String>): Int? = nameIndex(words.map { it.lowercase().trim() })

    private fun nameIndex(words: List<String>): Int? {
        if (words.isEmpty()) return null
        if (isName(words[0])) return 0
        if (words.size >= 2 && words[0] in ATTENTION_PREFIXES && isName(words[1])) return 1
        return null
    }

    private fun isName(word: String): Boolean = ALL_NAMES.any { word.startsWith(it) }

    fun detectLanguage(heard: String): Language? =
        if (nameIndex(normalize(heard)) != null) Language.PORTUGUESE else null

    enum class Language { PORTUGUESE, ENGLISH }

    private val leadingCall = Regex(
        "^\\s*(?:(?:ok|okay|ei|hey|oi)[\\s,!.]+)?(?:friday|fraide|fráide|fradi)[\\s,!.:;—–-]*",
        RegexOption.IGNORE_CASE
    )

    fun stripCall(transcript: String): String = transcript.replaceFirst(leadingCall, "").trim()

    private fun normalize(heard: String): List<String> =
        heard.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
}
