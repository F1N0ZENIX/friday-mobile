package com.friday.ai.core.people

import com.friday.ai.core.ContactMatcher

/**
 * What a person would actually send, out of what the parser took for the
 * message.
 *
 * "Отправь сообщение Фирдавс, Геттиспург, что я свободен" was read as the
 * message «Геттиспург, что я свободен.» for Firdavs Gettysburg: Whisper put
 * a comma inside the name, and the surname and the "что" that introduces
 * the text ended up in it. Nobody sends that. Before a message is read back,
 * leading words that are the recipient's own name, and the "что"/"чтобы"
 * that only introduced the text, are taken off.
 */
object MessageText {

    private val INTRODUCERS = setOf("что", "чтобы", "мол", "типа", "that", "saying")
    private val PUNCTUATION = ",:;—-–".toSet()

    fun tidy(text: String, recipient: String): String {
        val nameWords = recipient.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        var words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        while (words.isNotEmpty()) {
            val bare = words.first().trim { it in PUNCTUATION || it == '.' }.lowercase()
            val isName = bare.isNotEmpty() && nameWords.any { sameName(bare, it) }
            if (bare.isEmpty() || isName || bare in INTRODUCERS) words = words.drop(1) else break
        }
        val tidied = words.joinToString(" ").trim().trimStart { it in PUNCTUATION }.trim()
        // Never tidy a message away entirely: what was said is better than nothing.
        return tidied.ifBlank { text.trim() }
    }

    /** The same name in either script, or spelled a letter or two apart ("Геттиспург"/"Gettysburg"). */
    private fun sameName(said: String, name: String): Boolean {
        val a = ContactMatcher.stem(said)
        val b = ContactMatcher.stem(name)
        if (a == b) return true
        val shorter = minOf(a.length, b.length)
        if (shorter < MIN_FUZZY) return false
        val allowed = if (shorter >= LONG_NAME) 2 else 1
        return kotlin.math.abs(a.length - b.length) <= allowed &&
            com.friday.ai.core.apps.Spelling.distance(a, b) <= allowed
    }

    private const val MIN_FUZZY = 5
    private const val LONG_NAME = 8
}
