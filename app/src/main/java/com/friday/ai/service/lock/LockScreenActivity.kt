package com.friday.ai.service.lock

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.FridayTheme
import com.friday.ai.ui.theme.OnBackground
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Friday's panel over the lock screen, and the place Android's unlock
 * screen is asked for (it must be requested by an activity on screen).
 *
 * Shows only what the floating panel would: who is talking and the text.
 * What is personal reaches this panel only after [com.friday.ai.command.LockPolicy]
 * let it through, so nothing here is more than the owner allowed.
 */
class LockScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContent { FridayTheme { LockPanel(onClosed = ::finish) } }
        // Android's own unlock screen, when a command asks for it.
        lifecycleScope.launch {
            LockScreen.unlock.filterNotNull().collect { answer -> requestUnlock { answer.complete(it) } }
        }
    }

    override fun onStart() {
        super.onStart()
        LockScreen.visible = true
    }

    override fun onStop() {
        LockScreen.visible = false
        super.onStop()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

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

/** The lock screen dimmed behind the panel, and the panel itself: the floating panel's colours. */
private val Scrim = Color(0xCC03080C)
private val PanelColor = Color(0xF0071016)

@Composable
private fun LockPanel(onClosed: () -> Unit) {
    val panel by LockScreen.panel.collectAsState()
    LaunchedEffect(panel == null) { if (panel == null) onClosed() }
    val current = panel ?: return
    Box(
        modifier = Modifier.fillMaxSize().background(Scrim),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth()
                .background(PanelColor, RoundedCornerShape(28.dp))
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(current.status, color = ArcCyan, style = MaterialTheme.typography.labelLarge)
            if (current.text.isNotBlank()) {
                Text(current.text, color = OnBackground, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
