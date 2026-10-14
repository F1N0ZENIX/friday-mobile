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
        LockPolicy.Level.SAFE_ONLY, "Только безопасные",
        "Музыка, будильники, фонарик, погода. Звонки и сообщения — после разблокировки."
    ),
    Triple(
        LockPolicy.Level.VOICE, "После проверки голоса",
        "Записанный голосовой профиль подтверждает вас; без него — экран разблокировки Android."
    ),
    Triple(
        LockPolicy.Level.UNLOCK, "Только после разблокировки",
        "Звонки и сообщения всегда сначала через отпечаток, лицо или PIN."
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

    HudPanel("Заблокированный экран", index = 14) {
        HudNote(
            "Что Пятница делает, пока телефон заблокирован. Разблокировка — экран самого Android: " +
                "Пятница не видит и не хранит ваш PIN."
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
            "На экране блокировки видно только, что Пятница делает: слушает, обрабатывает, отвечает. " +
                "Ваши слова и ответ — после «Разблокировать для деталей»."
        )
        HudReadout(
            "Уведомления",
            if (notifications) "РАЗРЕШЕНЫ" else "ВЫКЛЮЧЕНЫ",
            valueColor = if (notifications) ArcCyan else ErrorColor
        )
        HudReadout(
            "Полноэкранные уведомления",
            if (fullScreen) "РАЗРЕШЕНЫ" else "НЕ РАЗРЕШЕНЫ",
            valueColor = if (fullScreen) ArcCyan else ErrorColor
        )
        HudReadout("Последний показ", outcomeText(outcome))
        if (!fullScreen || !notifications) {
            HudNote(
                "Чтобы показывать ответы и экран разблокировки поверх блокировки, " +
                    "разрешите Friday полноэкранные уведомления."
            )
            HudOutlinedButton(onClick = { openFullScreenSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Разрешить полноэкранные уведомления")
            }
        }
    }
}

/** What the last attempt really showed — an allowed permission alone proves nothing. */
private fun outcomeText(outcome: LockScreen.Outcome?): String = when (outcome) {
    null -> "ЕЩЁ НЕ БЫЛО"
    LockScreen.Outcome.ACTIVITY_VISIBLE -> "ПАНЕЛЬ НА ЭКРАНЕ"
    LockScreen.Outcome.NOTIFICATION_ONLY -> "ТОЛЬКО УВЕДОМЛЕНИЕ"
    LockScreen.Outcome.FSI_DENIED -> "УВЕДОМЛЕНИЕ (НЕТ ПОЛНОЭКРАННЫХ)"
    LockScreen.Outcome.NOTIFICATIONS_BLOCKED -> "ТОЛЬКО ГОЛОС"
    LockScreen.Outcome.FAILED -> "НЕ УДАЛОСЬ"
}

private fun openFullScreenSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
