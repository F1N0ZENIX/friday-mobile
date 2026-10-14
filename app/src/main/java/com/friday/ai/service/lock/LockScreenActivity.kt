package com.friday.ai.service.lock

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.friday.ai.core.voice.ScreenHold
import com.friday.ai.core.voice.VoicePanel
import com.friday.ai.core.voice.VoicePresentation
import com.friday.ai.service.voice.VoiceUi
import com.friday.ai.ui.theme.FridayTheme
import com.friday.ai.ui.voice.PanelAction
import com.friday.ai.ui.voice.VoicePanelCard
import com.friday.ai.ui.voice.VoiceTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Friday over the lock screen: a compact private panel, not a copy of the
 * chat and not an imitation of the lock screen — the clock and the system
 * unlock stay Android's.
 *
 * By default it shows only the stage ([VoicePresentation.locked]); the
 * owner's words and Friday's answer appear after "Разблокировать для
 * деталей" and Android's own unlock. "Завершить", the cross and Back all end
 * the session without undoing what was already done.
 *
 * The display stays on only while this activity is visible and the session
 * needs it ([ScreenHold]); the flag goes the moment it doesn't.
 */
class LockScreenActivity : ComponentActivity() {

    /** Android's unlock succeeded while this panel was up: what was withheld may show. */
    private val unlocked = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = end()
        })
        setContent {
            FridayTheme {
                LockPanelScreen(
                    unlocked = unlocked.collectAsState().value,
                    onClosed = ::finish,
                    onEnd = ::end,
                    onReveal = { requestUnlock { ok -> unlocked.value = ok } },
                    onTouch = VoiceUi.store::touched
                )
            }
        }
        // Android's own unlock screen, when a command asks for it.
        lifecycleScope.launch {
            LockScreen.unlock.filterNotNull().collect { answer ->
                requestUnlock { ok ->
                    unlocked.value = ok
                    answer.complete(ok)
                }
            }
        }
        holdScreenWhileNeeded()
    }

    override fun onStart() {
        super.onStart()
        unlocked.value = !keyguardLocked()
        LockScreen.activityStarted(true)
    }

    override fun onStop() {
        LockScreen.activityStarted(false)
        keepScreenOn(false)
        super.onStop()
    }

    override fun onDestroy() {
        keepScreenOn(false)
        super.onDestroy()
    }

    private fun end() {
        VoiceUi.close()
        finish()
    }

    private fun showOverLockScreen() {
        val owners = VoiceUi.store.state.value?.userInitiated == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            // Only a call by the owner lights the screen; Friday's own announcements never do.
            setTurnScreenOn(owners)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    if (owners) WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON else 0
            )
        }
    }

    /** Re-checks [ScreenHold] on every change of the session and when its timers run out. */
    private fun holdScreenWhileNeeded() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VoiceUi.store.state.collectLatest { panel ->
                    while (true) {
                        val now = SystemClock.elapsedRealtime()
                        keepScreenOn(ScreenHold.hold(panel, now))
                        delay(ScreenHold.recheckIn(panel, now) ?: break)
                    }
                }
            }
        }
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun keyguardLocked() = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun requestUnlock(done: (Boolean) -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            done(true)
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(true)
            override fun onDismissCancelled() = done(false)
            override fun onDismissError() = done(false)
        })
    }
}

@Composable
private fun LockPanelScreen(
    unlocked: Boolean,
    onClosed: () -> Unit,
    onEnd: () -> Unit,
    onReveal: () -> Unit,
    onTouch: () -> Unit
) {
    val panel by VoiceUi.store.state.collectAsState()
    val level by VoiceUi.store.level.collectAsState()
    LaunchedEffect(panel == null) { if (panel == null) onClosed() }
    val current: VoicePanel = panel ?: return
    val view = VoicePresentation.locked(current, unlocked)
    Box(
        modifier = Modifier.fillMaxSize().background(VoiceTokens.Scrim).safeDrawingPadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        VoicePanelCard(
            view = view,
            level = { level },
            expanded = unlocked,
            onExpand = onTouch,
            onClose = onEnd,
            closeLabel = "Завершить разговор с Пятницей",
            showClose = false,
            actions = buildList {
                add(PanelAction("Завершить", onClick = onEnd))
                if (view.canReveal) add(PanelAction("Разблокировать для деталей", primary = true, onClick = onReveal))
            },
            modifier = Modifier.navigationBarsPadding().padding(16.dp)
        )
    }
}
