package com.friday.ai.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.CorrectionDetector
import com.friday.ai.core.FileAnalyzer
import com.friday.ai.core.VoiceInputManager
import com.friday.ai.data.local.dao.ChatSessionSummary
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.ChatUiState
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import com.friday.ai.domain.usecase.AnalyzeContentUseCase
import com.friday.ai.domain.usecase.GetChatHistoryUseCase
import com.friday.ai.domain.usecase.SendMessageUseCase
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.capture.ScreenCapture
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The chat screen.
 *
 * One request at a time: a send, a screen or a file while Friday is still
 * answering is refused here, not only by a disabled button. Each request
 * belongs to the conversation it began in, fixed when it starts — its
 * prompt and its answer are saved there and its stream is drawn only while
 * that conversation is the one on screen. Opening another conversation
 * lets the answer finish out of sight, in its own; deleting the
 * conversation cancels it, so nothing is written into a deleted thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LongParameterList") // Koin hands in each collaborator; grouping them would only hide that.
class ChatViewModel(
    private val sendMessage: SendMessageUseCase,
    private val getChatHistory: GetChatHistoryUseCase,
    private val analyzeContent: AnalyzeContentUseCase,
    private val voiceInput: VoiceInputManager,
    private val commands: CommandExecutor,
    private val memory: FridayMemory,
    private val files: FileAnalyzer? = null,
    private val screen: ScreenCapture? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    var onAnalyzeScreenRequested: (() -> Unit)? = null
    var onAnalyzeFileRequested: ((String?) -> Unit)? = null

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /** Past conversations for the history drawer. */
    val sessions: StateFlow<List<ChatSessionSummary>> = getChatHistory.sessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One request: where it belongs, the history it was asked with, its job
     * and the id of the answer being drawn. Touched on the main thread only.
     */
    private class Request(var sessionId: String?, val history: List<Message>) {
        var job: Job? = null
        var streamId: String? = null
    }

    private var active: Request? = null

    init {
        observeHistory()
        observeVoiceInput()
        resumeActiveSession()
    }

    /**
     * Opens whatever conversation is currently active — the same one the
     * voice assistant is using — so talking to Friday and then opening the
     * app lands you in the same thread rather than a blank screen.
     */
    private fun resumeActiveSession() {
        viewModelScope.launch {
            _currentSessionId.value = memory.getOrCreateSessionId()
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            _currentSessionId
                .filterNotNull()
                .flatMapLatest { sessionId -> getChatHistory.session(sessionId) }
                .catch { e -> _uiState.update { it.copy(error = e.message) } }
                .collect { messages ->
                    _uiState.update { it.copy(messages = messages + streamingOf(it.messages)) }
                }
        }
    }

    /** The answer still being drawn for the conversation on screen, kept across a database refresh. */
    private fun streamingOf(shown: List<Message>): List<Message> {
        val request = active ?: return emptyList()
        if (request.sessionId != _currentSessionId.value) return emptyList()
        return shown.filter { it.isStreaming && it.id == request.streamId }
    }

    /** Starts a fresh conversation, leaving the previous one in history. */
    fun onNewChat() {
        viewModelScope.launch {
            _uiState.update { it.copy(messages = emptyList(), error = null) }
            _currentSessionId.value = memory.startNewSession()
        }
    }

    /** Reopens a past conversation and makes it active for voice too. */
    fun onOpenSession(sessionId: String) {
        if (sessionId == _currentSessionId.value) return
        viewModelScope.launch {
            memory.resumeSession(sessionId)
            _currentSessionId.value = sessionId
            // An answer still being drawn belongs to the conversation left behind.
            _uiState.update { it.copy(messages = it.messages.filterNot { m -> m.isStreaming }) }
        }
    }

    fun onDeleteSession(sessionId: String) {
        // Its answer must not be written into a conversation that no longer exists.
        active?.takeIf { it.sessionId == sessionId }?.let(::abandon)
        viewModelScope.launch {
            getChatHistory.deleteSession(sessionId)
            if (sessionId == _currentSessionId.value) {
                _currentSessionId.value = memory.startNewSession()
            }
        }
    }

    private fun observeVoiceInput() {
        viewModelScope.launch {
            voiceInput.recognizedText.filterNotNull().collect { text ->
                _inputText.value = text
                voiceInput.clearResult()
            }
        }
        viewModelScope.launch {
            voiceInput.isListening.collect { listening ->
                _uiState.update { it.copy(isVoiceListening = listening) }
            }
        }
        viewModelScope.launch {
            voiceInput.error.filterNotNull().collect { err ->
                _uiState.update { it.copy(error = err) }
            }
        }
    }

    fun onInputChange(text: String) {
        _inputText.value = text
    }

    fun onSend() {
        val text = _inputText.value.trim()
        if (text.isBlank() || active != null) return
        _inputText.value = ""
        begin {
            // An answer to "Отправить?" is not a new request.
            val confirmed = commands.answerPending(text, isRussian(text))
            if (confirmed != null) {
                persist(text, MessageRole.USER)
                persist(confirmed, MessageRole.ASSISTANT)
            } else {
                dispatch(text)
            }
        }
    }

    /**
     * Carries out what was typed. Commands run through the same executor as
     * the voice service; plain conversation streams from the model here,
     * where the reply can be drawn as it arrives.
     */
    private suspend fun Request.dispatch(text: String) {
        val command = commands.route(text)
        // A silent quick link leaves nothing in the chat, not even what was typed.
        if (command is CommandResult.OpenLink && command.silent) {
            commands.execute(command, isRussian(text))
            return
        }
        persist(text, MessageRole.USER)
        if (command is CommandResult.ChatMessage) {
            reply(command.text)
            return
        }
        when (val outcome = commands.execute(command, isRussian(text))) {
            is CommandExecutor.Outcome.Reply -> persist(outcome.text, MessageRole.ASSISTANT)
            is CommandExecutor.Outcome.Conversation -> reply(outcome.text)
            CommandExecutor.Outcome.NeedsScreen -> onAnalyzeScreenRequested?.invoke()
            is CommandExecutor.Outcome.NeedsFile -> onAnalyzeFileRequested?.invoke(outcome.hint)
            CommandExecutor.Outcome.Silent -> Unit
        }
    }

    private fun isRussian(text: String): Boolean =
        text.any { it in 'а'..'я' || it in 'А'..'Я' }

    /** The owner answered the screen-capture consent screen. */
    fun onScreenConsent(resultCode: Int, data: Intent?) {
        val capture = screen ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            showError("Доступ к экрану не дан — анализ отменён")
            return
        }
        if (!begin {
                when (val shot = capture.capture(resultCode, data)) {
                    is ScreenCapture.Result.Image -> stream(analyzeContent.analyzeImage(shot.jpegBase64))
                    is ScreenCapture.Result.Failed -> fail(shot.reason)
                }
            }
        ) {
            busy()
        }
    }

    /** A file picked in the system picker; read off the main thread, within limits. */
    fun onFilePicked(uri: Uri) {
        val reader = files ?: return
        if (!begin { analyse(reader.read(uri)) }) busy()
    }

    private suspend fun Request.analyse(file: FileAnalyzer.Outcome) {
        when (file) {
            is FileAnalyzer.Outcome.Image ->
                stream(analyzeContent.analyzeImage(file.jpegBase64, "Analyze this image (${file.name}):"))
            is FileAnalyzer.Outcome.Text -> {
                val text = if (file.truncated) {
                    file.text + "\n\n[…обрезано: показаны первые ${FileAnalyzer.MAX_TEXT_CHARS} символов]"
                } else {
                    file.text
                }
                stream(analyzeContent.analyzeText(text, "Analyze this file (${file.name}):"))
            }
            is FileAnalyzer.Outcome.Unsupported -> fail(
                if (file.mimeType == "application/pdf") {
                    "PDF пока не поддерживается — пришлите текст или снимок страницы"
                } else {
                    "Этот тип файла не поддерживается: «${file.name}»"
                }
            )
            is FileAnalyzer.Outcome.TooLarge ->
                fail("Файл «${file.name}» слишком большой (больше ${file.limitBytes / (1024 * 1024)} МБ)")
            is FileAnalyzer.Outcome.Unreadable -> fail("Не удалось прочитать файл «${file.name}»")
        }
    }

    /**
     * A page from the notebook: the question (or a note that there is none)
     * goes into the conversation, the page to the vision model.
     */
    fun onNotebook(page: com.friday.ai.ui.notebook.NotebookInbox.Page) {
        val started = begin {
            persist("[Блокнот] " + page.question.ifBlank { "что здесь написано?" }, MessageRole.USER)
            stream(analyzeContent.analyzeImage(page.imageBase64, notebookPrompt(page.question)))
        }
        if (!started) busy()
    }

    private fun notebookPrompt(question: String): String {
        val task = if (question.isBlank()) {
            "If it asks something or is a problem, answer or solve it; otherwise say what it says. "
        } else {
            "Then do what the user asks about it: \"$question\". "
        }
        return "The image is a page the user wrote or drew by hand: text, a formula, a sum or a sketch. " +
            "Read it carefully first. " + task +
            "Show the working for maths. Answer in the language of the writing or the question."
    }

    private suspend fun Request.reply(text: String) {
        // Catch a correction before the answer, so what the user just put
        // right is stored even if the reply goes wrong.
        CorrectionDetector.detect(
            text,
            assistantSaidSomething = history.any { it.role == MessageRole.ASSISTANT }
        )?.let { memory.recordCorrection(it) }
        stream(sendMessage(prompt = text, history = history, mode = _uiState.value.currentMode))
    }

    /**
     * Starts [work] as the one request, if none is running. Its conversation
     * is fixed now; loading is turned off only by the request that turned it on.
     */
    private fun begin(work: suspend Request.() -> Unit): Boolean {
        if (active != null) return false
        val request = Request(_currentSessionId.value, _uiState.value.messages.filterNot { it.isStreaming })
        active = request
        _uiState.update { it.copy(isLoading = true, error = null) }
        request.job = viewModelScope.launch {
            try {
                if (request.sessionId == null) {
                    request.sessionId = memory.getOrCreateSessionId().also { _currentSessionId.value = it }
                }
                request.work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                request.fail(e.message ?: "Ошибка")
            } finally {
                request.streamId?.let(::removeStreamingMessage)
                if (active === request) finishLoading(request)
            }
        }
        return true
    }

    /** Cancels [request]; a newer request is then free to start at once. */
    private fun abandon(request: Request) {
        request.job?.cancel()
        finishLoading(request)
    }

    private fun finishLoading(request: Request) {
        if (active !== request) return
        active = null
        _uiState.update { it.copy(isLoading = false) }
    }

    /** Saves a message into the request's own conversation, whichever one is on screen now. */
    private suspend fun Request.persist(content: String, role: MessageRole) {
        val session = sessionId ?: return
        sendMessage.saveMessage(Message(content = content, role = role, sessionId = session))
    }

    /** Draws [flow] as it arrives (when this conversation is on screen) and saves it to this conversation. */
    private suspend fun Request.stream(flow: Flow<String>) {
        val id = UUID.randomUUID().toString()
        streamId = id
        val text = StringBuilder()
        flow.collect { token ->
            text.append(token)
            if (sessionId == _currentSessionId.value) updateStreamingMessage(id, text.toString())
        }
        if (text.isNotEmpty()) persist(text.toString(), MessageRole.ASSISTANT)
    }

    /** An error belongs to the conversation it happened in; another one on screen is left alone. */
    private fun Request.fail(reason: String) {
        if (sessionId == _currentSessionId.value) showError(reason)
    }

    private fun showError(reason: String) {
        _uiState.update { it.copy(error = reason) }
    }

    private fun busy() = showError("Пятница ещё отвечает — подождите немного")

    private fun updateStreamingMessage(id: String, content: String) {
        _uiState.update { state ->
            val msgs = state.messages.toMutableList()
            val existingIdx = msgs.indexOfFirst { it.id == id }
            val streamMsg = Message(id = id, content = content, role = MessageRole.ASSISTANT, isStreaming = true)
            if (existingIdx >= 0) {
                msgs[existingIdx] = streamMsg
            } else {
                msgs.add(streamMsg)
            }
            state.copy(messages = msgs)
        }
    }

    private fun removeStreamingMessage(id: String) {
        _uiState.update { state ->
            state.copy(messages = state.messages.filter { it.id != id || !it.isStreaming })
        }
    }

    fun onModeChange(mode: AssistantMode) {
        _uiState.update { it.copy(currentMode = mode) }
    }

    fun onMicClick() {
        if (voiceInput.isListening.value) voiceInput.stopListening()
        else voiceInput.startListening()
    }

    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        voiceInput.destroy()
    }
}
