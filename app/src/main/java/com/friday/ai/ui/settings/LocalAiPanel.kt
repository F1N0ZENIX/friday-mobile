package com.friday.ai.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** Local model library. Files live in app-private storage and survive normal app updates. */
@Composable
fun LocalAiPanel() {
    val context = LocalContext.current
    val store = remember(context) { LocalModelStore(context) }
    val preferences = remember(context) { LocalAiPreferences(context) }
    val installed = remember { mutableStateListOf<LocalModelStore.InstalledModel>() }
    var mode by remember { mutableStateOf(preferences.mode) }
    var active by remember { mutableStateOf(store.activeFile()?.name) }
    var message by remember { mutableStateOf<String?>(null) }

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
        HudNote("Escolha Automático, Local ou Groq. Os modelos são opcionais, ficam no armazenamento privado do Friday e não são incluídos no APK.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                LocalAiPreferences.Mode.AUTOMATIC to "Automático",
                LocalAiPreferences.Mode.LOCAL to "Local",
                LocalAiPreferences.Mode.GROQ to "Groq"
            ).forEach { (value, label) ->
                HudOutlinedButton(
                    onClick = { mode = value; preferences.mode = value },
                    selected = mode == value,
                    modifier = Modifier.weight(1f)
                ) { Text(label, maxLines = 1) }
            }
        }
        HudReadout("Modelo ativo", active ?: "NENHUM MODELO INSTALADO", valueColor = if (active != null) ArcCyan else OnBackground)
        Text("Instalados", style = MaterialTheme.typography.titleSmall, color = ArcCyan)
        if (installed.isEmpty()) HudNote("Nenhum modelo instalado. Você ainda pode usar comandos locais básicos quando essa integração estiver ativada.")
        installed.forEach { model ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text(model.fileName + if (model.fileName == active) " · ATIVO" else "", color = OnBackground)
                Text(String.format(Locale.getDefault(), "%.1f MB · SHA-256 %s", model.bytes / 1048576.0, model.sha256.take(12)), style = MaterialTheme.typography.bodySmall, color = ArcCyan.copy(alpha = 0.75f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudOutlinedButton(
                        onClick = { if (store.select(model.fileName)) { active = model.fileName; message = "Modelo selecionado." } },
                        enabled = model.fileName != active,
                        modifier = Modifier.weight(1f)
                    ) { Text("Usar") }
                    HudOutlinedButton(
                        onClick = { store.delete(model.fileName); message = "Modelo removido."; reload() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Excluir") }
                }
            }
        }
        HudButton(onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Importar arquivo GGUF")
        }
        Text("Catálogo recomendado", style = MaterialTheme.typography.titleSmall, color = ArcCyan)
        LocalModelCatalog.recommended.forEach { model ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                Text(model.name + " · " + model.quantization, color = OnBackground)
                Text(model.sizeLabel + " — " + model.description, style = MaterialTheme.typography.bodySmall, color = ArcCyan.copy(alpha = 0.75f))
                HudOutlinedButton(
                    onClick = {
                        val url = "https://huggingface.co/${model.repository}"
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        message = "Abra os arquivos GGUF do repositório e importe o arquivo baixado."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Abrir no Hugging Face") }
            }
        }
        HudNote("O catálogo é manual. A página do Hugging Face abre somente quando você toca no botão; nenhum download acontece sozinho.")
        message?.let { Text(it, color = ArcCyan, style = MaterialTheme.typography.bodySmall) }
    }
}
