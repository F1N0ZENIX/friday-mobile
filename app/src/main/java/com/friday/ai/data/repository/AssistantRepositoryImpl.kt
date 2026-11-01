package com.friday.ai.data.repository

import com.friday.ai.agent.FridayAgent
import com.friday.ai.agent.PromptBudget
import com.friday.ai.core.SystemPromptBuilder
import com.friday.ai.data.local.dao.ChatMessageDao
import com.friday.ai.data.local.dao.ChatSessionSummary
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.mapper.toDomain
import com.friday.ai.data.local.mapper.toEntity
import com.friday.ai.data.remote.GroqApiException
import com.friday.ai.data.remote.GroqApiService
import com.friday.ai.data.remote.dto.ApiMessage
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import com.friday.ai.domain.repository.AssistantRepository
import com.friday.ai.localai.LocalAiPreferences
import com.friday.ai.localai.LocalChatRouting
import com.friday.ai.localai.LocalGroundedReplies
import com.friday.ai.localai.LocalInferenceEngine
import com.friday.ai.localai.LocalPrompt
import com.friday.ai.localai.LocalResponseQuality
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.ModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion

@Suppress("LongParameterList")
class AssistantRepositoryImpl(private val groqApi: GroqApiService, private val chatDao: ChatMessageDao, private val prefDao: UserPreferenceDao, private val promptBuilder: SystemPromptBuilder, private val memory: FridayMemory, private val catalog: ModelCatalog, private val agent: FridayAgent, private val localPreferences: LocalAiPreferences? = null, private val localEngine: LocalInferenceEngine? = null) : AssistantRepository {
    companion object { const val PREF_API_KEY = "groq_api_key" }
    override suspend fun sendMessage(prompt: String, history: List<Message>, mode: AssistantMode): Flow<String> {
        if (LocalChatRouting.useLocal(localPreferences?.mode)) {
            val engine = localEngine ?: return flow { throw IllegalStateException("A IA local não está disponível neste aparelho.") }
            return flow { val answer = LocalGroundedReplies.reply(prompt) ?: engine.reply(LocalPrompt.conversationTurn(prompt, history)); emit(LocalResponseQuality.checked(prompt, answer)) }
        }
        val apiKey = prefDao.get(PREF_API_KEY) ?: ""
        val model = catalog.model(com.friday.ai.core.GroqModels.Role.CHAT)
        val memoryContext = memory.buildMemoryContext()
        val apiMessages = buildApiMessages(prompt, history, mode, memoryContext)
        val responseBuilder = StringBuilder()
        val russian = prompt.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }
        val backup = catalog.model(com.friday.ai.core.GroqModels.Role.FAST)
        val settings = FridayAgent.Settings(apiKey, model, GroqApiService.DEFAULT_MAX_TOKENS, backup)
        return agent.reply(settings, apiMessages, russian).catch { e -> if (e is GroqApiException && e.modelMissing) catalog.refresh(); throw e }.onCompletion { val response = responseBuilder.toString(); if (response.isNotBlank()) { memory.logInteraction(prompt, response, null); memory.extractMemoriesInBackground(prompt, response) } }.map { token -> responseBuilder.append(token); token }
    }
    override fun getSessionMessages(sessionId: String): Flow<List<Message>> = chatDao.observeSession(sessionId).map { entities -> entities.map { it.toDomain() } }
    override fun getSessions(): Flow<List<ChatSessionSummary>> = chatDao.observeSessions()
    override suspend fun saveMessage(message: Message) { chatDao.insert(message.toEntity()) }
    override suspend fun deleteSession(sessionId: String) { chatDao.deleteSession(sessionId) }
    override suspend fun analyzeImage(imageBase64: String, prompt: String): Flow<String> { val apiKey = prefDao.get(PREF_API_KEY) ?: ""; val model = catalog.model(com.friday.ai.core.GroqModels.Role.VISION); return groqApi.analyzeImage(apiKey, imageBase64, prompt, model) }
    override suspend fun analyzeText(text: String, prompt: String): Flow<String> { val apiKey = prefDao.get(PREF_API_KEY) ?: ""; return groqApi.analyzeText(apiKey, text, prompt, catalog.model(com.friday.ai.core.GroqModels.Role.CHAT)) }
    private fun buildApiMessages(prompt: String, history: List<Message>, mode: AssistantMode, memoryContext: String): List<ApiMessage> = buildList { add(ApiMessage(role = "system", content = promptBuilder.build(mode, memoryContext, withTools = true))); val recent = history.takeLast(PromptBudget.CHAT_HISTORY); recent.forEachIndexed { i, msg -> add(ApiMessage(role = when (msg.role) { MessageRole.USER -> "user"; MessageRole.ASSISTANT -> "assistant"; MessageRole.SYSTEM -> "system" }, content = PromptBudget.older(msg.content, fromEnd = recent.size - i))) }; add(ApiMessage(role = "user", content = prompt)) }
}
