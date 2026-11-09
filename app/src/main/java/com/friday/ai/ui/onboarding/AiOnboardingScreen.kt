package com.friday.ai.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.friday.ai.core.AiSetup
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import com.friday.ai.localai.LocalAiPreferences
import com.friday.ai.localai.LocalModel
import com.friday.ai.localai.LocalModelCatalog
import com.friday.ai.localai.LocalModelStore
import com.friday.ai.service.FridayWakeWordService
import com.friday.ai.ui.theme.HudButton
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun AiOnboardingScreen(initialMode: LocalAiPreferences.Mode? = null, onComplete: () -> Unit) {
    val context = LocalContext.current
    val preferences: UserPreferenceDao = koinInject()
    val store: LocalModelStore = koinInject()
    val localPreferences = remember(context) { LocalAiPreferences(context) }
    val scope = rememberCoroutineScope()
    var cloud by remember { mutableStateOf(initialMode?.let { it == LocalAiPreferences.Mode.GROQ }) }
    var key by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(OnboardingState()) }

    fun refreshInstalled() = scope.launch {
        state = state.refreshInstalled(store)
    }
    fun download(model: LocalModel) = scope.launch {
        state = state.copy(busy = true, progress = 0, error = null)
        state = state.download(model, store) { percent ->
            scope.launch {
                if (state.busy) state = state.copy(progress = percent)
            }
        }
    }
    fun finish(mode: String) {
        localPreferences.mode = LocalAiPreferences.Mode.valueOf(mode)
        context.getSharedPreferences("friday_ai_setup", Context.MODE_PRIVATE)
            .edit()
            .putString(AiSetup.PREF_MODE, mode)
            .putBoolean(AiSetup.PREF_COMPLETE, true)
            .apply()
        if (mode == AiSetup.MODE_LOCAL) FridayWakeWordService.stop(context)
        onComplete()
    }

    LaunchedEffect(Unit) { refreshInstalled() }
    Column(
        Modifier.fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        HudPanel("Como o Friday usa IA", index = 1) {
            ModeButtons(
                busy = state.busy,
                onCloud = { cloud = true; state = state.clearError() },
                onLocal = { cloud = false; state = state.clearError(); refreshInstalled() }
            )
            when (cloud) {
                true -> CloudSetup(
                    key = key,
                    busy = state.busy,
                    onKeyChange = { key = it; state = state.clearError() },
                    onSave = {
                        if (!AiSetup.validGroqKey(key)) {
                            state = state.copy(error = "Use uma chave Groq no formato gsk_… (sem espaços).")
                        } else {
                            state = state.copy(busy = true)
                            scope.launch {
                                state = state.saveGroqKey(preferences, key, onSuccess = {
                                    finish(AiSetup.MODE_GROQ)
                                })
                            }
                        }
                    }
                )
                false -> LocalSetup(
                    state = state,
                    onModelClick = { model ->
                        val installed = state.installed.any { it.fileName == model.fileName }
                        if (installed) {
                            if (store.select(model.fileName)) refreshInstalled()
                        } else {
                            download(model)
                        }
                    },
                    onFinish = { finish(AiSetup.MODE_LOCAL) }
                )
                null -> Unit
            }
            state.error?.let { HudNote(it) }
        }
    }
}

@Composable
private fun ModeButtons(busy: Boolean, onCloud: () -> Unit, onLocal: () -> Unit) {
    HudNote("Escolha agora; dá para mudar depois em Configurações.")
    HudButton(onClick = onCloud, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Nuvem (Groq)")
    }
    HudNote("Usa sua própria chave Groq para conversas, voz e análises.")
    HudButton(onClick = onLocal, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("No celular")
    }
}

@Composable
private fun CloudSetup(
    key: String,
    busy: Boolean,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit
) {
    OutlinedTextField(
        value = key,
        onValueChange = onKeyChange,
        label = { Text("Sua chave da API Groq") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    HudButton(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "Salvando…" else "Salvar e continuar")
    }
}

@Composable
private fun LocalSetup(
    state: OnboardingState,
    onModelClick: (LocalModel) -> Unit,
    onFinish: () -> Unit
) {
    HudNote(
        "IA local funciona sem enviar sua conversa à nuvem. Baixe uma vez pela internet. " +
            "O modelo leve tem qualidade limitada para português e pedidos básicos."
    )
    LocalModelCatalog.recommended.forEach { model ->
        val installed = state.installed.firstOrNull { it.fileName == model.fileName }
        HudOutlinedButton(
            onClick = { onModelClick(model) },
            enabled = !state.busy,
            selected = installed?.active == true,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (installed == null) "Baixar ${model.name} (${model.sizeLabel})" else "Usar ${model.name}")
        }
        HudNote(model.description)
    }
    if (state.busy) HudNote("Baixando e verificando: ${state.progress ?: 0}%")
    if (state.installed.any { it.active }) {
        HudButton(onClick = onFinish, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Usar IA no celular")
        }
    } else {
        HudNote("Baixe ou escolha um modelo instalado para continuar no modo local.")
    }
}

private data class OnboardingState(
    val installed: List<LocalModelStore.InstalledModel> = emptyList(),
    val busy: Boolean = false,
    val progress: Int? = null,
    val error: String? = null
) {
    suspend fun refreshInstalled(store: LocalModelStore): OnboardingState = try {
        copy(installed = store.installed())
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        copy(error = "Não foi possível ler os modelos instalados.")
    }

    suspend fun download(
        model: LocalModel,
        store: LocalModelStore,
        onProgress: (Int) -> Unit
    ): OnboardingState = try {
        val saved = store.download(model, onProgress)
        store.select(saved.fileName)
        copy(installed = store.installed(), busy = false, progress = null)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        copy(error = exception.message ?: "Não foi possível baixar o modelo.", busy = false, progress = null)
    }

    suspend fun saveGroqKey(
        preferences: UserPreferenceDao,
        key: String,
        onSuccess: () -> Unit
    ): OnboardingState = try {
        preferences.set(UserPreferenceEntity("groq_api_key", key.trim()))
        onSuccess()
        this
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        copy(error = "Não foi possível salvar a chave.", busy = false)
    }

    fun clearError(): OnboardingState = copy(error = null)
}
