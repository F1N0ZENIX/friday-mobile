package com.friday.ai.localai

import android.content.Context

/** Persistent choices for the local / hybrid / cloud intelligence modes. */
class LocalAiPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("friday_local_ai", Context.MODE_PRIVATE)

    enum class Mode { AUTOMATIC, LOCAL, GROQ }

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, Mode.AUTOMATIC.name)!!) }
            .getOrDefault(Mode.AUTOMATIC)
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    var allowGroqFallback: Boolean
        get() = prefs.getBoolean(KEY_FALLBACK, true)
        set(value) { prefs.edit().putBoolean(KEY_FALLBACK, value).apply() }

    var ramBudgetMb: Int
        get() = prefs.getInt(KEY_RAM_BUDGET, 500).coerceIn(128, 1024)
        set(value) { prefs.edit().putInt(KEY_RAM_BUDGET, value.coerceIn(128, 1024)).apply() }

    var contextTokens: Int
        get() = prefs.getInt(KEY_CONTEXT, 1024).coerceIn(256, 4096)
        set(value) { prefs.edit().putInt(KEY_CONTEXT, value.coerceIn(256, 4096)).apply() }

    private companion object {
        const val KEY_MODE = "ai_mode"
        const val KEY_FALLBACK = "allow_groq_fallback"
        const val KEY_RAM_BUDGET = "ram_budget_mb"
        const val KEY_CONTEXT = "context_tokens"
    }
}
