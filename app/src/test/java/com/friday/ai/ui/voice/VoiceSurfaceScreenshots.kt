@file:Suppress("MaxLineLength") // test scenes and spoken sentences, kept whole

package com.friday.ai.ui.voice

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.friday.ai.core.voice.VoicePanel
import com.friday.ai.core.voice.VoicePhase
import com.friday.ai.core.voice.VoicePresentation
import com.friday.ai.ui.theme.FridayTheme
import java.io.File
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The voice surfaces drawn to PNG, to look at without a phone. Off by
 * default: `./gradlew testDebugUnitTest --tests "*VoiceSurfaceScreenshots*" -Pscreenshots`.
 * The backgrounds stand in for a launcher and a lock screen; they are not Friday's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class VoiceSurfaceScreenshots {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() = Assume.assumeTrue(System.getProperty("screenshots") == "true")

    private val turn = VoicePanel(
        session = 1, phase = VoicePhase.SPEAKING, heard = "Напиши Фердавсу Гетисбургу, что я свободен",
        answer = "Отправить SMS для «Firdavs Gettysburg»: «я свободен»? Да или нет.", source = "команда"
    )
    private val long = turn.copy(
        phase = VoicePhase.RESULT, source = "ИИ",
        answer = "Завтра в Геттисберге от +9° до +23°, утром ясно, к вечеру переменная облачность. " +
            "Осадков не ожидается, ветер слабый, 3 м/с. Вечером прохладно — возьмите лёгкую куртку. " +
            "В выходные потеплеет до +25°, в воскресенье возможен короткий дождь после обеда."
    )

    @Test fun overlayListening() = shoot("voice_overlay_listening") { Launcher { Open(turn.copy(phase = VoicePhase.LISTENING, answer = null)) } }
    @Test fun overlayHearing() = shoot("voice_overlay_hearing") { Launcher { Open(turn.copy(phase = VoicePhase.LISTENING, hearing = true, answer = null)) } }
    @Test fun overlayThinking() = shoot("voice_overlay_thinking") { Launcher { Open(turn.copy(phase = VoicePhase.PROCESSING, answer = null)) } }
    @Test fun overlaySpeaking() = shoot("voice_overlay_speaking") { Launcher { Open(turn) } }
    @Test fun overlayExpanded() = shoot("voice_overlay_expanded") { Launcher { Open(long, expanded = true) } }
    @Test fun overlayError() = shoot("voice_overlay_error") {
        Launcher { Open(turn.copy(phase = VoicePhase.ERROR, answer = "Нет сети: Groq не отвечает. Проверьте интернет.")) }
    }
    @Test fun lockListening() = shoot("voice_lock_listening") { Lock { Locked(turn.copy(phase = VoicePhase.LISTENING), false) } }
    @Test fun lockSpeaking() = shoot("voice_lock_speaking_private") { Lock { Locked(turn, false) } }
    @Test fun lockRevealed() = shoot("voice_lock_unlocked_details") { Lock { Locked(turn, true) } }
    @Test fun lockLargeFont() = shoot("voice_lock_large_font", fontScale = 1.6f) { Lock { Locked(turn, false) } }
    @Test fun overlayLargeFont() = shoot("voice_overlay_large_font", fontScale = 1.6f) { Launcher { Open(long, expanded = true) } }

    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    @Test fun lockLandscape() = shoot("voice_lock_landscape") { Lock { Locked(turn, false) } }

    @Composable
    private fun Open(p: VoicePanel, expanded: Boolean = false) {
        VoicePanelCard(
            view = VoicePresentation.open(p), level = { 0.45f }, expanded = expanded, onExpand = {}, onClose = {},
            actions = if (expanded) {
                listOf(PanelAction("Закрыть") {}, PanelAction("Открыть в Friday", primary = true) {})
            } else {
                emptyList()
            }
        )
    }

    @Composable
    private fun Locked(p: VoicePanel, unlocked: Boolean) {
        val view = VoicePresentation.locked(p, unlocked)
        VoicePanelCard(
            view = view, level = { 0.45f }, expanded = unlocked, onExpand = {}, onClose = {},
            closeLabel = "Завершить разговор с Пятницей",
            showClose = false,
            actions = buildList {
                add(PanelAction("Завершить") {})
                if (view.canReveal) add(PanelAction("Разблокировать для деталей", primary = true) {})
            }
        )
    }

    /** A stand-in home screen: the panel floats over it, the app stays visible. */
    @Composable
    private fun Launcher(panel: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF27405A), Color(0xFF131A24))))
        ) {
            Text("12:41", color = Color.White, fontSize = 56.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 28.dp)) {
                panel()
            }
        }
    }

    /** A stand-in lock screen: the system clock keeps first place, the panel sits low. */
    @Composable
    private fun Lock(panel: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B2433), Color(0xFF080B10))))) {
            Column(Modifier.align(Alignment.TopCenter).padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("12:41", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light)
                Text("вторник, 14 октября", color = Color(0xCCFFFFFF))
            }
            Box(Modifier.fillMaxSize().background(VoiceTokens.Scrim))
            Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { panel() }
        }
    }

    private fun shoot(name: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { FridayTheme(content) }
        }
        compose.mainClock.advanceTimeBy(1_200)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bitmap))
        val dir = File(System.getProperty("screenshotDir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
