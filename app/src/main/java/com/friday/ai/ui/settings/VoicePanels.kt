package com.friday.ai.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.core.VoiceProfile
import com.friday.ai.service.FridayNotificationListener
import com.friday.ai.service.VoiceCalibrator
import com.friday.ai.service.VoskModelManager
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ErrorColor
import com.friday.ai.ui.theme.HudBackground
import com.friday.ai.ui.theme.HudButton
import com.friday.ai.ui.theme.HudLabelStyle
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.HudReadout
import com.friday.ai.ui.theme.HudStatus
import com.friday.ai.ui.theme.HudSwitchRow
import com.friday.ai.ui.theme.HudTopBar
import com.friday.ai.ui.theme.OnBackground
import com.friday.ai.ui.theme.hudFieldColors
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/* The voice panels of Settings: wake word, calibration and the owner's voice profile. */

@Composable
internal fun VoicePanel(viewModel: SettingsViewModel, say: (String) -> Unit) {
    val context = LocalContext.current
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsStateWithLifecycle()
    val modelState by viewModel.modelDownloadState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            if (Settings.canDrawOverlays(context)) {
                viewModel.onWakeWordToggle(true)
            } else {
                openOverlaySettings(context)
                say(OVERLAY_HINT)
            }
        }
    }

    HudPanel("Voz", index = 2) {
        VoiceModelState(modelState, onDownload = viewModel::downloadVoiceModel)
        HudSwitchRow(
            title = "Palavra «Friday»",
            subtitle = "Escuta a palavra de ativação e abre o painel sobre outros aplicativos",
            checked = wakeWordEnabled,
            enabled = modelState is VoskModelManager.DownloadState.Ready || wakeWordEnabled,
            onChange = { enabled ->
                when {
                    !enabled -> viewModel.onWakeWordToggle(false)
                    modelState !is VoskModelManager.DownloadState.Ready -> say("Baixe primeiro o modelo de voz")
                    !wakePermissionsGranted(context) -> permissionLauncher.launch(wakePermissions())
                    !Settings.canDrawOverlays(context) -> {
                        openOverlaySettings(context)
                        say(OVERLAY_HINT)
                    }
                    else -> {
                        viewModel.onWakeWordToggle(true)
                        // Asked once, right when it starts mattering.
                        if (isBatteryRestricted(context)) requestBatteryExemption(context)
                    }
                }
            }
        )
        WakeHealthStatus(visible = wakeWordEnabled)
        BackgroundWorkStatus(visible = wakeWordEnabled)
        HudNote(
            "Botão lateral: escolha Friday como assistente digital para chamá-lo ao manter pressionado, " +
                "mesmo sem a palavra de ativação. No Samsung: Recursos avançados → Botão lateral → " +
                "Manter pressionado → Assistente digital."
        )
        HudOutlinedButton(
            onClick = { com.friday.ai.ui.openDefaultAssistantSettings(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Escolher assistente digital")
        }
    }
}

@Composable
private fun VoiceModelState(state: VoskModelManager.DownloadState, onDownload: () -> Unit) {
    when (state) {
        is VoskModelManager.DownloadState.NotDownloaded -> {
            HudReadout("Modelo de ativação", "NÃO INSTALADO", valueColor = ErrorColor)
            HudOutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Baixar modelo de voz (~45 MB)")
            }
        }
        is VoskModelManager.DownloadState.Downloading -> {
            HudReadout("Modelo de ativação", "BAIXANDO ${state.progress}%")
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        is VoskModelManager.DownloadState.Extracting -> {
            HudReadout("Modelo de ativação", "EXTRAINDO")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is VoskModelManager.DownloadState.Ready -> HudReadout("Modelo de ativação", "PRONTO", valueColor = ArcCyan)
        is VoskModelManager.DownloadState.Error -> {
            HudReadout("Modelo de ativação", "ERRO", valueColor = ErrorColor)
            HudNote(state.message)
            HudOutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Tentar baixar novamente") }
        }
    }
}

@Composable
internal fun CalibrationPanel(viewModel: SettingsViewModel) {
    val phase by viewModel.calibrationPhase.collectAsStateWithLifecycle()
    val energy by viewModel.calibrationEnergy.collectAsStateWithLifecycle()
    val isCalibrated by viewModel.isCalibrated.collectAsStateWithLifecycle()
    val result by viewModel.calibrationResult.collectAsStateWithLifecycle()
    val message by viewModel.calibrationMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Without the microphone the run would only report it unavailable; asking first is kinder.
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.startCalibration()
    }

    HudPanel("Calibração", index = 3) {
        HudNote("Ajusta o Friday à sua voz e ao ruído ambiente para reconhecer a ativação com precisão.")
        val current = phase
        if (current != null) {
            Text(phaseText(current), style = MaterialTheme.typography.titleMedium, color = ArcCyan)
            LinearProgressIndicator(progress = { phaseProgress(current) }, modifier = Modifier.fillMaxWidth())
            HudReadout("Nível", "%.0f".format(energy))
            HudOutlinedButton(onClick = viewModel::cancelCalibration, modifier = Modifier.fillMaxWidth()) {
                Text("Cancelar")
            }
        } else {
            message?.let { HudNote(it) }
            HudButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.startCalibration() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isCalibrated || message != null) "Calibrar novamente" else "Calibrar")
            }
        }
        val r = result
        if (isCalibrated && current == null && r != null) {
            HudReadout("Ruído ambiente", "%.0f".format(r.ambientNoise))
            HudReadout("Sua voz", "%.0f".format(r.speechEnergy))
            HudReadout("Limite de ativação", "%.0f".format(r.wakeWordThreshold))
            HudReadout("Limite de fala", "%.0f".format(r.whisperThreshold))
        } else if (isCalibrated && current == null) {
            HudReadout("Status", "CALIBRADO", valueColor = ArcCyan)
        }
    }
}

private fun phaseText(phase: VoiceCalibrator.Phase): String = when (phase) {
    VoiceCalibrator.Phase.MEASURING_SILENCE -> "Silêncio… medindo o ruído ambiente"
    VoiceCalibrator.Phase.WAITING_FOR_SPEECH -> "Agora diga algo, como «Friday, abra a câmera»"
    VoiceCalibrator.Phase.MEASURING_SPEECH -> "Continue… medindo sua voz"
    VoiceCalibrator.Phase.DONE -> "Concluído"
}

private fun phaseProgress(phase: VoiceCalibrator.Phase): Float = when (phase) {
    VoiceCalibrator.Phase.MEASURING_SILENCE -> 0.25f
    VoiceCalibrator.Phase.WAITING_FOR_SPEECH -> 0.5f
    VoiceCalibrator.Phase.MEASURING_SPEECH -> 0.75f
    VoiceCalibrator.Phase.DONE -> 1f
}

@Composable
internal fun VoiceProfilePanel(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val profile by viewModel.voiceProfile.collectAsStateWithLifecycle()
    val enrolment by viewModel.enrolment.collectAsStateWithLifecycle()
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.enrollVoice()
    }

    HudPanel("Perfil de voz", index = 4) {
        val current = profile
        HudReadout(
            "Acesso",
            when {
                current == null -> "QUALQUER VOZ"
                !current.coversCommands -> "SÓ ATIVAÇÃO"
                else -> "SÓ VOCÊ"
            },
            valueColor = if (current?.coversCommands == true) ArcCyan else ErrorColor
        )
        HudNote(profileText(current))
        // The last checks, so a rejection has a reason: the score against the bar.
        val checks by com.friday.ai.service.voice.VoiceChecks.recent.collectAsStateWithLifecycle()
        checks.take(3).forEach { c ->
            HudReadout(
                if (c.what == "wake word") "Palavra «Friday»" else "Comando",
                "%.2f / %.2f %s".format(c.score, c.threshold, if (c.passed) "✓" else "✗"),
                valueColor = if (c.passed) ArcCyan else ErrorColor
            )
        }
        if (enrolment.total > 0) {
            LinearProgressIndicator(
                progress = { enrolment.recorded.toFloat() / enrolment.total },
                modifier = Modifier.fillMaxWidth()
            )
            Text("${enrolment.recorded} / ${enrolment.total}", style = HudLabelStyle, color = ArcCyan)
        }
        enrolment.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enrolment.recording) ArcCyan else OnBackground
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.enrollVoice() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = !enrolment.recording,
                modifier = Modifier.weight(1f)
            ) { Text(if (current == null) "Gravar voz" else "Gravar novamente") }
            if (current != null) {
                HudOutlinedButton(onClick = viewModel::clearVoiceProfile, enabled = !enrolment.recording) {
                    Text("Redefinir")
                }
            }
        }
        HudNote(
            "Grave sua voz no ambiente onde costuma usar o Friday. Um perfil gravado no silêncio pode falhar no carro."
        )
    }
}

private fun profileText(profile: VoiceProfile.Profile?): String = when {
    profile == null ->
        "Por enquanto o Friday responde a qualquer voz. Grave a sua usando " +
            "${VoiceProfile.ENROLMENT_PHRASES.size} frases curtas para evitar que responda a outras pessoas."
    !profile.coversCommands ->
        "O perfil foi gravado apenas para a palavra «Friday», então só a ativação é verificada. " +
            "Grave novamente para verificar também os comandos seguintes."
    else ->
        "A ativação e os comandos são verificados. Frases: ${profile.sampleCount}, " +
            "limite %.2f, semelhança das gravações %.2f.".format(profile.threshold, profile.cohesion)
}

private const val OVERLAY_HINT = "Permita «Sobrepor a outros apps» e ative novamente"

private fun wakePermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun wakePermissionsGranted(context: Context): Boolean = wakePermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun openOverlaySettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    )
}
