package com.friday.ai.domain.usecase

import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.repository.AssistantRepository
import kotlinx.coroutines.flow.Flow

class SendMessageUseCase(private val repository: AssistantRepository) {

    suspend operator fun invoke(
        prompt: String,
        history: List<Message>,
        mode: AssistantMode,
        research: Boolean = false
    ): Flow<String> {
        return repository.sendMessage(prompt, history, mode, research)
    }

    suspend fun saveMessage(message: Message) {
        repository.saveMessage(message)
    }
}
