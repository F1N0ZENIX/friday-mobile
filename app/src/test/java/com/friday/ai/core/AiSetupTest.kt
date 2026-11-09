package com.friday.ai.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSetupTest {
    @Test
    fun `cloud needs completed setup and a non-local mode`() {
        assertFalse(AiSetup.cloudAllowed(false, AiSetup.MODE_GROQ))
        assertFalse(AiSetup.cloudAllowed(true, AiSetup.MODE_LOCAL))
        assertTrue(AiSetup.cloudAllowed(true, AiSetup.MODE_GROQ))
    }

    @Test
    fun `Groq key validation is format only`() {
        assertTrue(AiSetup.validGroqKey("gsk_1234567890123456"))
        assertFalse(AiSetup.validGroqKey("gsk_short"))
        assertFalse(AiSetup.validGroqKey("gsk_123456789012 3456"))
        assertFalse(AiSetup.validGroqKey("not-a-groq-key"))
    }
}
