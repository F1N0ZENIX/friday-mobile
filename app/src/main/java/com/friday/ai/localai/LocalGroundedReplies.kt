package com.friday.ai.localai

import java.text.Normalizer
import java.time.Clock
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Small, deterministic answers for facts that a local model must not guess.
 * Returning null deliberately leaves ordinary conversation to the selected model.
 */
object LocalGroundedReplies {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun reply(prompt: String, clock: Clock = Clock.systemDefaultZone()): String? {
        LocalArithmetic.reply(prompt)?.let { return it }
        val question = normalize(prompt)
        return when {
            question in identityQuestions -> "Sou a Friday, uma assistente de IA local neste celular."
            question in modelQuestions ->
                "Uso o modelo local selecionado nas configurações de IA local. Você pode conferir o nome lá."
            question in greetings -> "Olá! Como posso ajudar?"
            isWeatherQuestion(question) -> "Não tenho acesso ao clima em tempo real."
            isClockQuestion(question) -> "Agora são ${LocalTime.now(clock).format(timeFormatter)}."
            isAlarmCommand(question) -> "Não consigo criar alarmes ou temporizadores no modo local."
            else -> null
        }
    }

    private fun isWeatherQuestion(question: String): Boolean = question in weatherQuestions

    private fun isClockQuestion(question: String): Boolean = question in clockQuestions

    private fun isAlarmCommand(question: String): Boolean =
        alarmCommands.any(question::startsWith)

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .trim()
            .trimEnd { !it.isLetterOrDigit() }
            .trim()

    private val identityQuestions = setOf(
        "quem e voce",
        "o que e voce",
        "qual e a sua identidade"
    )
    private val modelQuestions = setOf(
        "qual modelo voce usa",
        "qual seu modelo de ia",
        "qual e seu modelo de ia",
        "que modelo voce usa",
        "qual modelo esta usando",
        "que modelo esta usando"
    )
    private val greetings = setOf("oi", "ola", "bom dia", "boa tarde", "boa noite")
    private val weatherQuestions = setOf(
        "como esta o tempo hoje",
        "qual a previsao do tempo hoje",
        "vai chover hoje",
        "como esta o clima hoje"
    )
    private val clockQuestions = setOf("que horas sao", "que horas sao agora", "qual a hora agora")
    private val alarmCommands = setOf(
        "crie um alarme",
        "criar um alarme",
        "defina um alarme",
        "coloque um alarme",
        "ative um alarme",
        "inicie um temporizador",
        "iniciar um temporizador",
        "crie um temporizador",
        "criar um temporizador",
        "defina um temporizador",
        "coloque um temporizador"
    )
    private val COMBINING_MARKS = "\\p{M}+".toRegex()
}
