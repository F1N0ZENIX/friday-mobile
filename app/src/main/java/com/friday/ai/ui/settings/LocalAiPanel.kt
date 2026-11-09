package com.friday.ai.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.friday.ai.localai.LocalAiPreferences
import com.friday.ai.localai.LocalModel
import com.friday.ai.localai.LocalModelCatalog
import com.friday.ai.localai.LocalModelStore
import com.friday.ai.ui.onboarding.AiOnboardingScreen
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun LocalAiPanel() {
    val context = LocalContext.current
    val preferences = remember(context) { LocalAiPreferences(context) }
    val store: LocalModelStore = koinInject()
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(preferences.mode) }
    var state by remember { mutableStateOf(LocalAiState()) }
    var showReconfiguration by remember { mutableStateOf(false) }

    fun refresh() = scope.launch { state = state.refresh(store) }
    fun download(model: LocalModel) = scope.launch {
        state = state.copy(busy = true, progress = 0, error = null)
        state = state.download(model, store) { percent ->
            scope.launch {
                if (state.busy) state = state.copy(progress = percent)
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    HudPanel("IA e privacidade", index = 2) {
        HudNote(
            "No celular usa um modelo GGUF baixado por você; conversas ficam no aparelho. " +
                "Clima e outros recursos podem usar seus próprios serviços."
        )
        LocalModelCatalog.recommended.forEach { model ->
            val installed = state.models.firstOrNull { it.fileName == model.fileName }
            HudOutlinedButton(
                onClick = {
                    if (installed != null) {
                        store.select(installed.fileName)
                        refresh()
                    } else {
                        download(model)
                    }
                },
                enabled = !state.busy,
                selected = installed?.active == true,
                modifier = Modifier.fillMaxWidth()
            ) {
                val label = if (installed == null) "Instalar" else "Selecionar"
                Text("$label ${model.name} (${model.sizeLabel})")
            }
        }
        LocalModelStatus(state)
        HudOutlinedButton(
            onClick = { showReconfiguration = true },
            enabled = !state.busy,
            selected = mode == LocalAiPreferences.Mode.LOCAL,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Alterar modo ou chave")
        }
    }
    ReconfigurationDialog(showReconfiguration, mode) {
        mode = preferences.mode
        showReconfiguration = false
    }
}

@Composable
private fun LocalModelStatus(state: LocalAiState) {
    if (state.busy) HudNote("Baixando e verificando: ${state.progress ?: 0}%")
    if (state.models.isEmpty()) {
        HudNote("Nenhum modelo local instalado ainda.")
    } else {
        HudNote("${state.models.count { it.active }} modelo local selecionado.")
    }
    state.error?.let { HudNote(it) }
}

@Composable
private fun ReconfigurationDialog(
    visible: Boolean,
    mode: LocalAiPreferences.Mode,
    onComplete: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onComplete,
        confirmButton = {},
        text = { AiOnboardingScreen(initialMode = mode, onComplete = onComplete) }
    )
}

private data class LocalAiState(
    val models: List<LocalModelStore.InstalledModel> = emptyList(),
    val busy: Boolean = false,
    val progress: Int? = null,
    val error: String? = null
) {
    suspend fun refresh(store: LocalModelStore): LocalAiState = try {
        copy(models = store.installed())
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        copy(error = "Não foi possível ler os modelos locais.")
    }

    suspend fun download(
        model: LocalModel,
        store: LocalModelStore,
        onProgress: (Int) -> Unit
    ): LocalAiState = try {
        val saved = store.download(model, onProgress)
        store.select(saved.fileName)
        copy(models = store.installed(), busy = false, progress = null)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        copy(error = exception.message ?: "Falha ao baixar o modelo.", busy = false, progress = null)
    }
}
