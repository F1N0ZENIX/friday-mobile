package com.friday.ai.localai

import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import dev.ffmpegkit.llama.LlamaConfig
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaLocalInferenceEngineTest {
    @Test
    fun `releases handle after successful completion`() = runBlocking {
        val native = FakeNative(answer = " resposta ")

        val answer = engine(native).reply("oi")

        assertEquals("resposta", answer)
        assertSame(native.handle, native.released)
    }

    @Test
    fun `releases handle when completion fails`() = runBlocking {
        val native = FakeNative(completionError = IllegalStateException("boom"))

        val error = runCatching { engine(native).reply("oi") }.exceptionOrNull()

        assertSame(native.completionError, error)
        assertSame(native.handle, native.released)
    }

    @Test
    fun `cancellation during generation releases handle`() = runBlocking {
        val native = FakeNative(blockCompletion = true)
        val job = launch { engine(native).reply("oi") }
        native.completionStarted.await()

        job.cancelAndJoin()

        assertSame(native.handle, native.released)
    }

    @Test
    fun `native user input excludes transcript and history is separate`() = runBlocking {
        val native = FakeNative(answer = "Tudo bem")
        val history = listOf(
            Message(role = MessageRole.USER, content = "Como foi ontem?"),
            Message(role = MessageRole.ASSISTANT, content = "Foi bem."),
            Message(role = MessageRole.USER, content = "E hoje?")
        )
        engine(native).replyWithHistory("E hoje?", history)
        assertEquals("E hoje?", native.receivedPrompt)
        assertTrue(native.receivedSystem.contains("Como foi ontem?"))
        assertTrue(native.receivedSystem.contains("Foi bem."))
        assertTrue(!native.receivedSystem.contains("E hoje?"))
        assertTrue(!native.receivedPrompt.endsWith("Friday:"))
    }


    @Test
    fun `streams native chunks incrementally and releases the model`() = runBlocking {
        val native = FakeNative(streamChunks = listOf("Olá", ", mundo", "!"))

        val chunks = engine(native).streamWithHistory("oi", emptyList()).toList()

        assertEquals(listOf("Olá", ", mundo", "!"), chunks)
        assertSame(native.handle, native.released)
    }

    @Test
    fun `cancellation during token streaming releases the model`() = runBlocking {
        val native = FakeNative(blockCompletion = true)
        val job = launch { engine(native).streamWithHistory("oi", emptyList()).collect { } }
        native.completionStarted.await()

        job.cancelAndJoin()

        assertSame(native.handle, native.released)
    }

    private fun engine(native: NativeLlama) =
        LlamaLocalInferenceEngine(activeFile = { File("model.gguf") }, native = native)

    private class FakeNative(
        private val answer: String = "",
        val completionError: Throwable? = null,
        private val blockCompletion: Boolean = false,
        private val streamChunks: List<String> = listOf(answer)
    ) : NativeLlama {
        val handle = Any()
        var released: Any? = null
        var receivedPrompt = ""
        var receivedSystem = ""
        val completionStarted = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun load(file: File, config: LlamaConfig): Any = handle

        override suspend fun complete(
            model: Any,
            prompt: String,
            systemPrompt: String,
            maxTokens: Int
        ): String {
            receivedPrompt = prompt
            receivedSystem = systemPrompt
            completionStarted.complete(Unit)
            completionError?.let { throw it }
            if (blockCompletion) awaitCancellation()
            return answer
        }

        override fun stream(
            model: Any,
            prompt: String,
            systemPrompt: String,
            maxTokens: Int
        ): Flow<String> = flow {
            receivedPrompt = prompt
            receivedSystem = systemPrompt
            completionStarted.complete(Unit)
            completionError?.let { throw it }
            if (blockCompletion) awaitCancellation()
            streamChunks.forEach { emit(it) }
        }

        override fun release(model: Any) {
            released = model
        }
    }
}
