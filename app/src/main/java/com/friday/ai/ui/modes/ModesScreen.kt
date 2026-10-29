package com.friday.ai.ui.modes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.core.modes.Mode
import com.friday.ai.core.modes.ModeEvent
import com.friday.ai.core.modes.Trigger
import com.friday.ai.core.modes.describeEvent
import com.friday.ai.core.modes.ModeSteps
import com.friday.ai.core.modes.Schedule
import com.friday.ai.core.modes.ScheduleMath
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ArcCyanDim
import com.friday.ai.ui.theme.HudBackground
import com.friday.ai.ui.theme.HudButton
import com.friday.ai.ui.theme.HudLabelStyle
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.HudReadout
import com.friday.ai.ui.theme.HudStatus
import com.friday.ai.ui.theme.HudTopBar
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.OnSurfaceMuted
import com.friday.ai.ui.theme.StatusDot
import com.friday.ai.ui.theme.hudFrame
import org.koin.androidx.compose.koinViewModel

/** The owner's modes: what each does, whether it's on, and buttons for what a phrase would do. */
@Composable
fun ModesScreen(onNavigateBack: () -> Unit, viewModel: ModesViewModel = koinViewModel()) {
    val modes by viewModel.modes.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    ModesLayout(
        modes, status, busy, onNavigateBack, viewModel::run, viewModel::stop, viewModel::delete,
        schedules = schedules, onUnschedule = viewModel::unschedule,
        events = events, onRemoveEvent = viewModel::removeEvent,
        onTag = viewModel::tag
    )
}

/** Without the view model: what the screenshot test renders. */
@Composable
internal fun ModesLayout(
    modes: List<Mode>?,
    status: String?,
    busy: String?,
    onNavigateBack: () -> Unit,
    onRun: (Mode) -> Unit,
    onStop: (Mode) -> Unit,
    onDelete: (Mode) -> Unit,
    schedules: Map<String, List<Schedule>> = emptyMap(),
    onUnschedule: (Schedule) -> Unit = {},
    events: Map<String, List<ModeEvent>> = emptyMap(),
    onRemoveEvent: (ModeEvent) -> Unit = {},
    onTag: (Mode) -> Unit = {}
) {
    var deleting by remember { mutableStateOf<Mode?>(null) }
    HudBackground {
        Column(Modifier.fillMaxSize()) {
            HudTopBar(
                title = "MODOS",
                status = when {
                    modes == null -> HudStatus("Carregando")
                    modes.isEmpty() -> HudStatus("Nenhum ainda")
                    else -> HudStatus("Salvo: ${modes.size} · ativos: ${modes.count { it.active }}")
                },
                navigation = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = OnBackground)
                    }
                }
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                status?.let { item { StatusStrip(it) } }
                if (modes != null && modes.isEmpty()) item { HowTo() }
                items(modes.orEmpty(), key = { it.id }) { mode ->
                    ModePanel(
                        mode, index = modes.orEmpty().indexOf(mode) + 1, busy = busy == mode.id,
                        onRun = { onRun(mode) }, onStop = { onStop(mode) }, onDelete = { deleting = mode },
                        schedules = schedules[mode.id].orEmpty(), onUnschedule = onUnschedule,
                        events = events[mode.id].orEmpty(), onRemoveEvent = onRemoveEvent,
                        onTag = { onTag(mode) }
                    )
                }
                if (!modes.isNullOrEmpty()) {
                    item { HudNote("Crie um modo por voz: «crie um modo... e diga o que ele faz».") }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    deleting?.let { mode ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir modo ${mode.name}?") },
            text = {
                Text(
                    if (mode.active) "Este modo está ativo: as configurações alteradas não serão restauradas."
                    else "As ações deste modo serão apagadas."
                )
            },
            confirmButton = { TextButton(onClick = { onDelete(mode); deleting = null }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ModePanel(
    mode: Mode,
    index: Int,
    busy: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    schedules: List<Schedule> = emptyList(),
    onUnschedule: (Schedule) -> Unit = {},
    events: List<ModeEvent> = emptyList(),
    onRemoveEvent: (ModeEvent) -> Unit = {},
    onTag: () -> Unit = {}
) {
    HudPanel("Modo ${mode.name}", index = index, accent = if (mode.active) ArcCyan else ArcCyanDim) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (mode.active) ArcCyan else OnSurfaceMuted, live = mode.active)
            Spacer(Modifier.width(6.dp))
            Text(
                if (mode.active) "ATIVADO" else "DESATIVADO",
                style = HudLabelStyle,
                color = if (mode.active) ArcCyan else OnSurfaceMuted
            )
        }
        Text(
            "«${mode.description}»",
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = OnSurfaceMuted
        )
        mode.steps.forEachIndexed { i, step ->
            Row {
                Text("%02d".format(i + 1), style = HudLabelStyle, color = ArcCyan.copy(alpha = 0.6f))
                Spacer(Modifier.width(10.dp))
                Text(
                    ModeSteps.describe(step, russian = true),
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnBackground
                )
            }
        }
        if (schedules.isNotEmpty() || events.isNotEmpty()) Triggers(schedules, onUnschedule, events, onRemoveEvent)
        HudReadout("Execuções", mode.runCount.toString())
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode.active) {
                HudButton(onClick = onStop, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Desativar") }
            } else {
                HudButton(onClick = onRun, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Ativar") }
            }
            HudOutlinedButton(onClick = onTag, enabled = !busy) { Text("Etiqueta") }
            HudOutlinedButton(onClick = onDelete, enabled = !busy) { Text("Excluir") }
        }
    }
}

/** What starts the mode by itself; one group, so the panel's spacing doesn't open gaps around the 48dp buttons. */
@Composable
private fun Triggers(
    schedules: List<Schedule>,
    onUnschedule: (Schedule) -> Unit,
    events: List<ModeEvent>,
    onRemoveEvent: (ModeEvent) -> Unit
) {
    Column {
        schedules.sortedBy { it.exit }.forEach { s ->
            TriggerRow(
                Icons.Filled.Schedule,
                verb(s.exit) + ScheduleMath.describe(s.days, s.time, russian = true),
                onRemove = { onUnschedule(s) }
            )
        }
        events.forEach { e ->
            val icon = when (e.trigger) {
                Trigger.CHARGER -> Icons.Filled.BatteryChargingFull
                Trigger.WIFI -> Icons.Filled.Wifi
                Trigger.BLUETOOTH -> Icons.Filled.Bluetooth
            }
            TriggerRow(icon, verb(e.exit) + describeEvent(e, russian = true), onRemove = { onRemoveEvent(e) })
        }
    }
}

private fun verb(exit: Boolean) = if (exit) "Desativa " else "Ativa "

@Composable
private fun TriggerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = ArcCyan,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remover: $text", tint = OnSurfaceMuted)
        }
    }
}

@Composable
private fun StatusStrip(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudFrame()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("FRIDAY", style = HudLabelStyle, color = ArcCyan)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = OnBackground)
    }
}

@Composable
private fun HowTo() {
    HudPanel("Como criar um modo") {
        HudNote(
            "Um modo reúne várias ações com um único nome. Crie-o por voz, " +
                "e o Friday interpretará a descrição e explicará as ações."
        )
        Example("«Crie um modo triste que toque músicas tristes no Spotify.»")
        Example("«Crie um modo descanso: silencioso, não perturbe e brilho mínimo.»")
        HudNote("Depois, diga «modo descanso» para ativar e «desative o modo descanso» para restaurar.")
        HudNote("Por horário: «ative o modo descanso todos os dias às 23h» ou «desative o modo descanso às 7h nos dias úteis».")
        HudNote("Por evento: «quando conectar ao carro, ative o modo direção» ou «quando carregar, ative o modo sono».")
        HudNote("Etiqueta NFC: «associe o modo descanso à etiqueta» para alternar o modo com um toque.")
    }
}

@Composable
private fun Example(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = ArcCyan)
}
