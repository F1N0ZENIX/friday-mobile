package com.friday.ai.core.capabilities

/**
 * The diagnostics screen as data: one row per thing that decides whether a
 * Friday feature works, what it means for the user, and how to fix it.
 *
 * Before this, a missing permission surfaced in the middle of a spoken
 * exchange as "permission needed for…", if at all. Here it is all in one place,
 * read from the same snapshot that decides what the model is offered — so
 * the screen and Friday's behaviour can't disagree.
 */
object Diagnostics {

    enum class Group(val title: String) {
        ESSENTIALS("PRINCIPAL"),
        VOICE("VOZ"),
        PHONE("TELEFONE E PESSOAS"),
        INTEGRATIONS("SERVIÇOS"),
        DEVICE("ESTE DISPOSITIVO")
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
            "Não configurada: Friday não entende a fala nem responde", Fix.FRIDAY_SETTINGS
        )
            .row(Group.ESSENTIALS, c.integrations.groqKey),
        Check(
            "microphone", "Microfone", "Permissão concedida",
            "Sem permissão: Friday não consegue ouvir você", Fix.MICROPHONE
        )
            .row(Group.ESSENTIALS, c.permissions.microphone),
        Check(
            "overlay", "Sobre outros apps", "Permissão concedida",
            "Sem permissão: o painel do assistente não pode aparecer", Fix.OVERLAY
        ).row(Group.ESSENTIALS, c.permissions.overlay),
        Check(
            "post_notifications", "Notificações", "Permissão concedida",
            "Sem permissão: o Android pode impedir a escuta em segundo plano", Fix.POST_NOTIFICATIONS
        ).row(Group.ESSENTIALS, c.permissions.postNotifications)
    )

    private fun voice(c: FridayCapabilities): List<Row> {
        val v = c.voice
        val wakeWord = Check(
            "wake_word", "Palavra de ativação “Friday”", "Ativada",
            "Desativada: Friday só escuta quando o app está aberto", Fix.FRIDAY_SETTINGS, optional = true
        ).row(Group.VOICE, v.wakeWordEnabled)
        val sideButton = Check(
            "assistant", "Botão lateral", "Chama Friday: ela está selecionada como assistente digital",
            "Chama outro assistente. Selecione Friday: Apps padrão → Assistente digital",
            Fix.ASSISTANT, optional = true
        ).row(Group.VOICE, c.assistant.active)
        if (!v.wakeWordEnabled) return listOf(wakeWord, sideButton)
        return listOf(
            wakeWord,
            sideButton,
            Check(
                "wake_model", "Modelo da palavra", "Baixado",
                "Não baixado: a palavra de ativação não funciona", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.wakeModelReady),
            Check(
                "listening", "Escuta em segundo plano", "Funcionando",
                "Parada: “Friday” não faz nada agora", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.listening),
            Check(
                "battery", "Restrições de bateria", "Sem restrições",
                "Há restrições: o Android pode suspender a escuta", Fix.BATTERY
            ).row(Group.VOICE, c.permissions.batteryExempt),
            Check(
                "voice_profile", "Perfil de voz", "Gravado: só sua voz é reconhecida",
                "Não gravado: qualquer voz pode dar comandos", Fix.FRIDAY_SETTINGS
            ).row(Group.VOICE, v.voiceProfile)
        )
    }

    private fun phone(c: FridayCapabilities) = listOf(
        Check(
            "notification_access", "Acesso às notificações", "Ativado",
            "Desativado: sem ler ou responder chats, controlar música ou anunciar chamadas",
            Fix.NOTIFICATION_ACCESS
        ).row(Group.PHONE, c.permissions.notificationListener),
        Check(
            "contacts", "Contatos", "Permissão concedida",
            "Sem permissão: “ligue para minha mãe” e “escreva para meu pai” não encontram ninguém",
            Fix.CONTACTS
        ).row(Group.PHONE, c.permissions.contacts),
        Check(
            "phone", "Chamadas", "Permissão concedida: a chamada começa na hora",
            "Sem permissão: a chamada só abre o discador", Fix.PHONE, optional = true
        ).row(Group.PHONE, c.permissions.phone),
        Check(
            "calendar", "Calendário", "Permissão concedida",
            "Sem permissão: eventos precisam ser salvos manualmente e remarcar não funciona", Fix.CALENDAR
        ).row(Group.PHONE, c.permissions.calendar),
        Check(
            "location", "Localização", "Permissão concedida",
            "Sem permissão: não há clima “aqui” nem lembretes em lojas", Fix.LOCATION
        ).row(Group.PHONE, c.permissions.location),
        Check(
            "dnd_access", "Acesso a “Não perturbe”", "Disponível",
            "Indisponível: “Não perturbe” e modo silencioso só abrem as configurações", Fix.DND_ACCESS
        ).row(Group.PHONE, c.permissions.dndAccess),
        Check(
            "write_settings", "Alterar configurações do sistema", "Permissão concedida",
            "Sem permissão: o brilho não muda por voz", Fix.WRITE_SETTINGS, optional = true
        ).row(Group.PHONE, c.permissions.writeSettings),
        Check(
            "nearby_devices", "Dispositivos próximos", "Permissão concedida",
            "Sem permissão: modos não ativam pelo Bluetooth do carro ou fones", Fix.NEARBY_DEVICES, optional = true
        ).row(Group.PHONE, c.permissions.nearbyDevices)
    )

    private fun integrations(c: FridayCapabilities) = listOf(
        Check(
            "gmail", "Gmail", "Conectado", "Não conectado: comandos de e-mail estão desativados",
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
            info("stylus", "Caneta", yesNo(d.stylus)),
            info("external_display", "Tela externa", if (d.externalDisplay) "Conectada" else "Não"),
            info(
                "shizuku", "Shizuku",
                if (c.privileged.shizukuInstalled) "Instalado (ainda não é usado)"
                else "Não instalado (opcional)"
            )
        )
    }
}
