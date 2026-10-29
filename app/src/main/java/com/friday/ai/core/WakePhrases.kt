package com.friday.ai.core

/**
 * Identifies a direct call to Friday. The pt-BR Vosk model is used for
 * offline recognition; historical Russian spellings remain accepted by
 * the pure text matcher for compatibility with old transcripts.
 *
 * The name must be at the beginning, optionally after one attention word,
 * to avoid waking on ordinary conversation.
 */
object WakePhrases {

    /** Spellings the Russian recogniser produces for the English "Friday". */
    private val ENGLISH_NAME = listOf(
        "фрайдей", "фрайди", "фрайде", "фрайд", "фрэйди", "фрейди",
        "прайдей", "прайди", "friday"
    )

    /** Common spellings produced by Brazilian Portuguese transcription. */
    private val PORTUGUESE_NAME = listOf(
        "friday", "fraidei", "fráidei", "fraide", "fráide", "fridei", "fridai"
    )

    /** The Russian name (kept for historical transcripts). */
    private val RUSSIAN_NAME = listOf(
        "пятница", "пятницы", "пятнице", "пятницу", "пятнеца", "пятниц"
    )

    /** Optional politeness before the name: "окей пятница", "hey friday". */
    private val ATTENTION_PREFIXES = listOf(
        "ok", "okay", "ei", "oi", "olá", "ola", "fala", "hey", "hi",
        "окей", "оке", "ок", "привет", "слушай", "эй"
    )


    /** Every accepted name, longest first so "фрайдей" wins over "фрай". */
    private val ALL_NAMES: List<String> =
        (PORTUGUESE_NAME + ENGLISH_NAME + RUSSIAN_NAME).distinct().sortedByDescending { it.length }

    /**
     * Closed grammar for the Brazilian Portuguese Vosk model. Never feed the
     * Cyrillic legacy aliases into a Portuguese acoustic model.
     * Spellings that are not in a model's lexicon are ignored by Vosk.
     */
    fun grammarVocabulary(): List<String> =
        (PORTUGUESE_NAME + listOf("ei", "oi", "olá", "ola", "fala", "ok", "okay"))
            .distinct()

    /**
     * True when [heard] is the user calling Friday.
     *
     * @param heard text from the recogniser, already lower-cased or not.
     */
    fun isWakeCall(heard: String): Boolean = nameIndex(normalize(heard)) != null

    /** Which of the decoder's [words] is the name, or null if they are not a wake call. */
    fun nameIndexIn(words: List<String>): Int? = nameIndex(words.map { it.lowercase().trim() })

    /**
     * Index of the word that is the name, or null if this isn't a wake call.
     *
     * The name must open the utterance, or follow a single attention word.
     * It is tempting to relax this — a hesitation before the name really does
     * get the call rejected — but it cannot be done at this layer. Grammar
     * decoding collapses everything outside the grammar to `[unk]`, so
     * "ээ, пятница" and "…в пятницу" both arrive as `[unk] пятница`. There is
     * no information left to tell a call apart from talk about the weekday,
     * and waking up in the middle of someone's sentence is the worse failure.
     *
     * What actually fixed the missed calls was upstream, in [VoiceGate]:
     * feeding the decoder the start of the word and clearing its state only
     * between utterances, so a real call arrives with the name in front.
     */
    private fun nameIndex(words: List<String>): Int? {
        if (words.isEmpty()) return null
        if (isName(words[0])) return 0
        if (words.size >= 2 && words[0] in ATTENTION_PREFIXES && isName(words[1])) return 1
        return null
    }

    // startsWith rather than equality: the recogniser often glues a case
    // ending on ("пятницу") or trails off mid-word.
    private fun isName(word: String): Boolean = ALL_NAMES.any { word.startsWith(it) }

    /**
     * Which language the user addressed Friday in, or null if it wasn't a
     * wake call. Lets the reply match the language the user chose.
     */
    fun detectLanguage(heard: String): Language? {
        val words = normalize(heard)
        val candidate = nameIndex(words)?.let { words[it] } ?: return null
        return when {
            RUSSIAN_NAME.any { candidate.startsWith(it) } -> Language.RUSSIAN
            else -> Language.ENGLISH
        }
    }

    enum class Language { RUSSIAN, ENGLISH }

    private val leadingCall = Regex(
        "^\\s*(?:(?:окей|оке|ок|привет|слушай|эй|hey|ok|okay|hi)[\\s,!.]+)?" +
            "(?:пятниц\\p{L}*|пятнец\\p{L}*|фрайд\\p{L}*|фрэйди|фрейди|friday)[\\s,!.:;—–-]*",
        RegexOption.IGNORE_CASE
    )

    /**
     * [transcript] without the call in front: "Пятница, включи музыку" →
     * "включи музыку". When the command was said in the same breath as the
     * name, the recording starts with the name too.
     */
    fun stripCall(transcript: String): String = transcript.replaceFirst(leadingCall, "").trim()

    private fun normalize(heard: String): List<String> =
        heard.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
}
