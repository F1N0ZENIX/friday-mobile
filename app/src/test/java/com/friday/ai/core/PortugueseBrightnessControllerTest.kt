package com.friday.ai.core

import com.friday.ai.domain.model.DeviceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortugueseBrightnessControllerTest {

    @Test
    fun `brightness set applies Portuguese level and restores automatic brightness`() {
        val knobs = BrightnessKnobs(brightness = 40, auto = true)
        val controller = DeviceController(knobs)

        val changed = controller.apply(DeviceAction.BRIGHTNESS_SET, 35, russian = false)

        assertTrue(changed.succeeded)
        assertFalse(knobs.auto)
        assertEquals(35, knobs.brightness)
        assertEquals("Brilho em 35%", changed.message)
        assertEquals(DeviceAction.BRIGHTNESS_AUTO, changed.undo!!.action)

        controller.apply(changed.undo!!.action, changed.undo!!.level, russian = false)
        assertTrue(knobs.auto)
    }

    @Test
    fun `brightness directions use the controller step and clamp at safe minimum`() {
        val knobs = BrightnessKnobs(brightness = 90, auto = false)
        val controller = DeviceController(knobs)

        assertTrue(controller.apply(DeviceAction.BRIGHTNESS_UP, null, russian = false).succeeded)
        assertEquals(100, knobs.brightness)

        knobs.brightness = 10
        assertTrue(controller.apply(DeviceAction.BRIGHTNESS_DOWN, null, russian = false).succeeded)
        assertEquals(1, knobs.brightness)
    }

    @Test
    fun `brightness without permission opens write settings without claiming success`() {
        val knobs = BrightnessKnobs(canWrite = false)

        val result = DeviceController(knobs).apply(DeviceAction.BRIGHTNESS_SET, 80, russian = false)

        assertEquals(DeviceActionResult.Mode.NEEDS_PERMISSION, result.mode)
        assertFalse(result.succeeded)
        assertEquals(listOf("write_settings"), knobs.opened)
        assertNull(result.undo)
    }

    @Test
    fun `ignored brightness write is reported as a failure with no undo`() {
        val knobs = BrightnessKnobs(brightness = 40, auto = true, ignoreWrites = true)

        val result = DeviceController(knobs).apply(DeviceAction.BRIGHTNESS_SET, 80, russian = false)

        assertFalse(result.succeeded)
        assertEquals("O brilho não mudou", result.message)
        assertTrue(knobs.auto)
        assertEquals(40, knobs.brightness)
        assertNull(result.undo)
    }
}

private class BrightnessKnobs(
    var brightness: Int = 40,
    var auto: Boolean = true,
    var canWrite: Boolean = true,
    var ignoreWrites: Boolean = false
) : DeviceKnobs {
    val opened = mutableListOf<String>()

    override fun mediaVolume() = MediaVolume(0, 0, 1, muted = false)
    override fun setMediaVolumeIndex(index: Int) = Unit
    override fun stepMediaVolume(raise: Boolean) = Unit
    override fun setMediaMuted(muted: Boolean) = Unit
    override fun dndAccess() = true
    override fun dndOn() = false
    override fun setDnd(on: Boolean) = Unit
    override fun openDndAccessSettings() = Unit
    override fun ringer() = Ringer.NORMAL
    override fun setRinger(mode: Ringer) = Unit
    override fun canWriteSettings() = canWrite
    override fun openWriteSettingsPermission() { opened += "write_settings" }
    override fun brightnessPercent() = brightness
    override fun setBrightnessPercent(percent: Int) { if (!ignoreWrites) brightness = percent }
    override fun brightnessAuto() = auto
    override fun setBrightnessAuto(auto: Boolean) { if (!ignoreWrites) this.auto = auto }
    override fun radioOn(radio: Radio): Boolean? = null
    override fun openPanel(radio: Radio) = Unit
}
