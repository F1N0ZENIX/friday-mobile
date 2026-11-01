package com.friday.ai.localai

import dev.ffmpegkit.llama.LlamaConfig
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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

    private fun engine(native: NativeLlama) =
        LlamaLocalInferenceEngine(activeFile = { File("model.gguf") }, native = native)

    private class FakeNative(
        private val answer: String = "",
        val completionError: Throwable? = null,
        private val blockCompletion: Boolean = false
    ) : NativeLlama {
        val handle = Any()
        var released: Any? = null
        val completionStarted = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun load(file: File, config: LlamaConfig): Any = handle

        override suspend fun complete(
            model: Any,
            prompt: String,
            systemPrompt: String,
            maxTokens: Int
        ): String {
            completionStarted.complete(Unit)
            completionError?.let { throw it }
            if (blockCompletion) awaitCancellation()
            return answer
        }

        override fun release(model: Any) {
            released = model
        }
    }
}
