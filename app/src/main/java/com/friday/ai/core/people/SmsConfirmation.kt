package com.friday.ai.core.people

import com.friday.ai.core.mail.Confirmation

/** Sends an SMS from the phone itself; [com.friday.ai.service.messages.AndroidSms] on a real one. */
interface SmsLine {

    sealed interface Result {
        data object Sent : Result
        /** Android reported a failure: no signal, radio off, refused by the carrier… */
        data class Failed(val reason: String) : Result
        /** Handed to the radio, but no report came back in time. */
        data object Unconfirmed : Result
    }

    /** SEND_SMS granted and the phone can send messages at all. */
    fun canSend(): Boolean

    suspend fun send(number: String, text: String): Result
}

/**
 * "Отправить SMS для Фердавса: «я свободен»?" — and only a yes sends it.
 *
 * A spoken message goes out in the owner's name; a misheard name or word is
 * cheap to fix before and impossible after. So the text and the person are
 * read back, a yes sends, a no cancels, and anything else drops the draft —
 * a "да" said later in another context can never send it. The reply says
 * "отправлено" only when Android reports the message sent.
 */
class SmsConfirmation(
    private val line: SmsLine,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** An answer comes within this, or the draft is gone. */
        const val TTL_MS = 60_000L
    }

    private class Draft(val name: String, val number: String, val text: String, val at: Long)

    @Volatile private var draft: Draft? = null

    /** Whether messages can be sent directly; otherwise the SMS app is opened as before. */
    val available: Boolean get() = line.canSend()

    /** Holds the message and asks. */
    fun ask(name: String, number: String, text: String, russian: Boolean): String {
        draft = Draft(name, number, text, clock())
        return if (russian) "Отправить SMS для «$name»: «$text»? Да или нет."
        else "Send an SMS to $name: \"$text\"? Yes or no."
    }

    /** The answer to [ask], carried out; null when nothing was asked or [text] is a new request. */
    suspend fun answer(text: String, russian: Boolean): String? {
        val d = draft ?: return null
        draft = null
        if (clock() - d.at > TTL_MS) return null
        return when (Confirmation.classify(text)) {
            Confirmation.Answer.YES -> report(line.send(d.number, d.text), d.name, russian)
            Confirmation.Answer.NO -> if (russian) "Не отправляю." else "Not sending it."
            Confirmation.Answer.OTHER -> null
        }
    }

    private fun report(result: SmsLine.Result, name: String, russian: Boolean): String = when (result) {
        SmsLine.Result.Sent -> if (russian) "SMS для «$name» отправлено." else "SMS to $name sent."
        is SmsLine.Result.Failed ->
            if (russian) "SMS не ушло: ${result.reason}." else "The SMS didn't go: ${result.reason}."
        SmsLine.Result.Unconfirmed ->
            if (russian) "SMS передано телефону, но подтверждения отправки нет — проверьте в «Сообщениях»."
            else "The SMS was handed to the phone, but it didn't confirm sending — check Messages."
    }
}
