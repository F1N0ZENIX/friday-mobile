package com.friday.ai.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.data.backup.SettingsArchive
import com.friday.ai.data.backup.SettingsTransfer
import com.friday.ai.ui.theme.HudButton
import com.friday.ai.ui.theme.HudOutlinedButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

/** "Transferir para outro celular": export everything to one encrypted file, or import one. */
@Composable
fun TransferSection(viewModel: TransferViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Which file the passphrase dialog is for, and whether it's an export (asks twice).
    var asking by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let { asking = it to true } }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { asking = it to false }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Chave Groq, perfil de voz, memória, comandos aprendidos, histórico e ajustes em um " +
                "arquivo criptografado por senha. Salve-o e abra no novo celular.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HudButton(
                onClick = { createFile.launch("friday-${today()}.friday") },
                enabled = state !is TransferViewModel.State.Working,
                modifier = Modifier.weight(1f)
            ) { Text("Exportar") }
            HudOutlinedButton(
                onClick = { openFile.launch(arrayOf("*/*")) },
                enabled = state !is TransferViewModel.State.Working,
                modifier = Modifier.weight(1f)
            ) { Text("Importar") }
        }
        when (val s = state) {
            TransferViewModel.State.Working -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            is TransferViewModel.State.Done ->
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            is TransferViewModel.State.Failed ->
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }

    asking?.let { (uri, export) ->
        PassphraseDialog(
            export = export,
            onDismiss = { asking = null },
            onConfirm = { pass ->
                asking = null
                if (export) viewModel.export(uri, pass) else viewModel.open(uri, pass)
            }
        )
    }
    (state as? TransferViewModel.State.Review)?.let { review ->
        ReviewDialog(review.summary, onConfirm = viewModel::confirmImport, onDismiss = viewModel::dismiss)
    }
}

@Composable
private fun PassphraseDialog(export: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val longEnough = pass.length >= SettingsArchive.MIN_PASSPHRASE
    val ok = longEnough && (!export || pass == repeat)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (export) "Senha para o arquivo" else "Senha do arquivo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (export) {
                    Text(
                        "Sem a senha, ninguém pode abrir o arquivo. Não é possível recuperar a senha.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, singleLine = true,
                    label = { Text("Senha") }, visualTransformation = PasswordVisualTransformation(),
                    supportingText = if (export && pass.isNotEmpty() && !longEnough) {
                        { Text("No mínimo ${SettingsArchive.MIN_PASSPHRASE} caracteres") }
                    } else null
                )
                if (export) {
                    OutlinedTextField(
                        value = repeat, onValueChange = { repeat = it }, singleLine = true,
                        label = { Text("Confirmar senha") }, visualTransformation = PasswordVisualTransformation(),
                        isError = repeat.isNotEmpty() && repeat != pass
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pass.toCharArray()) }, enabled = ok) {
                Text(if (export) "Salvar" else "Abrir")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ReviewDialog(summary: SettingsTransfer.Summary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Substituir todos os dados deste celular?") },
        text = {
            Column {
                Text("Arquivo de ${date(summary.createdAt)}, Friday ${summary.appVersion}:")
                Spacer(Modifier.height(8.dp))
                Text("• chave Groq: ${if (summary.hasApiKey) "sim" else "não"}")
                Text("• perfil de voz: ${if (summary.hasVoiceProfile) "sim" else "não"}")
                Text("• fatos na memória: ${summary.memories}")
                Text("• comandos aprendidos: ${summary.learnedCommands}")
                Text("• mensagens no histórico: ${summary.chatMessages}")
                Text("• tarefas pendentes: ${summary.errands}")
                Spacer(Modifier.height(8.dp))
                Text(
                    "As configurações, a memória e o histórico atuais serão substituídos. O Gmail " +
                        "pode exigir um novo login.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Substituir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

private fun date(millis: Long): String = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru")).format(Date(millis))
