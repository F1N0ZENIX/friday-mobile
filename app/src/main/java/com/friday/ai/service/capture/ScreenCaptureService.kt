package com.friday.ai.service.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import com.friday.ai.core.ImageBudget
import com.friday.ai.core.JpegEncoding
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * The short-lived foreground service Android 14+ requires around a screen
 * capture (type mediaProjection). It lives for one frame: started with the
 * consent token, it goes foreground, takes the projection, reads one image
 * into a bounded bitmap, releases everything and stops. See [ScreenCapture].
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val EXTRA_ID = "capture_id"
        private const val EXTRA_CODE = "result_code"
        private const val EXTRA_DATA = "result_data"
        private const val ACTION_CANCEL = "com.friday.ai.capture.CANCEL"
        private const val CHANNEL_ID = "friday_screen_capture"
        private const val NOTIFICATION_ID = 4107

        /** No frame within this: the capture failed. */
        private const val FRAME_TIMEOUT_MS = 5_000L

        /** Frames the reader may hold; one is used. */
        private const val MAX_IMAGES = 2

        fun start(context: Context, id: String, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Ends capture [id] if it is still running; nothing otherwise. */
        fun cancel(id: String) {
            running?.cancel(id)
        }

        @Volatile
        private var running: ScreenCaptureService? = null
    }

    private val capture: ScreenCapture by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val thread = HandlerThread("friday-screen-capture").apply { start() }
    private val handler = Handler(thread.looper)

    /** The capture in progress; one at a time. */
    @Volatile
    private var session: Session? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, and with the projection type: Android 14+ refuses the projection otherwise.
        goForeground()
        val id = intent?.getStringExtra(EXTRA_ID)
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        if (intent?.action == ACTION_CANCEL || id == null || data == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        session?.finish(ScreenCapture.Result.Failed("Снимок экрана прерван новым"))
        handler.post { session = Session(id).also { it.begin(code, data) } }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.finish(ScreenCapture.Result.Failed("Снимок экрана остановлен"))
        running = null
        scope.cancel()
        thread.quitSafely()
        super.onDestroy()
    }

    private fun cancel(id: String) {
        handler.post { session?.takeIf { it.id == id }?.finish(ScreenCapture.Result.Failed("Снимок экрана отменён")) }
    }

    private fun stopIfIdle() {
        if (session == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun goForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Снимок экрана", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Пока Пятница снимает экран для анализа — одно мгновение" }
        )
        // Says that the screen is being captured, never what is on it.
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Пятница смотрит на экран")
            .setContentText("Один снимок для анализа")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
        // The type exists from Android 10; before it, a plain foreground service is what there is.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /**
     * One capture: projection, display and reader, released together by
     * [finish] however it ends. Runs on [handler]'s thread.
     */
    private inner class Session(val id: String) {
        private val done = AtomicBoolean(false)
        private val frameTaken = AtomicBoolean(false)
        @Volatile private var projection: MediaProjection? = null
        @Volatile private var display: VirtualDisplay? = null
        @Volatile private var reader: ImageReader? = null
        private val timeout = Runnable { finish(ScreenCapture.Result.Failed("Экран не прислал изображение")) }

        private val stopped = object : MediaProjection.Callback() {
            override fun onStop() {
                // Our own release after taking the frame stops it too; only an outside stop is a failure.
                if (!frameTaken.get()) finish(ScreenCapture.Result.Failed("Снимок экрана остановлен"))
            }
        }

        // Projection setup throws SecurityException or IllegalStateException, varying by device.
        @Suppress("TooGenericExceptionCaught")
        fun begin(resultCode: Int, data: Intent) {
            try {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = manager.getMediaProjection(resultCode, data)
                    ?: return finish(ScreenCapture.Result.Failed("Android не дал доступ к экрану"))
                this.projection = projection
                // Registered before the display: Android 14+ requires it.
                projection.registerCallback(stopped, handler)
                val metrics = resources.displayMetrics
                val size = ImageBudget.bounded(metrics.widthPixels, metrics.heightPixels)
                val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, MAX_IMAGES)
                this.reader = reader
                reader.setOnImageAvailableListener({ onFrame(it) }, handler)
                display = projection.createVirtualDisplay(
                    "FridayScreenCapture", size.width, size.height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
                )
                handler.postDelayed(timeout, FRAME_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Capture could not start: ${e.javaClass.simpleName}")
                finish(ScreenCapture.Result.Failed("Android не дал снять экран (${e.javaClass.simpleName})"))
            }
        }

        private fun onFrame(reader: ImageReader) {
            if (done.get() || !frameTaken.compareAndSet(false, true)) return
            val bitmap = runCatching { reader.acquireLatestImage() }.getOrNull()?.use { toBitmap(it) }
            if (bitmap == null) {
                frameTaken.set(false)
                return
            }
            // The frame is ours: the display and projection can go before the encoding.
            releaseCapture()
            scope.launch {
                val jpeg = try {
                    JpegEncoding.base64(bitmap)
                } finally {
                    bitmap.recycle()
                }
                finish(
                    jpeg?.let { ScreenCapture.Result.Image(it) }
                        ?: ScreenCapture.Result.Failed("Снимок экрана слишком большой")
                )
            }
        }

        /** The image's pixels, without the row padding some devices add. */
        private fun toBitmap(image: Image): Bitmap {
            val plane = image.planes[0]
            val padding = plane.rowStride - plane.pixelStride * image.width
            val padded = createBitmap(image.width + padding / plane.pixelStride, image.height)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (padding == 0) return padded
            return Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        }

        /** Delivers [result] once, releases everything, and stops the service. Safe to call again. */
        fun finish(result: ScreenCapture.Result) {
            if (!done.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            releaseCapture()
            capture.deliver(id, result)
            if (session === this) session = null
            stopIfIdle()
        }

        private fun releaseCapture() {
            runCatching { display?.release() }
            display = null
            runCatching { reader?.close() }
            reader = null
            runCatching { projection?.unregisterCallback(stopped) }
            runCatching { projection?.stop() }
            projection = null
        }
    }
}
