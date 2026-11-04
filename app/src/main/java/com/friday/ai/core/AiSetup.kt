package com.friday.ai.core

/** Persisted choices shared by onboarding, settings and network boundaries. */
@Suppress("MagicNumber")
object AiSetup {
    const val PREF_COMPLETE = "ai_setup_complete"
    const val PREF_MODE = "ai_mode"
    const val MODE_LOCAL = "LOCAL"
    const val MODE_GROQ = "GROQ"

    fun validGroqKey(value: String): Boolean {
        val key = value.trim()
        return key.startsWith("gsk_") && key.length >= 20 && key.none(Char::isWhitespace)
    }

    fun cloudAllowed(complete: Boolean, mode: String?): Boolean =
        complete && mode == MODE_GROQ
}
