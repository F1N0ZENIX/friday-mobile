package com.friday.ai.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.friday.ai.command.CommandExecutor
import com.friday.ai.core.FileAnalyzer
import com.friday.ai.service.capture.ScreenCapture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import com.friday.ai.command.InfoActions
import com.friday.ai.command.PhoneActions
import com.friday.ai.command.PlannerActions
import com.friday.ai.core.CommandRouter
import com.friday.ai.core.VoiceInputManager
import com.friday.ai.service.FridayMemory
import com.friday.ai.service.mail.MailAssistant
import com.friday.ai.domain.model.AssistantMode
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import com.friday.ai.domain.usecase.AnalyzeContentUseCase
import com.friday.ai.domain.usecase.GetChatHistoryUseCase
import com.friday.ai.domain.usecase.SendMessageUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelInteractionTest {

    private companion object {
        const val ACTIVE_SESSION = "session-active"
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var getChatHistoryUseCase: GetChatHistoryUseCase
    private lateinit var analyzeContentUseCase: AnalyzeContentUseCase
    private lateinit var voiceInputManager: VoiceInputManager
    private lateinit var memory: FridayMemory
    private lateinit var router: CommandRouter
    private lateinit var phone: PhoneActions
    private lateinit var planner: PlannerActions
    private lateinit var info: InfoActions
    private lateinit var mail: MailAssistant
    private lateinit var commands: CommandExecutor
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sendMessageUseCase = mockk(relaxed = true)
        getChatHistoryUseCase = mockk(relaxed = true)
        analyzeContentUseCase = mockk(relaxed = true)
        voiceInputManager = mockk(relaxed = true)
        memory = mockk(relaxed = true)
        router = mockk(relaxed = true)
        phone = mockk(relaxed = true)
        planner = mockk(relaxed = true)
        info = mockk(relaxed = true)
        mail = mockk(relaxed = true)
        // Relaxed mocks answer "" for String?, which would read as a
        // confirmed send and swallow every message.
        coEvery { mail.answerPending(any(), any()) } returns null
        coEvery { phone.answerPending(any(), any()) } returns null
        // The real executor, so these tests cover the chat and the command
        // path together; only the actions themselves are stubbed.
        commands = CommandExecutor(router, phone, planner, info, mail, io = testDispatcher)

        every { getChatHistoryUseCase.sessions() } returns flowOf(emptyList())
        every { getChatHistoryUseCase.session(any()) } returns emptyFlow()
        coEvery { memory.getOrCreateSessionId() } returns ACTIVE_SESSION
        coEvery { memory.startNewSession() } returns "new-session"
        every { voiceInputManager.recognizedText } returns MutableStateFlow(null)
        every { voiceInputManager.isListening } returns MutableStateFlow(false)
        every { voiceInputManager.error } returns MutableStateFlow(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(files: FileAnalyzer? = null, screen: ScreenCapture? = null) = ChatViewModel(
        sendMessage = sendMessageUseCase,
        getChatHistory = getChatHistoryUseCase,
        analyzeContent = analyzeContentUseCase,
        voiceInput = voiceInputManager,
        commands = commands,
        memory = memory,
        files = files,
        screen = screen
    )

    @Test
    fun `onMicClick starts voice listening`() = runTest {
        val listening = MutableStateFlow(false)
        every { voiceInputManager.isListening } returns listening

        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onMicClick()
        verify { voiceInputManager.startListening() }
    }

    @Test
    fun `voice input is destroyed on cleanup`() = runTest {
        viewModel = createViewModel()
        advanceUntilIdle()

        val method = viewModel.javaClass.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(viewModel)
        verify { voiceInputManager.destroy() }
    }

    @Test
    fun `analyze screen triggers callback`() = runTest {
        every { router.route(any()) } returns CommandResult.AnalyzeScreen
        var callbackTriggered = false

        viewModel = createViewModel()
        viewModel.onAnalyzeScreenRequested = { callbackTriggered = true }
        advanceUntilIdle()

        viewModel.onInputChange("проанализируй экран")
        viewModel.onSend()
        advanceUntilIdle()

        assertTrue(callbackTriggered)
    }

    @Test
    fun `analyze file triggers callback`() = runTest {
        every { router.route(any()) } returns CommandResult.AnalyzeFile("test.txt")
        var callbackFileHint: String? = null

        viewModel = createViewModel()
        viewModel.onAnalyzeFileRequested = { hint -> callbackFileHint = hint }
        advanceUntilIdle()

        viewModel.onInputChange("проанализируй файл test.txt")
        viewModel.onSend()
        advanceUntilIdle()

        assertEquals("test.txt", callbackFileHint)
    }

    @Test
    fun `a granted screen capture is analysed`() = runTest {
        val screen = mockk<ScreenCapture>()
        coEvery { screen.capture(any(), any()) } returns ScreenCapture.Result.Image("base64data")
        coEvery { analyzeContentUseCase.analyzeImage(any(), any()) } returns flowOf("This is a screenshot of...")
        viewModel = createViewModel(screen = screen)
        advanceUntilIdle()

        viewModel.onScreenConsent(Activity.RESULT_OK, mockk<Intent>())
        advanceUntilIdle()

        coVerify { analyzeContentUseCase.analyzeImage("base64data", any()) }
        coVerify { sendMessageUseCase.saveMessage(match { it.role == MessageRole.ASSISTANT }) }
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a refused or failed capture says why instead of clearing the error`() = runTest {
        val screen = mockk<ScreenCapture>()
        coEvery { screen.capture(any(), any()) } returns ScreenCapture.Result.Failed("Captura de tela interrompida")
        viewModel = createViewModel(screen = screen)
        advanceUntilIdle()

        viewModel.onScreenConsent(Activity.RESULT_CANCELED, null)
        assertEquals("Acesso à tela não autorizado — análise cancelada", viewModel.uiState.value.error)

        viewModel.onScreenConsent(Activity.RESULT_OK, mockk<Intent>())
        advanceUntilIdle()
        assertEquals("Captura de tela interrompida", viewModel.uiState.value.error)
        coVerify(exactly = 0) { analyzeContentUseCase.analyzeImage(any(), any()) }
    }

    @Test
    fun `a file that is too large or a PDF is explained, not sent`() = runTest {
        val files = mockk<FileAnalyzer>()
        val uri = mockk<Uri>()
        coEvery { files.read(uri) } returns FileAnalyzer.Outcome.TooLarge("photo.jpg", 30L * 1024 * 1024)
        viewModel = createViewModel(files = files)
        advanceUntilIdle()

        viewModel.onFilePicked(uri)
        advanceUntilIdle()
        assertEquals("O arquivo «photo.jpg» é grande demais (mais de 30 MB)", viewModel.uiState.value.error)

        coEvery { files.read(uri) } returns FileAnalyzer.Outcome.Unsupported("doc.pdf", "application/pdf")
        viewModel.onFilePicked(uri)
        advanceUntilIdle()
        assertEquals(
            "PDF ainda não é compatível — envie o texto ou uma imagem da página",
            viewModel.uiState.value.error
        )
        coVerify(exactly = 0) { analyzeContentUseCase.analyzeText(any(), any()) }
    }

    @Test
    fun `a cut text file is sent with the cut marked`() = runTest {
        val files = mockk<FileAnalyzer>()
        val uri = mockk<Uri>()
        coEvery { files.read(uri) } returns FileAnalyzer.Outcome.Text("notes.txt", "начало", truncated = true)
        coEvery { analyzeContentUseCase.analyzeText(any(), any()) } returns flowOf("ok")
        viewModel = createViewModel(files = files)
        advanceUntilIdle()

        viewModel.onFilePicked(uri)
        advanceUntilIdle()

        coVerify { analyzeContentUseCase.analyzeText(match { it.startsWith("начало") && "truncado" in it }, any()) }
    }

    /** A reply that waits for [gate] before its last part. */
    private fun heldReply(gate: CompletableDeferred<Unit>) = flow {
        emit("часть ")
        gate.await()
        emit("конец")
    }

    @Test
    fun `an answer begun in one conversation is saved there, not in the one opened meanwhile`() = runTest {
        val gate = CompletableDeferred<Unit>()
        every { router.route(any()) } returns CommandResult.ChatMessage("вопрос")
        coEvery { sendMessageUseCase(any(), any(), any()) } returns heldReply(gate)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("вопрос")
        viewModel.onSend()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.messages.any { it.isStreaming })

        viewModel.onOpenSession("session-b")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.messages.none { it.isStreaming })

        gate.complete(Unit)
        advanceUntilIdle()

        coVerify {
            sendMessageUseCase.saveMessage(match {
                it.role == MessageRole.ASSISTANT && it.content == "часть конец" && it.sessionId == ACTIVE_SESSION
            })
        }
        coVerify(exactly = 0) { sendMessageUseCase.saveMessage(match { it.sessionId == "session-b" }) }
        assertTrue(viewModel.uiState.value.messages.none { it.isStreaming })
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a second send while answering is refused`() = runTest {
        val gate = CompletableDeferred<Unit>()
        every { router.route(any()) } returns CommandResult.ChatMessage("вопрос")
        coEvery { sendMessageUseCase(any(), any(), any()) } returns heldReply(gate)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("вопрос")
        viewModel.onSend()
        viewModel.onInputChange("вопрос")
        viewModel.onSend()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { sendMessageUseCase(any(), any(), any()) }
        // The refused text stays in the field, to send once Friday has answered.
        assertEquals("вопрос", viewModel.inputText.value)
    }

    @Test
    fun `deleting the conversation cancels its answer, and a new one is not held up`() = runTest {
        val gate = CompletableDeferred<Unit>()
        every { router.route(any()) } returns CommandResult.ChatMessage("вопрос")
        coEvery { sendMessageUseCase(any(), any(), any()) } returns heldReply(gate)
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("вопрос")
        viewModel.onSend()
        advanceUntilIdle()
        viewModel.onDeleteSession(ACTIVE_SESSION)
        assertFalse(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        // The next request starts at once and its loading is its own.
        val second = CompletableDeferred<Unit>()
        coEvery { sendMessageUseCase(any(), any(), any()) } returns heldReply(second)
        viewModel.onInputChange("ещё")
        viewModel.onSend()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)

        second.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)
        coVerify(exactly = 0) {
            sendMessageUseCase.saveMessage(match { it.role == MessageRole.ASSISTANT && it.sessionId == ACTIVE_SESSION })
        }
        coVerify {
            sendMessageUseCase.saveMessage(match { it.role == MessageRole.ASSISTANT && it.sessionId == "new-session" })
        }
    }

    @Test
    fun `a failing command becomes a reply instead of an exception`() = runTest {
        // The voice loop used to die silently on this; the chat caught it.
        // With one executor both get the same, survivable behaviour.
        every { router.route(any()) } returns CommandResult.ToggleFlashlight
        coEvery { phone.run(any(), any()) } throws IllegalStateException("camera in use")
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("включи фонарик")
        viewModel.onSend()
        advanceUntilIdle()

        coVerify {
            sendMessageUseCase.saveMessage(match {
                it.role == MessageRole.ASSISTANT && it.content.contains("camera in use")
            })
        }
    }

    @Test
    fun `an answer to a pending send is not routed as a new command`() = runTest {
        coEvery { mail.answerPending("да", true) } returns "Отправила."
        viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onInputChange("да")
        viewModel.onSend()
        advanceUntilIdle()

        verify(exactly = 0) { router.route(any()) }
        coVerify { sendMessageUseCase.saveMessage(match { it.content == "Отправила." }) }
    }
}
