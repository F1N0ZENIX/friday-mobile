@file:Suppress("MaxLineLength")

package com.friday.ai.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.friday.ai.localai.LocalAiPreferences
import com.friday.ai.localai.LocalModelCatalog
import com.friday.ai.localai.LocalModelStore
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.HudButton
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.HudReadout
import com.friday.ai.ui.theme.OnBackground
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun LocalAiPanel() {
    val context = LocalContext.current
    val store = remember(context) { LocalModelStore(context) }
    val preferences = remember(context) { LocalAiPreferences(context) }
    val scope = rememberCoroutineScope()
    val installed = remember { mutableStateListOf<LocalModelStore.InstalledModel>() }
    var mode by remember { mutableStateOf(preferences.mode) }
    var active by remember { mutableStateOf(store.activeFile()?.name) }
    var message by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }

    fun reload() {
        installed.clear()
        installed.addAll(store.installed())
        active = store.activeFile()?.name
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast(':') ?: "modelo-importado.gguf"
            runCatching { store.import(uri, name) }
                .onSuccess { message = "Modelo importado e validado: ${it.fileName}"; reload() }
                .onFailure { message = it.message ?: "Falha ao importar o modelo." }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { reload() }

    HudPanel("IA local · Modelos", index = 2) {
        HudNote("Baixe e instale modelos GGUF diretamente pelo Friday. Os arquivos ficam no armazenamento privado e são preservados ao atualizar o aplicativo.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(LocalAiPreferences.Mode.AUTOMATIC to "Automático", LocalAiPreferences.Mode.LOCAL to "Local", LocalAiPreferences.Mode.GROQ to "Groq")
                .forEach { (value, label) ->
                    HudOutlinedButton(onClick = { mode = value; preferences.mode = value }, selected = mode == value,
                        modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                }
        }
        HudReadout("Modelo ativo", active ?: "NENHUM MODELO INSTALADO",
            valueColor = if (active != null) ArcCyan else OnBackground)
        Text("Modelos instalados", style = MaterialTheme.typography.titleSmall, color = ArcCyan)
        if (installed.isEmpty()) HudNote("Nenhum modelo instalado.")
        installed.forEach { model ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text(model.fileName + if (model.fileName == active) " · ATIVO" else "", color = OnBackground)
                Text(String.format(Locale.getDefault(), "%.1f MB · SHA-256 %s", model.bytes / 1048576.0, model.sha256.take(12)),
                    style = MaterialTheme.typography.bodySmall, color = ArcCyan.copy(alpha = 0.75f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudOutlinedButton(onClick = { if (store.select(model.fileName)) { active = model.fileName; message = "Modelo selecionado." } },
                        enabled = model.fileName != active, modifier = Modifier.weight(1f)) { Text("Ativar") }
                    HudOutlinedButton(onClick = { store.delete(model.fileName); message = "Modelo removido."; reload() },
                        modifier = Modifier.weight(1f)) { Text("Excluir") }
                }
            }
        }
        HudButton(onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
            modifier = Modifier.fillMaxWidth()) { Text("Importar GGUF do dispositivo") }
        Text("Catálogo Hugging Face", style = MaterialTheme.typography.titleSmall, color = ArcCyan)
        LocalModelCatalog.recommended.forEach { model ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                Text(model.name + " · " + model.quantization, color = OnBackground)
                Text(model.sizeLabel + " — " + model.description, style = MaterialTheme.typography.bodySmall,
                    color = ArcCyan.copy(alpha = 0.75f))
                HudOutlinedButton(
                    onClick = {
                        downloading = model.id
                        progress = 0
                        message = "Conectando ao Hugging Face…"
                        scope.launch {
                            runCatching { store.download(model) { progress = it } }
                                .onSuccess { message = "Instalado e verificado: ${it.fileName}"; reload() }
                                .onFailure { message = it.message ?: "Falha no download." }
                            downloading = null
                        }
                    },
                    enabled = downloading == null && installed.none { it.fileName == model.fileName },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (downloading == model.id) "Baixando…" else if (installed.any { it.fileName == model.fileName }) "Já instalado" else "Baixar e instalar no Friday")
                }
                if (downloading == model.id) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
        HudNote("O download só começa quando você toca no botão. É necessária conexão com a internet e espaço livre suficiente.")
        message?.let { Text(it, color = ArcCyan, style = MaterialTheme.typography.bodySmall) }
    }
}
