package com.friday.ai.core.audio

import java.util.Locale

/**
 * Ends a turn by what was *said*, not only by how loud the room is.
 *
 * The on-device decoder (Vosk, already loaded for the wake word) follows the
 * command as it is spoken and closes an utterance when its acoustic model
 * hears the speech stop — noise of a fan or a street is "silence" to it, not
 * words. That close is a candidate end; the words decide how long to wait
 * after it:
 *
 *  - a phrase that cannot end where it stopped — "включи музыку и…",
 *    "напомни мне в…" — waits [OPEN_PHRASE_MS] for the rest;
 *  - a longer request gets [LONG_PHRASE_MS]: room for a short pause to think;
 *  - a short complete one ends after [SHORT_PHRASE_MS].
 *
 * Any new words heard meanwhile cancel the candidate. Groq Whisper still
 * makes the transcript; this only decides when to stop recording, without a
 * request per half-second.
 */
class SpokenEnd {

    companion object {
        const val SHORT_PHRASE_MS = 350L
        const val LONG_PHRASE_MS = 800L
        const val OPEN_PHRASE_MS = 2_500L

        /** From this many words a request is "long". */
        private const val LONG_WORDS = 4

        /**
         * Words a sentence does not end on: conjunctions, prepositions and
         * the like, Russian and English.
         */
        private val CONTINUES = setOf(
            "и", "а", "но", "или", "либо", "что", "чтобы", "потом", "затем", "ещё", "еще", "также", "если",
            "в", "во", "на", "с", "со", "к", "ко", "по", "за", "из", "от", "до", "для", "о", "об", "про",
            "у", "под", "над", "при", "через", "между", "после", "перед", "около", "мне", "ему", "ей",
            "and", "or", "but", "then", "to", "the", "a", "an", "of", "for", "with", "in", "on", "at",
            "from", "about", "my", "me", "if", "that"
        )
    }

    /** Words of the utterances closed so far. */
    private val words = mutableListOf<String>()

    /** When the decoder last closed an utterance with words in it; null while words are coming. */
    private var closedAt: Long? = null

    /** The decoder closed an utterance at [atMs] (time into the recording) with [text]. */
    fun onFinal(text: String, atMs: Long) {
        val heard = split(text)
        if (heard.isEmpty()) return
        words += heard
        closedAt = atMs
    }

    /** The decoder's running guess; any words in it mean the owner is still talking. */
    fun onPartial(text: String) {
        if (split(text).isNotEmpty()) closedAt = null
    }

    /** Whether the turn is over at [nowMs]. */
    fun finished(nowMs: Long): Boolean {
        val closed = closedAt ?: return false
        return nowMs - closed >= patience()
    }

    /** What was said, as the decoder heard it (for the log's word count only). */
    val wordCount: Int get() = words.size

    private fun patience(): Long = when {
        words.lastOrNull() in CONTINUES -> OPEN_PHRASE_MS
        words.size >= LONG_WORDS -> LONG_PHRASE_MS
        else -> SHORT_PHRASE_MS
    }

    private fun split(text: String): List<String> =
        text.lowercase(Locale.ROOT).split(' ').map { it.trim() }.filter { it.isNotEmpty() }
}
