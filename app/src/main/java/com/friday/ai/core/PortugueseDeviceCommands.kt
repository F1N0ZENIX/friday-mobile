package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import java.text.Normalizer

/** Explicit device requests, handled before any local or online language model. */
object PortugueseDeviceCommands {
    private const val SECONDS_PER_MINUTE = 60L
    private const val MAX_TIMER_SECONDS = 2_147_483_647L
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59
    private const val ALARM_HOUR_GROUP = 1
    private const val ALARM_MINUTE_COLON_GROUP = 2
    private const val ALARM_MINUTE_H_GROUP = 3

    private val timer = Regex(
        "^(?:(?:defina|definir|crie|criar|coloque|colocar|inicie|iniciar|ative|ativar)\\s+)?" +
            "(?:um\\s+)?(?:alarme|temporizador|timer)(?:\\s+(?:para|pra|por))?" +
            "\\s+(?:daqui\\s+a\\s+|em\\s+)?(\\d+)\\s*(minutos?|min|segundos?|s)\\s*[.!]?$"
    )
    private val alarm = Regex(
        "^(?:(?:defina|definir|crie|criar|coloque|colocar|ative|ativar)\\s+)?" +
            "(?:um\\s+)?alarme\\s+(?:para\\s+|pra\\s+)?(?:as\\s+)?(\\d{1,2})" +
            "(?::(\\d{2})|h(\\d{2})?)?\\s*[.!]?$"
    )
    private val flashlight = Regex(
        "^(ligue|ligar|ative|ativar|acenda|desligue|desligar|apague)\\s+(?:a\\s+)?lanterna[.!]?$"
    )
    private val open = Regex("^(?:abra|abrir|abre)\\s+(?:o\\s+|a\\s+)?(.+?)[.!]?$")

    fun route(text: String): CommandResult? {
        val normal = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trim()
        return routeTimer(normal)
            ?: routeAlarm(normal)
            ?: routeFlashlight(normal)
            ?: routeTime(normal)
            ?: routeOpenApp(normal)
    }

    private fun routeTimer(text: String): CommandResult? = timer.matchEntire(text)?.let {
        val value = it.groupValues[1].toLongOrNull() ?: return null
        if (value !in 1..MAX_TIMER_SECONDS) return null
        val seconds = value * if (it.groupValues[2].startsWith("min")) SECONDS_PER_MINUTE else 1L
        if (seconds in 1..MAX_TIMER_SECONDS) CommandResult.SetTimer(seconds.toInt(), null) else null
    }

    private fun routeAlarm(text: String): CommandResult? = alarm.matchEntire(text)?.let {
        val hour = it.groupValues[ALARM_HOUR_GROUP].toInt()
        val minute = it.groupValues[ALARM_MINUTE_COLON_GROUP]
            .ifBlank { it.groupValues[ALARM_MINUTE_H_GROUP] }
            .ifBlank { "0" }
            .toInt()
        if (hour in 0..MAX_HOUR && minute in 0..MAX_MINUTE) CommandResult.SetAlarm(hour, minute, null) else null
    }

    private fun routeFlashlight(text: String): CommandResult? = flashlight.matchEntire(text)?.let {
        CommandResult.Flashlight(it.groupValues[1] !in listOf("desligue", "desligar", "apague"))
    }

    private fun routeTime(text: String): CommandResult? {
        val timeQueries = listOf("que horas sao", "que horas sao?", "qual a hora", "qual a hora?")
        return if (text in timeQueries) CommandResult.TellTime else null
    }

    private fun routeOpenApp(text: String): CommandResult? = open.matchEntire(text)?.let {
        val name = it.groupValues[1]
        CommandResult.OpenApp(name, com.friday.ai.core.apps.AppMatcher.aliasFor(name))
    }
}
