package com.friday.ai.core.memory

import com.friday.ai.data.local.dao.MemoryDao
import com.friday.ai.data.local.entity.MemoryEntity
import com.friday.ai.localai.LocalConversationFacts
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small, deterministic, device-local memory facade.
 *
 * It deliberately recognizes only explicit Portuguese commands. It performs no
 * network, synchronization, model invocation, or inspection of chat history.
 */
class PersistentMemory(private val dao: MemoryDao) {

    @Suppress("ReturnCount") // Each recognized command finishes without falling through to another action.
    suspend fun reply(prompt: String): String? {
        val normalized = normalized(prompt)

        when {
            normalized in forgetNameCommands -> {
                writeMutex.withLock {
                    dao.getByCategory(FACT_CATEGORY)
                        .filter { it.key == NAME_KEY }
                        .forEach { dao.delete(it.id) }
                }
                return "Certo, apaguei seu nome salvo."
            }
            normalized.startsWith(DELETE_PREFIX) -> {
                val id = normalized.removePrefix(DELETE_PREFIX).trim().toLongOrNull() ?: return null
                val deleted = writeMutex.withLock {
                    if (dao.findById(id) == null) false
                    else {
                        dao.delete(id)
                        true
                    }
                }
                return if (deleted) "Memória $id apagada." else "Não encontrei uma memória com esse id."
            }
            normalized in listCommands -> return listMemories()
        }

        LocalConversationFacts.declaredName(prompt)?.let { name ->
            saveName(name)
            return "Certo, salvei seu nome como $name."
        }

        noteValue(prompt)?.let { note ->
            if (note.length > MAX_NOTE_CHARS) return "Não salvei: a nota deve ter no máximo 500 caracteres."
            writeMutex.withLock {
                dao.insert(
                    MemoryEntity(
                        category = FACT_CATEGORY,
                        key = "note_${UUID.randomUUID()}",
                        value = note,
                        source = EXPLICIT_LOCAL_SOURCE
                    )
                )
            }
            return "Certo, guardei essa nota."
        }

        return when (normalized) {
            in userNameQuestions -> dao.findByKey(NAME_KEY, FACT_CATEGORY)
                ?.let { "Seu nome salvo é ${it.value}." }
                ?: "Você ainda não me disse seu nome."
            in assistantNameQuestions -> null
            else -> null
        }
    }

    private suspend fun saveName(name: String) = writeMutex.withLock {
        val existing = dao.findByKey(NAME_KEY, FACT_CATEGORY)
        if (existing == null) {
            dao.insert(
                MemoryEntity(
                    category = FACT_CATEGORY,
                    key = NAME_KEY,
                    value = name,
                    source = EXPLICIT_LOCAL_SOURCE
                )
            )
        } else {
            dao.updateValue(existing.id, name)
        }
    }

    private suspend fun listMemories(): String {
        val memories = dao.getRecent(MAX_LIST)
        if (memories.isEmpty()) return "Não tenho memórias salvas."
        return buildString {
            append("Memórias salvas:")
            memories.forEach { memory ->
                append("\n[${memory.id}] ${memory.key}: ${memory.value}")
            }
        }
    }

    private fun noteValue(prompt: String): String? {
        val match = noteDeclaration.matchEntire(prompt) ?: return null
        return match.groups["note"]?.value?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .trim()
            .trimEnd { !it.isLetterOrDigit() }
            .trim()

    private companion object {
        val writeMutex = Mutex()
        const val FACT_CATEGORY = "fact"
        const val NAME_KEY = "name"
        const val EXPLICIT_LOCAL_SOURCE = "explicit_local"
        const val MAX_LIST = 20
        const val MAX_NOTE_CHARS = 500
        const val DELETE_PREFIX = "apagar memoria "
        val COMBINING_MARKS = Regex("\\p{M}+")
        val noteDeclaration = Regex(
            """^\s*(?:lembre|lembra|guarde)\s+que\s+(?<note>.*?)\s*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val userNameQuestions = setOf(
            "qual meu nome", "qual o meu nome", "qual e meu nome",
            "qual e o meu nome", "como eu me chamo"
        )
        val assistantNameQuestions = setOf(
            "qual seu nome", "qual e seu nome", "qual o seu nome", "qual e o seu nome"
        )
        val listCommands = setOf("listar memorias", "o que voce lembra de mim")
        val forgetNameCommands = setOf("esqueca meu nome", "apagar meu nome")
    }
}
