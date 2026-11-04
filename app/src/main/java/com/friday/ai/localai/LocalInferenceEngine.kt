package com.friday.ai.localai

import com.friday.ai.domain.model.Message
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Minimal contract around native inference so routing and prompts can be tested
 * without loading a JNI library on the JVM.
 */
interface LocalInferenceEngine {
    suspend fun reply(prompt: String): String
    suspend fun replyWithHistory(prompt: String, history: List<Message>): String = reply(prompt)
    fun streamWithHistory(prompt: String, history: List<Message>): Flow<String> = flow {
        emit(replyWithHistory(prompt, history))
    }
    /** Safe default: do not pass web text to an engine that cannot isolate it. */
    suspend fun replyWithGrounding(
        prompt: String,
        history: List<Message>,
        sources: LocalInternetReply.SearchSources
    ): String = throw LocalInferenceException.GroundingUnavailable()
}

/** Fixed limits for an on-device chat turn. Do not use cloud-sized defaults here. */
object LocalInferenceLimits {
    const val CONTEXT_TOKENS = 2048
    const val MAX_REPLY_TOKENS = 128
    const val MAX_CPU_THREADS = 4
}

/**
 * Loads one selected GGUF only for the duration of a turn. llama.cpp models are
 * not thread-safe and can use substantial native RAM, so all process-local
 * inference is serialized and the native handle is always released.
 */
class LlamaLocalInferenceEngine(
    private val activeFile: () -> File?,
    private val native: NativeLlama
) : LocalInferenceEngine {
    constructor(models: LocalModelStore, native: NativeLlama = NativeLlamaApi) :
        this(models::activeFile, native)

    override suspend fun reply(prompt: String): String = replyWithHistory(prompt, emptyList())

    override suspend fun replyWithHistory(prompt: String, history: List<Message>): String = inferenceMutex.withLock {
        complete(prompt, LocalPrompt.systemFor(prompt, history))
    }

    @Suppress("ThrowsCount", "LongMethod")
    override fun streamWithHistory(prompt: String, history: List<Message>): Flow<String> = flow {
        inferenceMutex.withLock {
            currentCoroutineContext().ensureActive()
            val modelFile = activeFile() ?: throw LocalInferenceException.ModelMissing()
            val config = LlamaConfig(
                contextSize = LocalInferenceLimits.CONTEXT_TOKENS,
                threads = Runtime.getRuntime().availableProcessors().coerceIn(1, LocalInferenceLimits.MAX_CPU_THREADS),
                gpuLayers = 0,
                temperature = 0.0f,
                seed = 42
            )
            var model: Any? = null
            try {
                val loadedModel = withContext(NonCancellable) { native.load(modelFile, config) }
                model = loadedModel
                currentCoroutineContext().ensureActive()
                native.stream(
                    model = loadedModel,
                    prompt = LocalPrompt.userTurn(prompt),
                    systemPrompt = LocalPrompt.systemFor(prompt, history),
                    maxTokens = LocalInferenceLimits.MAX_REPLY_TOKENS
                ).collect { token ->
                    currentCoroutineContext().ensureActive()
                    emit(token)
                }
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) {
                throw error
            } catch (error: LocalInferenceException) {
                throw error
            } catch (error: LlamaException.ModelLoadFailed) {
                throw LocalInferenceException.LoadFailed(error)
            } catch (error: LlamaException) {
                throw LocalInferenceException.Failed("A inferência local falhou: ${error.message.orEmpty()}", error)
            } catch (error: UnsatisfiedLinkError) {
                throw LocalInferenceException.LibraryUnavailable(error)
            } catch (error: OutOfMemoryError) {
                throw LocalInferenceException.OutOfMemory(error)
            } finally {
                val handleToRelease = model
                if (handleToRelease != null) {
                    withContext(NonCancellable) { native.release(handleToRelease) }
                }
            }
        }
    }

    override suspend fun replyWithGrounding(
        prompt: String,
        history: List<Message>,
        sources: LocalInternetReply.SearchSources
    ): String = inferenceMutex.withLock {
        complete(prompt, LocalPrompt.systemForGrounded(prompt, history, sources))
    }

    @Suppress("ThrowsCount")
    private suspend fun complete(prompt: String, systemPrompt: String): String {
        currentCoroutineContext().ensureActive()
        val modelFile = activeFile() ?: throw LocalInferenceException.ModelMissing()
        val config = LlamaConfig(
            contextSize = LocalInferenceLimits.CONTEXT_TOKENS,
            threads = Runtime.getRuntime().availableProcessors().coerceIn(1, LocalInferenceLimits.MAX_CPU_THREADS),
            gpuLayers = 0,
            temperature = 0.0f,
            seed = 42
        )
        var model: Any? = null
        return try {
            // Once JNI returns a handle we own it, even if the caller cancelled meanwhile.
            model = withContext(NonCancellable) { native.load(modelFile, config) }
            currentCoroutineContext().ensureActive()
            val answer = native.complete(
                model = model,
                prompt = LocalPrompt.userTurn(prompt),
                systemPrompt = systemPrompt,
                maxTokens = LocalInferenceLimits.MAX_REPLY_TOKENS
            ).trim()
            currentCoroutineContext().ensureActive()
            if (answer.isBlank()) throw LocalInferenceException.Failed("O modelo não retornou texto.")
            answer
        } catch (error: LocalInferenceException) {
            throw error
        } catch (error: LlamaException.ModelLoadFailed) {
            throw LocalInferenceException.LoadFailed(error)
        } catch (error: LlamaException) {
            throw LocalInferenceException.Failed("A inferência local falhou: ${error.message.orEmpty()}", error)
        } catch (error: UnsatisfiedLinkError) {
            throw LocalInferenceException.LibraryUnavailable(error)
        } catch (error: OutOfMemoryError) {
            throw LocalInferenceException.OutOfMemory(error)
        } finally {
            if (model != null) native.release(model)
        }
    }

    private companion object {
        /** One mutex for every engine instance in this process. */
        val inferenceMutex = Mutex()
    }
}

/** Native boundary kept tiny so tests never load libllama_jni.so. */
interface NativeLlama {
    suspend fun load(file: File, config: LlamaConfig): Any
    suspend fun complete(model: Any, prompt: String, systemPrompt: String, maxTokens: Int): String
    fun stream(model: Any, prompt: String, systemPrompt: String, maxTokens: Int): Flow<String> = flow {
        emit(complete(model, prompt, systemPrompt, maxTokens))
    }
    fun release(model: Any)
}

private object NativeLlamaApi : NativeLlama {
    override suspend fun load(file: File, config: LlamaConfig): Any =
        Llama.loadModel(file.absolutePath, config)

    override suspend fun complete(model: Any, prompt: String, systemPrompt: String, maxTokens: Int): String =
        Llama.complete(model as dev.ffmpegkit.llama.LlamaModel, prompt, systemPrompt, maxTokens).text

    override fun stream(model: Any, prompt: String, systemPrompt: String, maxTokens: Int): Flow<String> =
        Llama.stream(model as dev.ffmpegkit.llama.LlamaModel, prompt, systemPrompt, maxTokens)

    override fun release(model: Any) {
        Llama.releaseModel(model as dev.ffmpegkit.llama.LlamaModel)
    }
}

sealed class LocalInferenceException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause) {
    class ModelMissing(cause: Throwable? = null) : LocalInferenceException(
        "Nenhum modelo local foi selecionado. Instale ou escolha um arquivo GGUF nas configurações.", cause
    )
    class LoadFailed(cause: Throwable? = null) : LocalInferenceException(
        "Não foi possível carregar o modelo local. Verifique o arquivo e a memória disponível.", cause
    )
    class LibraryUnavailable(cause: Throwable? = null) : LocalInferenceException(
        "A biblioteca de IA local não está disponível neste aparelho.", cause
    )
    class OutOfMemory(cause: Throwable? = null) : LocalInferenceException(
        "Memória insuficiente para executar o modelo local. Tente um modelo menor.", cause
    )
    class Failed(detail: String, cause: Throwable? = null) : LocalInferenceException(detail, cause)
    class GroundingUnavailable : LocalInferenceException("Este mecanismo local não suporta fontes de busca.")
}
