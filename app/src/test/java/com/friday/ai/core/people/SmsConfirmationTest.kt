package com.friday.ai.core.people

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsConfirmationTest {

    private class FakeLine(var result: SmsLine.Result = SmsLine.Result.Sent, var granted: Boolean = true) : SmsLine {
        val sent = mutableListOf<Pair<String, String>>()
        override fun canSend() = granted
        override suspend fun send(number: String, text: String): SmsLine.Result {
            sent += number to text
            return result
        }
    }

    private var now = 0L
    private val line = FakeLine()
    private val sms = SmsConfirmation(line) { now }

    @Test
    fun `the message is read back and goes only after a yes`() = runTest {
        assertEquals(
            "Отправить SMS для «Firdavs Gettysburg»: «я свободен»? Да или нет.",
            sms.ask("Firdavs Gettysburg", "+998901234567", "я свободен", russian = true)
        )
        assertTrue("nothing goes before the answer", line.sent.isEmpty())
        assertEquals("SMS для «Firdavs Gettysburg» отправлено.", sms.answer("Да, отправляй", russian = true))
        assertEquals(listOf("+998901234567" to "я свободен"), line.sent)
        assertNull("one yes sends once", sms.answer("да", russian = true))
        assertEquals(1, line.sent.size)
    }

    @Test
    fun `a no cancels, and anything else drops the draft`() = runTest {
        sms.ask("Мама", "+7900", "буду в восемь", russian = true)
        assertEquals("Не отправляю.", sms.answer("нет", russian = true))
        sms.ask("Мама", "+7900", "буду в восемь", russian = true)
        assertNull("a new request is not an answer", sms.answer("какая завтра погода", russian = true))
        assertNull("and a yes after it sends nothing", sms.answer("да", russian = true))
        assertTrue(line.sent.isEmpty())
    }

    @Test
    fun `a late yes sends nothing`() = runTest {
        sms.ask("Мама", "+7900", "буду в восемь", russian = true)
        now += SmsConfirmation.TTL_MS + 1
        assertNull(sms.answer("да", russian = true))
        assertTrue(line.sent.isEmpty())
    }

    @Test
    fun `sent is said only when android reports it sent`() = runTest {
        line.result = SmsLine.Result.Failed("нет сети")
        sms.ask("Мама", "+7900", "x", russian = true)
        assertEquals("SMS не ушло: нет сети.", sms.answer("да", russian = true))

        line.result = SmsLine.Result.Unconfirmed
        sms.ask("Мама", "+7900", "x", russian = true)
        assertTrue(sms.answer("да", russian = true)!!.contains("подтверждения отправки нет"))
    }

    @Test
    fun `without the permission it is not offered`() {
        line.granted = false
        assertFalse(sms.available)
    }
}
