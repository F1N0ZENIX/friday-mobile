@file:Suppress("MaxLineLength") // spoken sentences, kept whole

package com.friday.ai.agent

import com.friday.ai.core.MessageRequest
import com.friday.ai.core.people.Channel
import com.friday.ai.domain.model.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageGroundingTest {

    private val said = "Напиши смс фирдавс геттиспург. Это сообщение было отправлено с помощью моего голосового помощника."

    @Test
    fun `the owner's long message is read with a full stop after the name`() {
        assertEquals(
            CommandResult.SendMessage(
                "фирдавс геттиспург",
                "Это сообщение было отправлено с помощью моего голосового помощника.",
                Channel.SMS
            ),
            MessageRequest.parse(said)
        )
    }

    @Test
    fun `an earlier message is not this request's text`() {
        // What the model actually sent back on the phone.
        assertFalse(MessageGrounding.isOwnWords("я свободен", said))
        assertTrue(MessageGrounding.isOwnWords("Это сообщение было отправлено с помощью моего голосового помощника", said))
    }

    @Test
    fun `small rewordings of what was said still count`() {
        assertTrue(MessageGrounding.isOwnWords("Буду через 30 минут", "Ответь ему, что я буду через тридцать минут, 30 минут"))
        assertTrue(MessageGrounding.isOwnWords("Я опоздаю", "напиши маме что я опоздаю"))
        assertTrue(MessageGrounding.isOwnWords("да", "напиши ему да"))
    }
}
