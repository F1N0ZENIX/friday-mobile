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

/**
 * Friday's settings as a stack of numbered HUD panels, one per system:
 * intelligence, voice, calibration, voice profile, chats, mail, learned
 * commands, maps, memory and moving to another phone.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenDashboard: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenModes: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel()
) {
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { text -> scope.launch { snackbarHost.showSnackbar(text) } }
    LaunchedEffect(Unit) { viewModel.refreshLearned() }

    HudBackground {
        Column(Modifier.fillMaxSize()) {
            HudTopBar(
                title = "SISTEMAS",
                status = HudStatus("Configurações"),
                navigation = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = OnBackground)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Filled.MonitorHeart, contentDescription = "Diagnóstico", tint = ArcCyan)
                    }
                }
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(4.dp))
                IntelligencePanel(viewModel)
                VoicePanel(viewModel, say)
                CalibrationPanel(viewModel)
                VoiceProfilePanel(viewModel)
                ChatsPanel(viewModel)
                MailPanel(viewModel)
                LearnedPanel(viewModel)
                MapsPanel(viewModel)
                HudPanel("Modos", index = 9) {
                    HudNote(
                        "Несколько действий под одним именем, созданные голосом: «создай режим отдыха: " +
                            "беззвучный и яркость на минимум». «Выключи режим» возвращает всё как было."
                    )
                    HudOutlinedButton(onClick = onOpenModes, modifier = Modifier.fillMaxWidth()) {
                        Text("Abrir modos")
                    }
                }
                HudPanel("Memória", index = 10) {
                    HudNote("Карта того, что Пятница знает о вас: факты и разговоры, из которых они взялись.")
                    HudOutlinedButton(onClick = onOpenDashboard, modifier = Modifier.fillMaxWidth()) {
                        Text("Что Пятница знает обо мне")
                    }
                }
                HudPanel("Transferir para outro celular", index = 11) { TransferSection() }
                LocationPanel()
                QuickLinksPanel()
                LockPanel()
                Spacer(Modifier.height(24.dp))
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
            Snackbar(snackbarData = data)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntelligencePanel(viewModel: SettingsViewModel) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val model by viewModel.model.collectAsStateWithLifecycle()
    val isSaved by viewModel.isSaved.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }

    HudPanel("Inteligência · Groq", index = 1) {
        HudReadout(
            "Status",
            if (apiKey.isBlank()) "SEM CHAVE" else "CHAVE CONFIGURADA",
            valueColor = if (apiKey.isBlank()) ErrorColor else ArcCyan
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text("Chave da API Groq") },
            placeholder = { Text("gsk_…") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = hudFieldColors()
        )
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = model,
                onValueChange = {},
                readOnly = true,
                label = { Text("Modelo") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = hudFieldColors(),
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                models.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            viewModel.onModelChange(name)
                            expanded = false
                        }
                    )
                }
            }
        }
        HudButton(onClick = viewModel::saveSettings, modifier = Modifier.fillMaxWidth()) {
            Text(if (isSaved) "Salvo" else "Salvar")
        }
        HudNote("Ключ хранится зашифрованным (Android Keystore) и уходит только в Groq.")
    }
}

@Composable
private fun ChatsPanel(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val announceCalls by viewModel.announceCalls.collectAsStateWithLifecycle()
    // Needs the notification listener; turning it on without it goes
    // straight to the screen that grants it.
    val needsAccess = { on: Boolean ->
        if (on && !FridayNotificationListener.isEnabled(context)) FridayNotificationListener.openSettings(context)
    }
    HudPanel("Chamadas e mensagens", index = 5) {
        HudNote(
            "Пятница собирает сообщения WhatsApp, Telegram и SMS, не читая их вслух, и пропускает " +
                "рекламу. Спросите «есть непрочитанные?» — назовёт, кто писал; ответ: «ответь ему, что еду». " +
                "Нужен доступ к уведомлениям."
        )
        HudSwitchRow(
            title = "Anunciar chamadas",
            subtitle = "Говорит, кто звонит: телефон, WhatsApp или Telegram",
            checked = announceCalls,
            onChange = { needsAccess(it); viewModel.onAnnounceCallsChange(it) }
        )
    }
}

@Composable
private fun MailPanel(viewModel: SettingsViewModel) {
    val gmail by viewModel.gmail.collectAsStateWithLifecycle()
    // Google's consent screen. A cancelled screen comes back without data,
    // which the view model reports as "Вход отменён".
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onGmailConsent(result.data, completed = result.resultCode == android.app.Activity.RESULT_OK)
    }
    LaunchedEffect(gmail.consent) {
        gmail.consent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }
    HudPanel("E-mail · Gmail", index = 6) {
        HudReadout(
            "Conta",
            gmail.account ?: "NÃO CONECTADA",
            valueColor = if (gmail.account != null) ArcCyan else OnBackground
        )
        HudNote(
            if (gmail.account != null) {
                "Пятница проверяет входящие, читает письма и отвечает — отправляет только после вашего «да»."
            } else {
                "После подключения: «есть новые письма?», «прочитай письмо от Ивана», «ответь Ивану: буду в пять»."
            }
        )
        gmail.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ArcCyan) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(onClick = viewModel::connectGmail, enabled = !gmail.busy, modifier = Modifier.weight(1f)) {
                Text(if (gmail.account == null) "Conectar Gmail" else "Reconectar")
            }
            if (gmail.account != null) {
                HudOutlinedButton(onClick = viewModel::disconnectGmail, enabled = !gmail.busy) { Text("Desconectar") }
            }
        }
    }
}

@Composable
private fun LearnedPanel(viewModel: SettingsViewModel) {
    val learned by viewModel.learnedCommands.collectAsStateWithLifecycle()
    HudPanel("Comandos aprendidos", index = 7) {
        HudNote(
            "Фразы, которые ИИ однажды разобрал, а теперь Пятница выполняет сама, без ИИ. " +
                "Скажите «нет, не то» сразу после ошибки — и она забудет; или уберите здесь."
        )
        if (learned.isEmpty()) HudReadout("Aprendidos", "0")
        learned.forEach { (phrase, action) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("«$phrase»", style = MaterialTheme.typography.bodyLarge, color = OnBackground)
                    Text(action, style = HudLabelStyle, color = ArcCyan.copy(alpha = 0.7f))
                }
                IconButton(onClick = { viewModel.forgetLearned(phrase) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Esquecer «$phrase»",
                        tint = ArcCyan.copy(alpha = 0.7f)
                    )
                }
            }
        }
        if (learned.isNotEmpty()) {
            HudOutlinedButton(onClick = viewModel::forgetAllLearned) { Text("Esquecer todos") }
        }
    }
}

@Composable
private fun MapsPanel(viewModel: SettingsViewModel) {
    val provider by viewModel.mapsProvider.collectAsStateWithLifecycle()
    HudPanel("Mapas", index = 8) {
        HudNote("Чем открывать «найди ближайшую кофейню / банк / …». «Как в телефоне» — карты по умолчанию.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Celular", "google" to "Google", "yandex" to "Yandex").forEach { (value, label) ->
                Box(Modifier.weight(1f)) {
                    HudOutlinedButton(
                        onClick = { viewModel.onMapsProviderChange(value) },
                        selected = provider == value,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}
