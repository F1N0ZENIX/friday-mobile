package com.friday.ai.core.people

import com.friday.ai.core.MessageRequest
import com.friday.ai.domain.model.CommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextTest {

    @Test
    fun `the owner's case - a surname and 'что' are not part of the message`() {
        // "Отправь сообщение Фирдавс, Геттиспург, что я свободен." as the parser split it.
        val parsed = MessageRequest.parse("Отправь сообщение Фирдавс, Геттиспург, что я свободен.")
        assertEquals(CommandResult.SendMessage("Фирдавс", "Геттиспург, что я свободен.", null), parsed)
        assertEquals("я свободен.", MessageText.tidy("Геттиспург, что я свободен.", "Firdavs Gettysburg"))
    }

    @Test
    fun `the surname in the text says which of the namesakes is meant`() {
        val both = listOf(
            Person("Firdavs Yakubov", emptyList()),
            Person("Firdavs Gettysburg", emptyList()),
            Person("Фирдавс ака", emptyList())
        )
        assertEquals("Firdavs Gettysburg", PeopleDirectory.byName("Геттиспург,", both)?.name)
        assertNull("an ordinary first word picks nobody", PeopleDirectory.byName("буду", both))
        assertNull("a number is not 'the second one' here", PeopleDirectory.byName("2", both))
    }

    @Test
    fun `an ordinary message is left as it was said`() {
        assertEquals("буду в пять", MessageText.tidy("буду в пять", "Мама"))
        assertEquals("привет, как дела?", MessageText.tidy("привет, как дела?", "Firdavs Gettysburg"))
        assertEquals("опоздаю на 10 минут", MessageText.tidy("что опоздаю на 10 минут", "Мама"))
        assertEquals(
            "a comma after the name still separates the text",
            CommandResult.SendMessage("маме", "буду в пять", Channel.SMS),
            MessageRequest.parse("напиши смс маме, буду в пять")
        )
    }

    @Test
    fun `a message is never tidied away entirely`() {
        assertEquals("Гетисбург", MessageText.tidy("Гетисбург", "Firdavs Gettysburg"))
    }

    @Test
    fun `an unanswered question is dropped and a later yes sends nothing`() = runTest {
        val line = object : SmsLine {
            val sent = mutableListOf<String>()
            override fun canSend() = true
            override suspend fun send(number: String, text: String): SmsLine.Result {
                sent += text
                return SmsLine.Result.Sent
            }
        }
        val sms = SmsConfirmation(line)
        sms.ask("Мама", "+7900", "буду в пять", russian = true)
        assertTrue("a draft was waiting", sms.drop())
        assertFalse("nothing is left to drop", sms.drop())
        assertNull(sms.answer("да", russian = true))
        assertTrue(line.sent.isEmpty())
    }
}
