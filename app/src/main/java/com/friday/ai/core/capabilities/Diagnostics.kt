package com.friday.ai.core.capabilities

/**
 * The diagnostics screen as data: one row per thing that decides whether a
 * Friday feature works, what it means for the user, and how to fix it.
 *
 * Before this, a missing permission surfaced in the middle of a spoken
 * exchange as "é necessário permitir acesso a…", if at all. Here it is all in one place,
 * read from the same snapshot that decides what the model is offered — so
 * the screen and Friday's behaviour can't disagree.
 */
object Diagnostics {

    enum class Group(val title: String) {
        ESSENTIALS("Essencial"),
        VOICE("Voz"),
        PHONE("Telefone e contatos"),
        INTEGRATIONS("Serviços"),
        DEVICE("Este dispositivo")
    }

    enum class Status {
        /** Works. */
        OK,

        /** Something Friday should do doesn't work until this is fixed. */
        PROBLEM,

        /** Off by choice or not set up; nothing is broken. */
        OFF,

        /** A fact about the device, nothing to fix. */
        INFO
    }

    /** What tapping "fix" does; the screen maps each to the right system action. */
    enum class Fix {
        MICROPHONE, POST_NOTIFICATIONS, OVERLAY, NOTIFICATION_ACCESS, BATTERY,
        CONTACTS, PHONE, CALENDAR, LOCATION, DND_ACCESS, WRITE_SETTINGS, NEARBY_DEVICES,

        /** The phone's default digital assistant, which the side button calls. */
        ASSISTANT,

        /** Friday's own Settings screen: API key, wake word, voice model and profile, Gmail, export. */
        FRIDAY_SETTINGS
    }

    data class Row(
        val id: String,
        val group: Group,
        val title: String,
        val status: Status,
        val detail: String,
        val fix: Fix? = null
    )

    fun rows(c: FridayCapabilities): List<Row> = essentials(c) + voice(c) + phone(c) + integrations(c) + device(c)

    /** How many rows need the user's attention. */
    fun problems(rows: List<Row>): Int = rows.count { it.status == Status.PROBLEM }

    /** One thing to check: what it is called, what each outcome means, how to fix it. */
    private class Check(
        val id: String,
        val title: String,
        val okDetail: String,
        val badDetail: String,
        val fix: Fix?,
        /** Off by choice rather than broken: shown as OFF, not counted as a problem. */
        val optional: Boolean = false
    ) {
        fun row(group: Group, ok: Boolean) = Row(
            id, group, title,
            status = when {
                ok -> Status.OK
                optional -> Status.OFF
                else -> Status.PROBLEM
            },
            detail = if (ok) okDetail else badDetail,
            fix = if (ok) null else fix
        )
    }

    private fun essentials(c: FridayCapabilities) = listOf(
        Check(
            "groq", "Chave da API Groq", "Configurada",
            "Não configurada: o Friday não consegue responder", Fix.FRIDAY_SETTINGS
        )
            .row(Group.ESSENTIALS, c.integrations.groqKey),
        Check("microphone", "Microfone", "Permitido", "Sem permissão: o Friday não consegue ouvir você", Fix.MICROPHONE)
            .row(Group.ESSENTIALS, c.permissions.microphone),
        Check(
            "overlay", "Sobrepor a outros aplicativos", "Permitido",
            "Sem permissão: o painel do assistente não pode aparecer", Fix.OVERLAY
        ).row(Group.ESSENTIALS, c.permissions.overlay),
        Check(
            "post_notifications", "Notificações", "Permitidas",
            "Sem permissão: o Android pode impedir a escuta em segundo plano", Fix.POST_NOTIFICATIONS
        ).row(Group.ESSENTIALS, c.permissions.postNotifications)
    )

    private fun voice(c: FridayCapabilities): List<Row> {
        val v = c.voice
        val wakeWord = Check(
            "wake_word", "Palavra «Friday»", "Ativado",
            "Desativado: o Friday só escuta com o aplicativo aberto", Fix.FRIDAY_SETTINGS, optional = true
        ).row(Group.VOICE, v.wakeWordEnabled)
        val sideButton = Check(
            "assistant", "Botão lateral", "Abre o Friday: ele está definido como assistente digital",
            "Abre outro assistente. Selecione Friday em Aplicativos padrão → Assistente digital",
            Fix.ASSISTANT, optional = true
        ).row(Group.VOICE, c.assistant.active)
        if (!v.wakeWordEnabled) return listOf(wakeWord, sideButton)
        return listOf(
            wakeWord,
            sideButton,
            Check(
                "wake_model", "Modelo de ativação", "Baixado",
                "Não baixado: a ativação por voz não funciona", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.wakeModelReady),
            Check(
                "listening", "Escuta em segundo plano", "Funcionando",
                "Parado: a palavra de ativação não está sendo detectada", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.listening),
            Check(
                "battery", "Restrições de bateria", "Não",
                "Ativas: o Android pode interromper a escuta", Fix.BATTERY
            ).row(Group.VOICE, c.permissions.batteryExempt),
            Check(
                "voice_profile", "Perfil de voz", "Gravado: reconhece apenas sua voz",
                "Não gravado: outras pessoas também podem dar comandos", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.voiceProfile)
        )
    }

    private fun phone(c: FridayCapabilities) = listOf(
        Check(
            "notification_access", "Acesso às notificações", "Ativado",
            "Desativado: sem leitura e resposta a mensagens, controle de música ou anúncio de chamadas",
            Fix.NOTIFICATION_ACCESS
        ).row(Group.PHONE, c.permissions.notificationListener),
        Check(
            "contacts", "Contatos", "Permitidos",
            "Sem permissão: comandos para contatos não funcionarão", Fix.CONTACTS
        ).row(Group.PHONE, c.permissions.contacts),
        Check(
            "phone", "Chamadas", "Permitidas: a chamada começa imediatamente",
            "Sem permissão: o comando apenas abre o discador", Fix.PHONE, optional = true
        ).row(Group.PHONE, c.permissions.phone),
        Check(
            "calendar", "Calendário", "Permitido",
            "Sem permissão: os eventos precisam ser salvos manualmente", Fix.CALENDAR
        ).row(Group.PHONE, c.permissions.calendar),
        Check(
            "location", "Localização", "Permitida",
            "Sem permissão: sem previsão local ou lembretes por localização", Fix.LOCATION
        ).row(Group.PHONE, c.permissions.location),
        Check(
            "dnd_access", "Acesso ao Não Perturbe", "Permitido",
            "Sem permissão: comandos de Não Perturbe apenas abrem as configurações", Fix.DND_ACCESS
        ).row(Group.PHONE, c.permissions.dndAccess),
        Check(
            "write_settings", "Alterar configurações do sistema", "Permitido",
            "Sem permissão: não é possível alterar o brilho por voz", Fix.WRITE_SETTINGS, optional = true
        ).row(Group.PHONE, c.permissions.writeSettings),
        Check(
            "nearby_devices", "Dispositivos próximos", "Permitido",
            "Sem permissão: modos automáticos por Bluetooth não funcionarão", Fix.NEARBY_DEVICES, optional = true
        ).row(Group.PHONE, c.permissions.nearbyDevices)
    )

    private fun integrations(c: FridayCapabilities) = listOf(
        Check(
            "gmail", "Gmail", "Conectado", "Não conectado: comandos de e-mail indisponíveis",
            Fix.FRIDAY_SETTINGS, optional = true
        ).row(Group.INTEGRATIONS, c.integrations.gmail)
        // Lazuri is deferred: Friday lives on one phone, and moves with settings export.
    )

    private fun device(c: FridayCapabilities): List<Row> {
        val d = c.device
        fun info(id: String, title: String, detail: String) = Row(id, Group.DEVICE, title, Status.INFO, detail)
        fun yesNo(on: Boolean) = if (on) "Sim" else "Não"
        return listOf(
            info("model", "Dispositivo", "${d.manufacturer} ${d.model} · Android API ${d.sdk}".trim()),
            info("nfc", "NFC", yesNo(d.nfc)),
            info("uwb", "UWB", yesNo(d.uwb)),
            info("stylus", "Caneta digital", yesNo(d.stylus)),
            info("external_display", "Tela externa", if (d.externalDisplay) "Conectado" else "Não"),
            info(
                "shizuku", "Shizuku",
                if (c.privileged.shizukuInstalled) "Instalado (ainda não utilizado)"
                else "Não instalado (opcional)"
            )
        )
    }
}
