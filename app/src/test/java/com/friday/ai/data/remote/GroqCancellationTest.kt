package com.friday.ai.data.remote

import com.friday.ai.data.remote.dto.ApiMessage
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTTP call behind a request stops with the coroutine that wanted it,
 * and every response is closed — proved against a fake server inside an
 * interceptor, with no network.
 */
class GroqCancellationTest {

    private val call = AtomicReference<Call?>()

    /**
     * A body that gives [first] and then hangs, as a stalled server does,
     * until its call is cancelled.
     */
    private inner class StalledBody(first: String) : Source {
        private val pending = Buffer().writeUtf8(first)

        @Volatile
        var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (pending.size > 0) return pending.read(sink, byteCount)
            while (true) {
                if (call.get()?.isCanceled() == true) throw IOException("Canceled")
                Thread.sleep(POLL_MS)
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
        }
    }

    private class CountingBody(text: String) : Source {
        private val data = Buffer().writeUtf8(text)

        @Volatile
        var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long = if (data.size == 0L) -1 else data.read(sink, byteCount)

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
        }
    }

    private fun groq(code: Int = 200, body: Source? = null, hangBeforeReply: Boolean = false) =
        GroqApiService.create(
            OkHttpClient.Builder().addInterceptor(
                Interceptor { chain ->
                    call.set(chain.call())
                    if (hangBeforeReply) {
                        while (!chain.call().isCanceled()) Thread.sleep(POLL_MS)
                        throw IOException("Canceled")
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("fake")
                        .body(body!!.buffer().asResponseBody("text/event-stream".toMediaType(), -1))
                        .build()
                }
            ).build()
        )

    private fun chunk(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\n"

    private suspend fun cancelledInTime() {
        withTimeout(TIMEOUT_MS) { while (call.get()?.isCanceled() != true) kotlinx.coroutines.delay(POLL_MS) }
    }

    @Test
    fun `a cancelled stream cancels its HTTP call and closes the body`() = runBlocking {
        val body = StalledBody(chunk("Привет"))
        val firstToken = CompletableDeferred<String>()
        val job = launch(Dispatchers.Default) {
            groq(body = body).streamCompletion("k", listOf(ApiMessage("user", "hi")), "m").collect {
                firstToken.complete(it)
            }
        }
        assertEquals("Привет", withTimeout(TIMEOUT_MS) { firstToken.await() })

        withTimeout(TIMEOUT_MS) { job.cancelAndJoin() }

        cancelledInTime()
        assertTrue(body.closed)
    }

    @Test
    fun `a cancelled transcription cancels the call to a stalled server`() = runBlocking {
        val audio = File.createTempFile("friday-test", ".wav").apply { writeBytes(ByteArray(44)) }
        try {
            val request = async(Dispatchers.Default) { groq(hangBeforeReply = true).transcribeAudio("k", audio) }
            withTimeout(TIMEOUT_MS) { while (call.get() == null) kotlinx.coroutines.delay(POLL_MS) }

            withTimeout(TIMEOUT_MS) { request.cancelAndJoin() }

            cancelledInTime()
        } finally {
            audio.delete()
        }
    }

    @Test
    fun `an error reply is closed and still reported as before`() = runBlocking {
        val body = CountingBody("""{"error":{"message":"bad key"}}""")
        val error = runCatching {
            groq(code = 401, body = body).streamCompletion("k", listOf(ApiMessage("user", "hi")), "m").toList()
        }.exceptionOrNull()

        assertTrue(error.toString(), error is GroqApiException && error.code == 401)
        assertTrue(body.closed)
    }

    @Test
    fun `a finished stream is read whole and closed`() = runBlocking {
        val body = CountingBody(chunk("Добрый ") + chunk("день") + "data: [DONE]\n\n")
        val tokens = groq(body = body).streamCompletion("k", listOf(ApiMessage("user", "hi")), "m").toList()

        assertEquals(listOf("Добрый ", "день"), tokens)
        assertTrue(body.closed)
    }

    private companion object {
        const val POLL_MS = 5L
        const val TIMEOUT_MS = 5_000L
    }
}
