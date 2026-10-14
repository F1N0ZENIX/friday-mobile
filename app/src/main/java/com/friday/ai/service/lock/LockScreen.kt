package com.friday.ai.service.lock

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.friday.ai.command.Unlocker
import com.friday.ai.core.voice.Surfaces
import com.friday.ai.core.voice.VoicePanel
import com.friday.ai.core.voice.VoicePresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Friday over the lock screen, within what Android allows.
 *
 * Android hides overlay windows while the phone is locked. Over the lock
 * screen only an activity that asks to show there can appear
 * ([LockScreenActivity]), and a background service can bring it up only
 * through a full-screen notification — a separate grant since Android 14,
 * not given to an assistant by default. So each session tries, once:
 *
 *  - full-screen allowed: the notification, then a wait to see whether the
 *    activity really came up ([Outcome.ACTIVITY_VISIBLE]) or the system only
 *    showed the notification ([Outcome.NOTIFICATION_ONLY]);
 *  - not allowed: an ordinary private notification ("Открыть после
 *    разблокировки") — [Outcome.FSI_DENIED];
 *  - notifications off: the answer is spoken only — [Outcome.NOTIFICATIONS_BLOCKED].
 *
 * Nothing pretends to be a call or an alarm, and nothing keeps the screen
 * on unless the activity is actually on it.
 */
object LockScreen {

    private const val TAG = "LockScreen"
    private const val CHANNEL_ID = "friday_lock_screen_v2"
    private const val NOTIFICATION_ID = 4108
    private const val UNLOCK_WAIT_MS = 45_000L

    /** How long the activity has to come up after the notification before it counts as not shown. */
    const val SHOW_WAIT_MS = 3_000L

    /** How the last attempt to show Friday over the lock screen ended. */
    enum class Outcome { ACTIVITY_VISIBLE, NOTIFICATION_ONLY, FSI_DENIED, NOTIFICATIONS_BLOCKED, FAILED }

    private val _lastOutcome = MutableStateFlow<Outcome?>(null)
    val lastOutcome: StateFlow<Outcome?> = _lastOutcome.asStateFlow()

    private val _unlock = MutableStateFlow<CompletableDeferred<Boolean>?>(null)

    /** A pending request to show Android's unlock screen; the activity answers it. */
    val unlock: StateFlow<CompletableDeferred<Boolean>?> = _unlock.asStateFlow()

    private val _visible = MutableStateFlow(false)

    /** True while [LockScreenActivity] is started (on screen). */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var presented = -1L
    private var notifiedStatus: String? = null

    fun locked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** The full-screen grant (always there before Android 14). One precondition, not proof the panel shows. */
    fun fullScreenAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true

    fun activityStarted(started: Boolean) {
        _visible.value = started
    }

    /** Puts [panel] on the lock screen as [surface] says; once per session, then only status updates. */
    fun present(context: Context, panel: VoicePanel, surface: Surfaces.Surface) {
        when (surface) {
            Surfaces.Surface.LOCK_ACTIVITY -> if (panel.session != presented) {
                presented = panel.session
                showActivity(context, panel)
            }
            Surfaces.Surface.LOCK_NOTIFICATION -> {
                if (panel.session != presented) {
                    presented = panel.session
                    record(context, Outcome.FSI_DENIED)
                }
                notifyPrivately(context, panel)
            }
            Surfaces.Surface.VOICE_ONLY -> if (panel.session != presented && LockScreen.locked(context)) {
                presented = panel.session
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    record(context, Outcome.NOTIFICATIONS_BLOCKED)
                }
            }
            else -> Unit
        }
    }

    fun close(context: Context) {
        presented = -1
        notifiedStatus = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** [Unlocker] on a real phone: Android's unlock screen, shown from [LockScreenActivity]. */
    fun unlocker(context: Context) = Unlocker {
        when {
            !locked(context) -> Unlocker.Result.UNLOCKED
            !_visible.value && !fullScreenAllowed(context) -> Unlocker.Result.CANNOT_ASK
            else -> {
                val answer = CompletableDeferred<Boolean>()
                _unlock.value = answer
                if (!_visible.value) post(context, fullScreen = true, status = "Подтвердите разблокировку")
                val unlocked = withTimeoutOrNull(UNLOCK_WAIT_MS) { answer.await() } == true
                _unlock.compareAndSet(answer, null)
                if (unlocked) Unlocker.Result.UNLOCKED else Unlocker.Result.STAYED_LOCKED
            }
        }
    }

    private fun showActivity(context: Context, panel: VoicePanel) {
        if (_visible.value) {
            record(context, Outcome.ACTIVITY_VISIBLE)
            return
        }
        if (!post(context, fullScreen = true, status = VoicePresentation.locked(panel, false).status)) {
            record(context, Outcome.FAILED)
            return
        }
        // notify() is not "shown": wait for the activity itself.
        scope.launch {
            val seen = withTimeoutOrNull(SHOW_WAIT_MS) {
                while (!_visible.value) delay(POLL_MS)
                true
            } == true
            record(context, if (seen) Outcome.ACTIVITY_VISIBLE else Outcome.NOTIFICATION_ONLY)
        }
    }

    /** The fallback: an ordinary private notification, updated only when its status changes. */
    private fun notifyPrivately(context: Context, panel: VoicePanel) {
        val status = VoicePresentation.locked(panel, false).status
        if (status == notifiedStatus) return
        notifiedStatus = status
        post(context, fullScreen = false, status = status)
    }

    /**
     * One notification for the session. Its text is only the stage, never what
     * was said or answered; the lock screen gets an even plainer public version.
     */
    private fun post(context: Context, fullScreen: Boolean, status: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Пятница на экране блокировки", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
        )
        val panel = PendingIntent.getActivity(
            context, 0,
            Intent(context, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // From the lock screen Android asks for the unlock before opening the app.
        val app = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Пятница")
            .setContentText("Голосовой ассистент")
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Пятница")
            .setContentText(status)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(app ?: panel)
        app?.let { builder.addAction(0, "Открыть после разблокировки", it) }
        if (fullScreen) builder.setFullScreenIntent(panel, true)
        return try {
            manager.notify(NOTIFICATION_ID, builder.build())
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Lock-screen notification refused: ${e.message}")
            false
        }
    }

    /** What happened, for settings, and a log line with no words of the conversation. */
    private fun record(context: Context, outcome: Outcome) {
        _lastOutcome.value = outcome
        val manager = context.getSystemService(NotificationManager::class.java)
        val importance = manager?.getNotificationChannel(CHANNEL_ID)?.importance
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive
        Log.i(
            TAG,
            "Lock-screen panel: $outcome (keyguard=${locked(context)}, screenOn=$interactive, " +
                "notifications=${NotificationManagerCompat.from(context).areNotificationsEnabled()}, " +
                "channelImportance=$importance, fullScreen=${fullScreenAllowed(context)}, activity=${_visible.value})"
        )
    }

    private const val POLL_MS = 100L
}
