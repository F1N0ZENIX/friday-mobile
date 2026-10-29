// Long lines on purpose: prompt text; a line break here would change what the model is sent.
@file:Suppress("MaxLineLength")

package com.friday.ai.core

import com.friday.ai.domain.model.AssistantMode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * @param deviceContext where the user is and what the phone has (see
 *   [DeviceContext]); added to prompts that carry tools, so the model decides
 *   details — which app, which way to message — from facts, not habit.
 */
class SystemPromptBuilder(private val deviceContext: (() -> String)? = null) {

    internal companion object {
        /**
         * Modelled on J.A.R.V.I.S. and F.R.I.D.A.Y.: composed, quick, dryly
         * witty, and loyal enough to disagree out loud. She is female — the
         * voice is, and every fixed phrase in the app is in the feminine — so
         * the model must use feminine forms about herself or the two clash.
         */
        const val BASE_PERSONALITY = """Você é Friday, uma assistente pessoal de inteligência artificial, inspirada na F.R.I.D.A.Y. do Homem de Ferro. Responda SEMPRE em português brasileiro (pt-BR), mesmo se o histórico, as ferramentas, o sistema ou a mensagem recebida estiverem em russo ou inglês. Nunca use russo nas respostas. Seja objetiva, educada, natural e precisa, com humor sutil quando apropriado. Você tem voz feminina: ao falar de si mesma, use sempre formas femininas, como 'estou pronta' e 'fui configurada'. Não invente informações nem afirme ter realizado ações sem confirmação. Respeite as preferências e o contexto da conversa. Quando receber resultados de ferramentas em outros idiomas, traduza-os para português brasileiro antes de responder."""

        /**
         * Without this the model happily writes essays. Applies to the text
         * chat; the voice path tightens it further to a single sentence.
         */
        /**
         * The model cannot act. Phone commands are recognised and carried out
         * before it is ever asked; a request that reaches it was not. Without
         * this, "поставь будильник в 8 утра" — a phrasing the alarm pattern
         * once missed — got "Будильник поставлен" and no alarm.
         */
        const val ACTION_HONESTY = """Você não pode controlar o celular apenas por esta conversa. Alarmes, temporizadores, chamadas, mensagens, e-mails, eventos, lembretes, configurações e aplicativos são tratados pelo sistema de comandos da Friday antes de você receber a solicitação. Se um pedido para EXECUTAR uma dessas ações chegou até você, a ação NÃO foi realizada. Nunca diga que concluiu, configurou ou enviou algo sem confirmação. Explique brevemente que não conseguiu executar desta vez e peça para a pessoa reformular o comando, sem inventar uma frase específica. Perguntas sobre como fazer algo não são pedidos para executar ações: responda normalmente."""

        /**
         * The same honesty, when the model has tools. It may now act, but
         * only through a tool, and it reports what the tool said happened —
         * "Часы открыты, сохраните" is not "будильник поставлен".
         */
        const val TOOL_RULES = """Você só pode agir no celular chamando as ferramentas disponíveis. Quando a pessoa pedir uma ação coberta por ferramenta, use-a. Não peça confirmação, salvo se faltar um dado indispensável. Pedidos com várias ações exigem uma chamada para CADA ação, de preferência juntas. Antes de responder, verifique se cada ação mencionada tem um resultado de ferramenta. Se não tiver, chame a ferramenta correspondente. Nunca afirme que uma ação foi concluída, configurada ou enviada sem confirmação explícita da ferramenta. Descreva exatamente o resultado: se um aplicativo foi apenas aberto, não diga que a ação foi finalizada; se houve erro, informe a falha. Se as ferramentas disponíveis forem insuficientes para controlar o celular, chame use_phone. Se não houver ferramenta adequada, explique a limitação. Use o contexto do aparelho, a memória e a conversa para resolver detalhes sem perguntas desnecessárias; pergunte apenas diante de ambiguidade real. Use calculate para cálculos. Para notícias, preços, placares, lançamentos e fatos atuais incertos, use web_search e cite a origem. Traduza resultados de ferramentas para português brasileiro. Responda perguntas explicativas normalmente, sem tentar executar ações."""

        const val BREVITY_RULE = """Seja breve. Por padrão, responda em uma a três frases curtas. Evite repetir a pergunta, acrescentar avisos desnecessários ou fazer listas, a menos que a pessoa peça detalhes. Quando a resposta estiver completa, encerre."""
    }

    /** @param withTools true when the request carries [com.friday.ai.agent.AgentTools]. */
    fun build(mode: AssistantMode, memoryContext: String = "", withTools: Boolean = false): String = buildString {
        appendLine(BASE_PERSONALITY)
        appendLine()
        appendLine(BREVITY_RULE)
        appendLine()
        appendLine(if (withTools) TOOL_RULES else ACTION_HONESTY)
        if (withTools) {
            deviceContext?.let { describe ->
                runCatching(describe).getOrNull()?.let {
                    appendLine()
                    appendLine(it)
                }
            }
        }
        appendLine()
        appendLine(currentDateTime(ZonedDateTime.now()))
        appendLine()

        if (mode != AssistantMode.DEFAULT) {
            appendLine("Modo ativo: ${mode.emoji} ${mode.displayName}")
            appendLine("Comportamento: ${mode.description}")
            appendLine()
        }

        if (memoryContext.isNotBlank()) {
            append(memoryContext)
        }
    }

    /**
     * The user's local moment, spelled out.
     *
     * The weekday is written in rather than left to the model: language
     * models are unreliable at working out the day of the week from a date,
     * and "в пятницу" or "next Monday" then resolve to the wrong day. The zone
     * is there so times the model mentions are read as local ones.
     */
    internal fun currentDateTime(now: ZonedDateTime): String {
        val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR"))
        return "Data e hora locais atuais: ${now.format(dateTimeFormat)} ($weekday), " +
            "fuso horário ${now.zone.id} (UTC$offset). Interprete os horários informados pela pessoa neste fuso."
    }

    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
