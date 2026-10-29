package com.friday.ai.ui.settings

import java.util.Locale

/** User-facing feedback from SettingsViewModel, kept in Brazilian Portuguese. */
internal object SettingsFeedback {
    const val GMAIL_DISCONNECTED = "Desconectado"
    const val GMAIL_CONNECTED = "Conectado"
    const val VOICE_MODEL_UNAVAILABLE = "Não foi possível carregar o modelo de reconhecimento de voz."
    const val AUDIO_NOT_HEARD = "Não consegui ouvir."
    const val AUDIO_TOO_SHORT = "Gravação muito curta."
    const val INSUFFICIENT_SAMPLES =
        "Não houve gravações nítidas suficientes. Tente novamente em um local silencioso."
    const val LAZURI_MISSING_CREDENTIALS = "Informe o endereço do servidor e a chave de API."
    const val LAZURI_REGISTRATION_FAILED = "Falha ao registrar no Lazuri."

    fun voicePrompt(phrase: String): String = "Diga: «$phrase»"

    fun enrolmentComplete(cohesion: Float): String =
        "Concluído. Semelhança das gravações: %.2f".format(Locale.forLanguageTag("pt-BR"), cohesion)

    fun lazuriUnreachable(url: String): String = "Não foi possível conectar ao Lazuri em $url"
}
