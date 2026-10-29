package com.friday.ai.core

/**
 * The phrase list Friday can understand with no network at all.
 *
 * Speech recognition normally goes through Groq Whisper, so losing signal
 * currently makes the assistant completely mute — no alarm, no torch, nothing,
 * exactly when you might be underground or on a plane. Device actions don't
 * need the internet to *run*, only to be *heard*, so the on-device recogniser
 * is given a small closed grammar of them.
 *
 * Deliberately narrow: a constrained grammar is far more accurate than open
 * transcription, and anything that genuinely needs the network (a question for
 * the model) is better refused clearly than half-understood.
 */
object OfflineCommands {

    /**
     * Phrases the offline decoder is allowed to return. Cyrillic only — the
     * on-device model is Russian and a word outside its vocabulary can break
     * the recogniser.
     */
    val GRAMMAR: List<String> = listOf(
        "ligar lanterna", "acender lanterna", "desligar lanterna", "lanterna",
        "aumentar volume", "diminuir volume", "silenciar", "ativar som",
        "abrir câmera", "abrir camera", "abrir música", "abrir telefone",
        "abrir mensagens", "abrir navegador", "tirar foto", "tirar selfie",
        "gravar vídeo", "abrir gravador", "abrir configurações",
        "abrir wifi", "abrir bluetooth", "abrir nfc",
        "abrir tela", "abrir som", "abrir bateria", "abrir localização",
        "não perturbe", "ativar não perturbe", "desativar não perturbe",
        "parar", "chega", "tchau", "encerrar"
    )

    /**
     * True when [text] is something the device can carry out by itself.
     *
     * Used to decide whether an offline transcription is worth acting on, or
     * whether the user should simply be told there's no connection.
     */
    fun isOfflineCapable(command: Any?): Boolean = when (command) {
        is com.friday.ai.domain.model.CommandResult.ToggleFlashlight,
        is com.friday.ai.domain.model.CommandResult.Flashlight,
        is com.friday.ai.domain.model.CommandResult.SendMessage,
        is com.friday.ai.domain.model.CommandResult.DeviceControl,
        is com.friday.ai.domain.model.CommandResult.SetAlarm,
        is com.friday.ai.domain.model.CommandResult.SetTimer,
        is com.friday.ai.domain.model.CommandResult.OpenApp,
        is com.friday.ai.domain.model.CommandResult.CloseApp,
        // Launching an activity needs no network, only the phone.
        is com.friday.ai.domain.model.CommandResult.OpenSettings,
        is com.friday.ai.domain.model.CommandResult.OpenCamera,
        is com.friday.ai.domain.model.CommandResult.RecordAudio -> true
        else -> false
    }

    /** What Friday says when it heard something it can't do without a network. */
    fun offlineRefusal(russian: Boolean): String =
        "Sem conexão. No momento, só posso controlar o celular."
}
