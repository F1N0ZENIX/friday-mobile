package com.friday.ai.core.people

import com.friday.ai.core.mail.Confirmation
import com.friday.ai.core.mail.GmailMessages
import com.friday.ai.core.mail.MailSpeech
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortugueseSpeechTest {

    @Test
    fun `confirmation accepts Portuguese and prioritizes refusal`() {
        assertEquals(Confirmation.Answer.YES, Confirmation.classify("Sim, pode enviar"))
        assertEquals(Confirmation.Answer.YES, Confirmation.classify("Confirmo"))
        assertEquals(Confirmation.Answer.NO, Confirmation.classify("Não, cancele"))
        assertEquals(Confirmation.Answer.NO, Confirmation.classify("Nao envie"))
        assertEquals(Confirmation.Answer.NO, Confirmation.classify("Sim, não"))
        assertEquals(Confirmation.Answer.OTHER, Confirmation.classify("Sim, e depois envie outra mensagem"))
        assertEquals(Confirmation.Answer.OTHER, Confirmation.classify("Pode enviar para outra pessoa"))
    }

    private class FakeSms : SmsLine {
        val sent = mutableListOf<Pair<String, String>>()
        override fun canSend() = true
        override suspend fun send(number: String, text: String): SmsLine.Result {
            sent += number to text
            return SmsLine.Result.Sent
        }
    }

    @Test
    fun `Portuguese SMS needs an explicit confirmation and sends only once`() = runTest {
        val phone = FakeSms()
        val sms = SmsConfirmation(phone) { 100L }
        assertEquals(
            "Enviar SMS para «Ana»: «Chego às 8»? Sim ou não.",
            sms.ask("Ana", "5511999999999", "Chego às 8", russian = false)
        )
        assertTrue(phone.sent.isEmpty())
        assertEquals("SMS para «Ana» enviado.", sms.answer("sim", russian = false))
        assertEquals(listOf("5511999999999" to "Chego às 8"), phone.sent)
        assertNull(sms.answer("sim", russian = false))
    }

    @Test
    fun `Portuguese SMS refusal never sends`() = runTest {
        val phone = FakeSms()
        val sms = SmsConfirmation(phone) { 100L }
        sms.ask("Ana", "5511999999999", "Teste", russian = false)
        assertEquals("Não vou enviar.", sms.answer("não", russian = false))
        assertTrue(phone.sent.isEmpty())
    }

    @Test
    fun `Portuguese email previews retain recipient and body`() {
        val mail = GmailMessages.Mail(
            id = "1", threadId = "1", fromName = "Ana",
            fromAddress = "ana@example.com", subject = "Reunião",
            snippet = "", messageId = null, references = null, body = null
        )
        assertEquals("Nenhum e-mail novo.", MailSpeech.unread(0, emptyList(), russian = false))
        assertTrue(MailSpeech.unread(1, listOf(mail), russian = false).contains("1 e-mail não lido"))
        assertEquals("Ana escreveu: «Reunião».", MailSpeech.header(mail, russian = false))
        assertEquals(
            "Resposta para Ana sobre «Reunião»: «Tudo certo». Enviar?",
            MailSpeech.confirmReply("Ana", "Reunião", "Tudo certo", russian = false)
        )
        assertEquals(
            "Novo e-mail para Ana (ana@example.com). Assunto: «Oi». Mensagem: «Olá». Enviar?",
            MailSpeech.confirmNew("Ana", "ana@example.com", "Oi", "Olá", russian = false)
        )
    }
}
