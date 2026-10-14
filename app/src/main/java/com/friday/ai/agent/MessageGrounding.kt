package com.friday.ai.agent

import com.friday.ai.core.ContactMatcher

/**
 * Whether the text the model wants to send is something the owner said.
 *
 * Asked to send "Это сообщение было отправлено с помощью моего голосового
 * помощника", the model called send_message with «я свободен» — the text of
 * a message from earlier in the conversation. A person writing a message for
 * someone uses the words they were just given; so does Friday. A text whose
 * words are mostly not in the request is refused back to the model, which
 * then takes the words from the request.
 */
object MessageGrounding {

    /** Share of the message's words that must appear in what was said. */
    private const val MIN_SHARE = 0.6

    /** Short words ("я", "в", "на") prove nothing either way. */
    private const val MIN_WORD = 3

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun isOwnWords(text: String, said: String): Boolean {
        val heard = said.split(SPLIT).filter { it.length >= MIN_WORD }.map { ContactMatcher.stem(it) }.toSet()
        val words = text.split(SPLIT).filter { it.length >= MIN_WORD }.map { ContactMatcher.stem(it) }
        if (words.isEmpty()) return said.contains(text.trim(), ignoreCase = true) || text.isBlank()
        return words.count { it in heard }.toDouble() / words.size >= MIN_SHARE
    }

    fun refusal(said: String): String =
        "Error: \"text\" must be the message the user dictated in this request, in their own words — " +
            "not an earlier message. Their request was: \"$said\". Nothing was sent; call again with that text."
}
