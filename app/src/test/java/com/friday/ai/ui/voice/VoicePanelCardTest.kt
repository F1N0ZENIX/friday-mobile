@file:Suppress("MaxLineLength") // test scenes and spoken sentences, kept whole

package com.friday.ai.ui.voice

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.friday.ai.core.voice.VoicePanel
import com.friday.ai.core.voice.VoicePhase
import com.friday.ai.core.voice.VoicePresentation
import com.friday.ai.ui.theme.FridayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class VoicePanelCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val answer = "Отправить SMS для «Firdavs Gettysburg»: «я свободен»? Да или нет."
    private val panel = VoicePanel(session = 1, phase = VoicePhase.SPEAKING, heard = "напиши Фердавсу", answer = answer)

    private var closed = 0
    private var expandedTaps = 0

    private fun show(locked: Boolean = false, fontScale: Float = 1f) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var expanded by androidx.compose.runtime.remember { mutableStateOf(false) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                FridayTheme {
                    VoicePanelCard(
                        view = if (locked) VoicePresentation.locked(panel, false) else VoicePresentation.open(panel),
                        level = { 0.3f },
                        expanded = expanded,
                        onExpand = {
                            expandedTaps++
                            expanded = true
                        },
                        onClose = { closed++ },
                        actions = if (expanded || locked) {
                            listOf(PanelAction("Fechar") { closed++ }, PanelAction("Abrir no Friday", primary = true) {})
                        } else {
                            emptyList()
                        }
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
    }

    @Test
    fun `a tap on the text expands the panel, it never closes it`() {
        show()
        compose.onNodeWithText(answer).performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, expandedTaps)
        assertEquals(0, closed)
        compose.onNodeWithText("Abrir no Friday").assertIsDisplayed()
    }

    @Test
    fun `the close button is labelled, large enough, and closes`() {
        show()
        compose.onNodeWithContentDescription("Fechar Friday")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `actions are at least 48 dp tall`() {
        show(locked = true)
        compose.onNodeWithText("Fechar").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Abrir no Friday").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the locked panel shows the stage, never the answer`() {
        show(locked = true)
        compose.onNodeWithText("Respondendo").assertIsDisplayed()
        assertTrue(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes().none { node ->
                node.config[SemanticsProperties.Text].any { it.text.contains("Gettysburg") }
            })
    }

    @Test
    fun `the stage is announced politely and the close button survives the largest font`() {
        show(fontScale = 2f)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Fechar Friday").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
    }
}
