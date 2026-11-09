package com.friday.ai.core.memory

/** Matches complete normalized recall questions, never writes or quoted examples. */
internal object SavedWordQuestion {
    private const val WORD = "(?:qual(?: e| foi)?(?: a)?|que) palavra"
    private const val ACTION = "(?:salvar|guardar|memorizar)"
    private const val RECIPIENT = "(?:(?:vc|voce) )?"
    private const val MEMORY = "(?: na(?: sua)? memoria)?"
    private val patterns = listOf(
        Regex("$WORD(?: que)?(?: eu)? pedi (?:pra|para) $RECIPIENT$ACTION$MEMORY"),
        Regex("$WORD(?: foi)?(?: a)? (?:salva|guardada|memorizada)$MEMORY"),
        Regex("$WORD(?: que)? (?:vc|voce) (?:salvou|guardou|memorizou)$MEMORY"),
        Regex(
            "(?:vc|voce) lembra (?:qual(?: e)?|da) palavra(?: que)?(?: eu)? pedi " +
                "(?:pra|para) $RECIPIENT$ACTION$MEMORY"
        )
    )
    private val prefix = Regex("^(?:friday[,!]?[ ]+)?(?:por favor[, ]+)?")
    private val suffix = Regex("[, ]+por favor$")

    fun matches(normalized: String): Boolean {
        val question = normalized.replaceFirst(prefix, "").replace(suffix, "")
        return patterns.any { it.matches(question) }
    }
}
