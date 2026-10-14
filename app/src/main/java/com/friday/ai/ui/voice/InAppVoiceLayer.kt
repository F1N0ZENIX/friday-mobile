package com.friday.ai.ui.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.friday.ai.core.voice.VoicePresentation
import com.friday.ai.service.voice.VoiceUi

/**
 * The voice panel inside Friday's own screen. While the app is in front the
 * floating window is not drawn ([com.friday.ai.core.voice.Surfaces]), so a
 * spoken exchange shows here instead of in a second panel on top of the chat.
 */
@Composable
fun BoxScope.InAppVoiceLayer() {
    val panel by VoiceUi.store.state.collectAsState()
    val level by VoiceUi.store.level.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val current = panel
    LaunchedEffect(current?.session) { expanded = false }
    if (current == null) return
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 88.dp)
    ) {
        VoicePanelCard(
            view = VoicePresentation.open(current),
            level = { level },
            expanded = expanded,
            onExpand = {
                expanded = true
                VoiceUi.store.touched()
            },
            onClose = { VoiceUi.close() },
            actions = if (expanded) listOf(PanelAction("Закрыть", onClick = { VoiceUi.close() })) else emptyList(),
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
