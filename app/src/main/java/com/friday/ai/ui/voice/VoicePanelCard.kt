package com.friday.ai.ui.voice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.friday.ai.core.voice.PanelView
import com.friday.ai.ui.overlay.ArcReactorView
import com.friday.ai.ui.theme.ArcAmber
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ErrorColor
import com.friday.ai.ui.theme.OnBackground

/**
 * Design tokens shared by every Friday voice surface: the floating panel,
 * the in-app layer and the lock-screen panel. Spacing on a 4/8 dp grid.
 */
object VoiceTokens {
    val Surface = Color(0xF00A1016)
    val Edge = Color(0x4438E8FF)
    val Muted = Color(0xFF7E97A3)
    /** Behind the lock-screen panel: enough for contrast, the wallpaper still shows. */
    val Scrim = Color(0x6603080C)
    val CompactRadius = 28.dp
    val ExpandedRadius = 24.dp
    val Reactor = 52.dp
    val Touch = 48.dp
    val Gap = 8.dp
    val Pad = 16.dp
    val MaxWidth = 560.dp

    fun tone(t: PanelView.Tone): Color = when (t) {
        PanelView.Tone.CYAN -> ArcCyan
        PanelView.Tone.AMBER -> ArcAmber
        PanelView.Tone.MUTED -> Muted
        PanelView.Tone.ERROR -> ErrorColor
    }
}

/** A button on the panel; [primary] is drawn filled. */
data class PanelAction(val label: String, val primary: Boolean = false, val onClick: () -> Unit)

/**
 * Friday's panel: the reactor, the stage in words, the text, and a close
 * button. Compact it shows two lines; a tap on the text expands it (it
 * never closes it) to the whole text and [actions].
 *
 * TalkBack hears the stage when it changes, not the microphone level or
 * every word of a streamed answer: only the status is a live region.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoicePanelCard(
    view: PanelView,
    level: () -> Float,
    expanded: Boolean,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    actions: List<PanelAction>,
    modifier: Modifier = Modifier,
    closeLabel: String = "Закрыть Пятницу",
    /** False where an action already ends the session ("Завершить"): one way out, not two. */
    showClose: Boolean = true
) {
    val maxBody = (LocalConfiguration.current.screenHeightDp * BODY_SHARE).dp
    Surface(
        modifier = modifier.widthIn(max = VoiceTokens.MaxWidth).fillMaxWidth(),
        shape = RoundedCornerShape(if (expanded) VoiceTokens.ExpandedRadius else VoiceTokens.CompactRadius),
        color = VoiceTokens.Surface,
        border = BorderStroke(1.dp, VoiceTokens.Edge)
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Reactor(view.mood, level)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .clickable(
                            enabled = !expanded && view.body != null,
                            onClickLabel = "Развернуть",
                            onClick = onExpand
                        )
                ) {
                    Text(
                        view.status,
                        color = VoiceTokens.tone(view.tone),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                    if (view.body != null && !expanded) {
                        Text(
                            view.body,
                            color = OnBackground,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = COMPACT_LINES,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (showClose) {
                    IconButton(onClick = onClose, modifier = Modifier.size(VoiceTokens.Touch)) {
                        Icon(Icons.Filled.Close, contentDescription = closeLabel, tint = VoiceTokens.Muted)
                    }
                }
            }
            if (expanded && view.body != null) {
                Box(
                    Modifier
                        .padding(start = 4.dp, end = 12.dp, top = VoiceTokens.Gap)
                        .heightIn(max = maxBody)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(view.body, color = OnBackground, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (actions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = VoiceTokens.Gap, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(VoiceTokens.Gap, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(VoiceTokens.Gap)
                ) {
                    actions.forEach { ActionButton(it) }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(action: PanelAction) {
    val size = Modifier.heightIn(min = VoiceTokens.Touch)
    if (action.primary) {
        Button(
            onClick = action.onClick,
            modifier = size,
            colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = Color(0xFF02161B))
        ) { Text(action.label) }
    } else {
        OutlinedButton(
            onClick = action.onClick,
            modifier = size,
            border = BorderStroke(1.dp, VoiceTokens.Edge)
        ) { Text(action.label, color = OnBackground) }
    }
}

@Composable
private fun Reactor(mood: PanelView.Mood, level: () -> Float) {
    AndroidView(
        factory = { ArcReactorView(it) },
        modifier = Modifier.size(VoiceTokens.Reactor),
        update = { view ->
            view.setMood(
                when (mood) {
                    PanelView.Mood.LISTENING -> ArcReactorView.Mood.LISTENING
                    PanelView.Mood.THINKING -> ArcReactorView.Mood.THINKING
                    PanelView.Mood.SPEAKING -> ArcReactorView.Mood.SPEAKING
                    PanelView.Mood.IDLE -> ArcReactorView.Mood.IDLE
                }
            )
            view.level(level())
        },
        onRelease = { it.stop() }
    )
}

private const val COMPACT_LINES = 2

/** The expanded text takes at most this share of the screen height. */
private const val BODY_SHARE = 0.4f
