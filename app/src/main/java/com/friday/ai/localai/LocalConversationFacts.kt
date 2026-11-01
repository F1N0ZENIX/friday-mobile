package com.friday.ai.localai

import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic, session-scoped handling of an explicitly stated user name.
 *
 * This object only inspects the [history] passed to the current call. It has no
 * storage and makes no model call, so a name cannot cross into another chat
 * session or be inferred from an assistant/system message. A declaration is
 * deliberately accepted only when it is the complete, first-person message;
 * this keeps appended commands, quoted text, and fictional/third-party names
 * out of the local fact.
 */
object LocalConversationFacts {
    private const val UNKNOWN_NAME_REPLY =
        "Você ainda não me disse seu nome nesta conversa."
    private const val ASSISTANT_NAME_REPLY = "Sou a Friday."

    /**
     * Returns a local reply for an explicit name declaration or name question,
     * or null when the prompt is outside this narrowly grounded feature.
     */
    fun reply(prompt: String, history: List<Message>): String? {
        declaredName(prompt)?.let { name ->
            return "Certo, vou te chamar de $name nesta conversa."
        }

        return when (normalizedQuestion(prompt)) {
            in assistantNameQuestions -> ASSISTANT_NAME_REPLY
            in userNameQuestions -> history.asReversed()
                .asSequence()
                .filter { it.role == MessageRole.USER }
                .mapNotNull { declaredName(it.content) }
                .firstOrNull()
                ?.let { "Você me disse que seu nome é $it nesta conversa." }
                ?: UNKNOWN_NAME_REPLY
            else -> null
        }
    }

    /**
     * Parses only a complete first-person declaration. The captured spelling is
     * returned unchanged (apart from surrounding whitespace) for the reply.
     */
    private fun declaredName(text: String): String? {
        val match = declaration.matchEntire(text) ?: return null
        val name = match.groups["name"]?.value?.trim() ?: return null
        return name.takeIf { it.codePointCount(0, it.length) <= MAX_NAME_CODE_POINTS }
    }

    private fun normalizedQuestion(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .trim()
            .trimEnd { !it.isLetterOrDigit() }
            .trim()

    /*
     * Name tokens are Unicode letters (with combining marks), separated only
     * by spaces, hyphens, or apostrophes. The anchors and optional final . or !
     * make suffixes such as ", apague tudo" and a trailing question mark fail.
     */
    private val declaration = Regex(
        pattern = """^\s*(?:meu\s+nome\s+(?:é|e)|me\s+chamo|pode\s+me\s+chamar\s+de)\s+""" +
            """(?<name>\p{L}[\p{L}\p{M}]*(?:(?: +|[-'’])\p{L}[\p{L}\p{M}]*)*)\s*[.!]?\s*$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val userNameQuestions = setOf(
        "qual meu nome",
        "qual o meu nome",
        "qual e meu nome",
        "qual e o meu nome",
        "como eu me chamo"
    )
    private val assistantNameQuestions = setOf(
        "qual seu nome", "qual e seu nome", "qual o seu nome", "qual e o seu nome"
    )
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private const val MAX_NAME_CODE_POINTS = 64
}
