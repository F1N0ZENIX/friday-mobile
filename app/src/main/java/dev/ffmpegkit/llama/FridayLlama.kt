package dev.ffmpegkit.llama

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LlamaConfig(
    val contextSize: Int = 2048,
    val threads: Int = 4,
    val gpuLayers: Int = 0,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val seed: Int = -1
)

sealed class LlamaException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ModelLoadFailed(path: String) : LlamaException(
        "Failed to load model '$path'. Check that it is a valid GGUF file and that the device has enough RAM."
    )
    class ModelReleased : LlamaException("This LlamaModel has been released.")
    class InferenceFailed(message: String) : LlamaException(message)
}

class LlamaModel internal constructor(internal var handle: Long, val config: LlamaConfig) {
    val isLoaded: Boolean get() = handle != 0L
    internal fun requireHandle(): Long {
        if (handle == 0L) throw LlamaException.ModelReleased()
        return handle
    }
}

data class LlamaResult(
    val text: String,
    val tokensGenerated: Int,
    val tokensPerSecond: Float,
    val promptEvalTimeMs: Long,
    val generateTimeMs: Long
)

fun interface LlamaTokenCallback {
    /** Receives raw UTF-8 bytes because a token can end in the middle of a character. */
    fun onToken(bytes: ByteArray): Boolean
}

internal object LlamaJNI {
    init {
        for (lib in listOf("ggml-base", "ggml-cpu", "ggml", "llama")) {
            runCatching { System.loadLibrary(lib) }
        }
        System.loadLibrary("llama_jni")
    }

    external fun nativeLoadModel(path: String, contextSize: Int, threads: Int, gpuLayers: Int): Long
    external fun nativeComplete(
        handle: Long, prompt: String, systemPrompt: String, maxTokens: Int,
        temp: Float, topP: Float, topK: Int, seed: Int
    ): String
    external fun nativeStream(
        handle: Long, prompt: String, systemPrompt: String, maxTokens: Int,
        temp: Float, topP: Float, topK: Int, seed: Int, callback: LlamaTokenCallback
    )
    external fun nativeEmbed(handle: Long, text: String): FloatArray
    external fun nativeReleaseModel(handle: Long)
    external fun nativeGetSystemInfo(): String
}

object Llama {
    suspend fun loadModel(modelPath: String, config: LlamaConfig = LlamaConfig()): LlamaModel =
        withContext(Dispatchers.Default) {
            if (!File(modelPath).exists()) throw LlamaException.ModelLoadFailed(modelPath)
            val handle = LlamaJNI.nativeLoadModel(
                modelPath, config.contextSize, config.threads, config.gpuLayers
            )
            if (handle == 0L) throw LlamaException.ModelLoadFailed(modelPath)
            LlamaModel(handle, config)
        }

    suspend fun complete(
        model: LlamaModel,
        prompt: String,
        systemPrompt: String = "",
        maxTokens: Int = 512
    ): LlamaResult = withContext(Dispatchers.Default) {
        val c = model.config
        val json = LlamaJNI.nativeComplete(
            model.requireHandle(), prompt, systemPrompt, maxTokens,
            c.temperature, c.topP, c.topK, c.seed
        )
        val result = JSONObject(json)
        if (result.has("error")) throw LlamaException.InferenceFailed(result.optString("error"))
        LlamaResult(
            text = result.optString("text"),
            tokensGenerated = result.optInt("tokensGenerated"),
            tokensPerSecond = result.optDouble("tokensPerSecond", 0.0).toFloat(),
            promptEvalTimeMs = result.optLong("promptEvalTimeMs"),
            generateTimeMs = result.optLong("generateTimeMs")
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun stream(
        model: LlamaModel,
        prompt: String,
        systemPrompt: String = "",
        maxTokens: Int = 512
    ): Flow<String> = callbackFlow {
        val c = model.config
        val callback = LlamaTokenCallback { bytes -> trySendBlocking(bytes).isSuccess }
        try {
            withContext(Dispatchers.IO) {
                LlamaJNI.nativeStream(
                    model.requireHandle(), prompt, systemPrompt, maxTokens,
                    c.temperature, c.topP, c.topK, c.seed, callback
                )
            }
            close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            close(error)
        }
        awaitClose { }
    }.decodeUtf8()

    fun releaseModel(model: LlamaModel) {
        val handle = model.handle
        if (handle != 0L) {
            LlamaJNI.nativeReleaseModel(handle)
            model.handle = 0L
        }
    }

    suspend fun embed(model: LlamaModel, text: String): FloatArray =
        withContext(Dispatchers.Default) { LlamaJNI.nativeEmbed(model.requireHandle(), text) }

    fun getSystemInfo(): String = LlamaJNI.nativeGetSystemInfo()
}

/** Join token bytes before decoding so split UTF-8 characters (including emoji) remain intact. */
private fun Flow<ByteArray>.decodeUtf8(): Flow<String> = flow {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    var pending = ByteArray(0)
    collect { chunk ->
        val merged = pending + chunk
        val input = ByteBuffer.wrap(merged)
        val output = CharBuffer.allocate(merged.size * 2 + 2)
        decoder.decode(input, output, false)
        pending = merged.copyOfRange(input.position(), input.limit())
        output.flip()
        if (output.hasRemaining()) emit(output.toString())
    }
    val input = ByteBuffer.wrap(pending)
    val output = CharBuffer.allocate(pending.size * 2 + 2)
    decoder.decode(input, output, true)
    decoder.flush(output)
    output.flip()
    if (output.hasRemaining()) emit(output.toString())
}
