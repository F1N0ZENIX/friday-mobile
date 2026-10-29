package com.friday.ai.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.ai.command.LockPolicy
import com.friday.ai.service.lock.LockScreen
import com.friday.ai.ui.theme.ArcCyan
import com.friday.ai.ui.theme.ErrorColor
import com.friday.ai.ui.theme.HudNote
import com.friday.ai.ui.theme.HudOutlinedButton
import com.friday.ai.ui.theme.HudPanel
import com.friday.ai.ui.theme.HudReadout
import com.friday.ai.ui.theme.HudSwitchRow
import org.koin.androidx.compose.koinViewModel

private val choices = listOf(
    Triple(
        LockPolicy.Level.SAFE_ONLY, "Somente ações seguras",
        "Música, alarmes, lanterna e previsão do tempo. Chamadas e mensagens exigem desbloqueio."
    ),
    Triple(
        LockPolicy.Level.VOICE, "Após verificar a voz",
        "Seu perfil de voz confirma sua identidade; sem ele, use a tela de desbloqueio do Android."
    ),
    Triple(
        LockPolicy.Level.UNLOCK, "Somente após desbloquear",
        "Chamadas e mensagens exigem impressão digital, reconhecimento facial ou PIN."
    )
)

@Composable
fun LockPanel(viewModel: LockLevelViewModel = koinViewModel()) {
    val context = LocalContext.current
    val level by viewModel.level.collectAsStateWithLifecycle()
    var fullScreen by remember { mutableStateOf(LockScreen.fullScreenAllowed(context)) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val outcome by LockScreen.lastOutcome.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        fullScreen = LockScreen.fullScreenAllowed(context)
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }

    HudPanel("Tela de bloqueio", index = 14) {
        HudNote(
            "O que o Friday pode fazer com o celular bloqueado. O desbloqueio é feito pelo Android: " +
                "o Friday não vê nem armazena seu PIN."
        )
        choices.forEach { (option, title, note) ->
            HudSwitchRow(
                title = title,
                subtitle = note,
                checked = level == option,
                enabled = true,
                onChange = { on -> if (on) viewModel.choose(option) }
            )
        }
        HudNote(
            "Na tela bloqueada, aparece apenas o status do Friday: ouvindo, processando ou respondendo. " +
                "Suas mensagens só aparecem após «Desbloquear para ver detalhes»."
        )
        HudReadout(
            "Notificações",
            if (notifications) "PERMITIDAS" else "DESATIVADAS",
            valueColor = if (notifications) ArcCyan else ErrorColor
        )
        HudReadout(
            "Notificações em tela cheia",
            if (fullScreen) "PERMITIDAS" else "NÃO PERMITIDAS",
            valueColor = if (fullScreen) ArcCyan else ErrorColor
        )
        HudReadout("Última exibição", outcomeText(outcome))
        if (!fullScreen || !notifications) {
            HudNote(
                "Para mostrar respostas e a tela de desbloqueio sobre a tela bloqueada, " +
                    "permita notificações em tela cheia para o Friday."
            )
            HudOutlinedButton(onClick = { openFullScreenSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Permitir notificações em tela cheia")
            }
        }
    }
}

/** What the last attempt really showed — an allowed permission alone proves nothing. */
private fun outcomeText(outcome: LockScreen.Outcome?): String = when (outcome) {
    null -> "AINDA NÃO OCORREU"
    LockScreen.Outcome.ACTIVITY_VISIBLE -> "PAINEL NA TELA"
    LockScreen.Outcome.NOTIFICATION_ONLY -> "SOMENTE NOTIFICAÇÃO"
    LockScreen.Outcome.FSI_DENIED -> "NOTIFICAÇÃO (SEM TELA CHEIA)"
    LockScreen.Outcome.NOTIFICATIONS_BLOCKED -> "SOMENTE VOZ"
    LockScreen.Outcome.FAILED -> "FALHOU"
}

private fun openFullScreenSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
