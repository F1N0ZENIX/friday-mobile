package com.friday.ai.core.mail

import com.friday.ai.core.BriefComposer

/**
 * What Friday says about mail, kept short enough to be listened to.
 *
 * Names stay in the nominative ("Иван Петров — «Встреча»") rather than being
 * forced into "от …": declining an arbitrary sender's name is a guess, and a
 * wrong case sounds worse than a list.
 */
object MailSpeech {

    /** How many senders are named before the rest are just counted. */
    const val NAMED = 3

    fun unread(total: Int, newest: List<GmailMessages.Mail>, russian: Boolean): String {
        if (total <= 0 || newest.isEmpty()) return if (russian) "Новых писем нет." else "Nenhum e-mail novo."
        val count = maxOf(total, newest.size)
        val head = if (russian) {
            val noun = BriefComposer.pluralRu(
                count, "непрочитанное письмо", "непрочитанных письма", "непрочитанных писем"
            )
            "$count $noun."
        } else {
            "$count ${if (count == 1) "e-mail não lido" else "e-mails não lidos"}."
        }
        val named = newest.take(NAMED).joinToString(" ") { line(it) }
        val rest = count - minOf(NAMED, newest.size)
        val tail = when {
            rest <= 0 -> ""
            russian -> " И ещё $rest."
            else -> " E mais $rest."
        }
        return "$head $named$tail"
    }

    private fun line(m: GmailMessages.Mail): String {
        val subject = m.subject.ifBlank { m.snippet.take(SUBJECT_FALLBACK) }
        return if (subject.isBlank()) "${m.fromName}." else "${m.fromName} — «$subject»."
    }

    /** Opening of a read-out: who and about what. */
    fun header(m: GmailMessages.Mail, russian: Boolean): String {
        val subject = m.subject.takeIf { it.isNotBlank() }?.let { "«$it»" }
        return when {
            subject == null && russian -> "${m.fromName} пишет."
            subject == null -> "${m.fromName} escreveu."
            russian -> "${m.fromName} пишет: $subject."
            else -> "${m.fromName} escreveu: $subject."
        }
    }

    fun confirmReply(name: String, subject: String, body: String, russian: Boolean): String =
        if (russian) "Ответ на «$subject» ($name): «$body». Отправить?"
        else "Resposta para $name sobre «$subject»: «$body». Enviar?"

    fun confirmNew(name: String, address: String, subject: String, body: String, russian: Boolean): String =
        if (russian) "Новое письмо: $name, $address. Тема «$subject». Текст: «$body». Отправить?"
        else "Novo e-mail para $name ($address). Assunto: «$subject». Mensagem: «$body». Enviar?"

    private const val SUBJECT_FALLBACK = 60
}
