package com.friday.ai.service.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One screenshot for the vision model, taken the way Android 14+ requires.
 *
 * The consent screen gives a one-time token. With it, a foreground service
 * of type mediaProjection ([ScreenCaptureService]) is started first, and
 * only then is the projection obtained and a single frame read. The old
 * capture skipped the service — on API 34+ that throws — and encoded the
 * image on the main thread. Every way it can end (a frame, a timeout, the
 * user stopping it from the system bar, an error, the caller giving up)
 * releases the projection and stops the service; nothing is kept for reuse.
 */
class ScreenCapture(private val context: Context) {

    sealed interface Result {
        /** The screen as a bounded JPEG, Base64. */
        data class Image(val jpegBase64: String) : Result

        /** Why there is no image, in words for the owner. */
        data class Failed(val reason: String) : Result
    }

    private companion object {
        /** Starting the service, the first frame and encoding together; the service has its own frame timeout. */
        const val TOTAL_TIMEOUT_MS = 15_000L
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result>>()

    /** The system's consent screen for capturing the display. */
    fun consentIntent(activity: Activity): Intent =
        (activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .createScreenCaptureIntent()

    /**
     * One frame with the consent [data]. Suspends until the image is ready or
     * the capture failed; cancelling the caller stops the capture too.
     */
    suspend fun capture(resultCode: Int, data: Intent): Result {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<Result>()
        pending[id] = result
        try {
            ScreenCaptureService.start(context, id, resultCode, data)
            return withTimeoutOrNull(TOTAL_TIMEOUT_MS) { result.await() }
                ?: Result.Failed("Экран не удалось снять: время вышло")
        } finally {
            pending.remove(id)
            // A no-op when the service already finished this capture.
            if (!result.isCompleted) ScreenCaptureService.cancel(id)
        }
    }

    /** Called by the service, once per capture. */
    internal fun deliver(id: String, result: Result) {
        pending[id]?.complete(result)
    }
}
