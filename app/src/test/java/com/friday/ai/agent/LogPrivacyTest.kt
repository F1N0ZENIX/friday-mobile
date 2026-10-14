package com.friday.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogPrivacyTest {

    @Test
    fun `a tool call is logged without what was said or sent`() {
        val line = FridayAgent.toolLog("send_message", done = true)
        assertEquals("Tool send_message: done", line)
        listOf("маме", "опоздаю", "+998", "{", "contact", "text").forEach { assertFalse(it, it in line) }
        assertEquals("Tool call: not done", FridayAgent.toolLog("call", done = false))
    }
}
