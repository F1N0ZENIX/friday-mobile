package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortugueseDeviceCommandsTest {
    private val router = PortugueseDeviceCommands

    @Test
    fun `relative alarm from screenshot is a real device timer`() {
        assertEquals(CommandResult.SetTimer(120, null), router.route("defina um alarme para daqui a 2 minutos"))
        assertEquals(CommandResult.SetTimer(30, null), router.route("inicie um temporizador por 30 segundos"))
    }

    @Test
    fun `absolute Portuguese alarms route to Clock`() {
        assertEquals(CommandResult.SetAlarm(7, 30, null), router.route("defina um alarme para às 7:30"))
        assertEquals(CommandResult.SetAlarm(8, 0, null), router.route("alarme às 8h"))
    }

    @Test
    fun `mentions and invalid times cannot create an alarm`() {
        for (text in listOf("por que meu alarme falhou", "alarme às 25:90", "alarme em 0 minutos")) {
            assertEquals(text, null, router.route(text))
        }
    }

    @Test
    fun `Portuguese flashlight and app requests bypass model`() {
        assertEquals(CommandResult.Flashlight(true), router.route("ligar a lanterna"))
        assertEquals(CommandResult.Flashlight(false), router.route("desligue a lanterna"))
        assertTrue(router.route("abra o WhatsApp") is CommandResult.OpenApp)
    }
}
