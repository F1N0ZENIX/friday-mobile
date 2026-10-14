package com.friday.ai.service.lock

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.friday.ai.command.Unlocker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Friday's panel over the lock screen, and Android's own unlock on request.
 *
 * The floating panel is an overlay window, and Android hides overlays while
 * the phone is locked: Friday answered there by voice only. Over the lock
 * screen a window must belong to an activity that asks to show there
 * ([LockScreenActivity]); from a background service it can only be started
 * the way alarms and calls start theirs — a full-screen notification.
 */
object LockScreen {

    private const val TAG = "LockScreen"
    private const val CHANNEL_ID = "friday_lock_screen"
    private const val NOTIFICATION_ID = 4108
    private const val UNLOCK_WAIT_MS = 45_000L

    /** What the panel says: who is talking, and the text. Null when the panel is closed. */
    data class Panel(val status: String, val text: String)

    private val _panel = MutableStateFlow<Panel?>(null)
    val panel: StateFlow<Panel?> = _panel.asStateFlow()

    private val _unlock = MutableStateFlow<CompletableDeferred<Boolean>?>(null)

    /** A pending request to show Android's unlock screen; the activity answers it. */
    val unlock: StateFlow<CompletableDeferred<Boolean>?> = _unlock.asStateFlow()

    /** True while [LockScreenActivity] is on screen. */
    @Volatile var visible = false

    fun locked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Mirrors the floating panel; on a locked phone, brings the lock-screen panel up. */
    fun show(context: Context, panel: Panel) {
        _panel.value = panel
        if (locked(context) && !visible) launch(context)
    }

    fun close(context: Context) {
        _panel.value = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** Whether Android lets Friday bring an activity over the lock screen at all. */
    fun canAppear(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    /** [Unlocker] on a real phone: Android's unlock screen, shown from [LockScreenActivity]. */
    fun unlocker(context: Context) = Unlocker {
        when {
            !locked(context) -> Unlocker.Result.UNLOCKED
            !visible && !canAppear(context) -> Unlocker.Result.CANNOT_ASK
            else -> {
                val answer = CompletableDeferred<Boolean>()
                _unlock.value = answer
                if (!visible) launch(context)
                val unlocked = withTimeoutOrNull(UNLOCK_WAIT_MS) { answer.await() } == true
                _unlock.compareAndSet(answer, null)
                if (unlocked) Unlocker.Result.UNLOCKED else Unlocker.Result.STAYED_LOCKED
            }
        }
    }

    /** The full-screen notification that starts [LockScreenActivity] over the lock screen. */
    private fun launch(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Пятница на экране блокировки", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) }
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Пятница")
            .setContentText(_panel.value?.text?.ifBlank { null } ?: "Слушаю")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(open, true)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Lock-screen panel refused: ${e.message}")
        }
    }
}
