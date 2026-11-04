package com.friday.ai.core.capabilities

/**
 * A plain-text report for working out what went wrong: version, every
 * diagnostics line, and the app's recent log.
 *
 * Redacted before it leaves the phone, always: API keys, phone numbers,
 * e-mail addresses and anything quoted (message text, what was said, names
 * in tool arguments) are replaced. What's left is which part of Friday did
 * what and when, which is what a fix needs.
 */
object DiagnosticReport {

    private val redactions = listOf(
        Regex("gsk_[A-Za-z0-9]+") to "[chave]",
        Regex("(?:enc:v1:)[A-Za-z0-9+/=]+") to "[criptografado]",
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "[email]",
        Regex("«[^»]*»") to "«…»",
        Regex("\"(?:[^\"\\\\]|\\\\.){2,}\"") to "\"…\""
    )

    /** Digits with separators; a phone number only when there are enough digits (log timestamps have fewer). */
    private val digitRun = Regex("\\+?\\d[\\d\\s()-]{6,}\\d")
    private const val PHONE_DIGITS = 9

    fun redact(line: String): String {
        val masked = redactions.fold(line) { text, (pattern, replacement) -> pattern.replace(text, replacement) }
        return digitRun.replace(masked) { m ->
            if (m.value.count(Char::isDigit) >= PHONE_DIGITS) "[número]" else m.value
        }
    }

    fun build(version: String, device: String, rows: List<Diagnostics.Row>, log: List<String>): String = buildString {
        appendLine("Friday $version · $device")
        appendLine()
        appendLine("== Diagnóstico ==")
        rows.forEach { appendLine("[${it.status}] ${it.group.title} · ${it.title}: ${it.detail}") }
        appendLine()
        appendLine("== Registro (${log.size} linhas, sem chaves, números nem texto) ==")
        log.forEach { appendLine(redact(it)) }
    }
}
