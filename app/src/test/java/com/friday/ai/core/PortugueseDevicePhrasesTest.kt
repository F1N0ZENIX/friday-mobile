package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortugueseDevicePhrasesTest {

    @Test
    fun `brightness level accepts Portuguese prepositions including zero`() {
        val expected = CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 0)
        assertEquals(expected, DevicePhrases.brightness("defina o brilho para 0"))
        assertEquals(expected, CommandRouter().route("defina o brilho para 0"))
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 35),
            DevicePhrases.brightness("ajuste o brilho em 35%")
        )
    }

    @Test
    fun `brightness supports Portuguese modes and directions`() {
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_AUTO),
            DevicePhrases.brightness("ative o brilho automático")
        )
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 100),
            DevicePhrases.brightness("coloque o brilho no máximo")
        )
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 5),
            DevicePhrases.brightness("brilho mínimo")
        )
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_UP),
            DevicePhrases.brightness("aumente o brilho")
        )
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_DOWN),
            DevicePhrases.brightness("diminua o brilho")
        )
    }

    @Test
    fun `Portuguese brightness matching requires a brightness request`() {
        assertNull(DevicePhrases.brightness("o sol está brilhando"))
        assertNull(DevicePhrases.brightness("defina o volume para 0"))
    }

    @Test
    fun `brightness does not parse numeric substrings, negatives, quotes, negations or explanations`() {
        val unsafe = listOf(
            "ajuste o brilho para 1000",
            "ajuste o brilho para -10",
            "ajuste o brilho para 20.5",
            "ajuste o brilho para 20,5",
            "não ajuste o brilho para 20",
            "não quero aumentar o brilho",
            "nao aumente o brilho",
            "explique \"brilho para 20\"",
            "como ajustar o brilho para 20?"
        )
        unsafe.forEach {
            assertNull(it, DevicePhrases.brightness(it))
            assertEquals(it, CommandResult.ChatMessage(it), CommandRouter().route(it))
        }
    }

    @Test
    fun `English and Russian brightness levels stay supported`() {
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 30),
            DevicePhrases.brightness("brightness to 30")
        )
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 30),
            DevicePhrases.brightness("яркость на 30")
        )
    }
}
