@file:Suppress("MaxLineLength")

package com.friday.ai.core

import com.friday.ai.agent.ActionEnvelope
import com.friday.ai.agent.ActionSchema
import com.friday.ai.agent.AgentTools
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import java.time.LocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A phone's settings in memory, with the same rules Android applies. */
private class FakeKnobs : DeviceKnobs {
    val media = FakeMediaStream()
    var dndGranted = true
    var dndState = false
    var ringerMode = Ringer.NORMAL
    var canWrite = true
    var brightness = 40
    var auto = true
    var wifi: Boolean? = true
    var bluetooth: Boolean? = false
    val opened = mutableListOf<String>()
    /** Simulates a vendor that ignores the request. */
    var ignoreWrites = false

    override fun mediaVolume() = media.read()
    override fun setMediaVolumeIndex(index: Int) = media.applyIndex(index)
    override fun stepMediaVolume(raise: Boolean) = media.applyStep(raise)
    override fun setMediaMuted(muted: Boolean) = media.applyMuted(muted)
    override fun dndAccess() = dndGranted
    override fun dndOn() = dndState
    override fun setDnd(on: Boolean) { if (!ignoreWrites) dndState = on }
    override fun openDndAccessSettings() { opened += "dnd_access" }
    override fun ringer() = ringerMode
    override fun setRinger(mode: Ringer) {
        if (mode == Ringer.SILENT && !dndGranted) throw SecurityException("Not allowed to change Do Not Disturb state")
        if (!ignoreWrites) ringerMode = mode
    }
    override fun canWriteSettings() = canWrite
    override fun openWriteSettingsPermission() { opened += "write_settings" }
    override fun brightnessPercent() = brightness
    override fun setBrightnessPercent(percent: Int) { if (!ignoreWrites) brightness = percent }
    override fun brightnessAuto() = auto
    override fun setBrightnessAuto(auto: Boolean) { if (!ignoreWrites) this.auto = auto }
    override fun radioOn(radio: Radio) = if (radio == Radio.WIFI) wifi else bluetooth
    override fun openPanel(radio: Radio) { opened += radio.name }
}

class DeviceControllerTest {

    private val knobs = FakeKnobs()
    private val controller = DeviceController(knobs)

    private fun run(action: DeviceAction, level: Int? = null) = controller.apply(action, level, russian = true)

    /** Applies [result]'s undo, every step of it, as a mode's exit does. */
    private fun undo(result: DeviceActionResult) {
        val u = result.undo ?: error("no undo")
        u.steps.forEach { controller.apply(it.action, it.level, russian = true) }
    }

    @Test
    fun `volume is set to the nearest step, read back, and its undo restores the old level`() {
        val r = run(DeviceAction.VOLUME_SET, 25)
        assertTrue(r.succeeded)
        // 15 steps: 25 % is step 4, which Android shows as 27 %.
        assertEquals(4, knobs.media.index)
        assertEquals("Громкость 27%", r.message)
        undo(r)
        assertEquals(9, knobs.media.index)
        assertFalse(knobs.media.muted)
    }

    @Test
    fun `set on a 15-step stream reports what the stream really holds`() {
        val expected = mapOf(0 to (0 to "Звук медиа выключен"), 1 to (1 to "Громкость 7%"), 50 to (8 to "Громкость 53%"),
            99 to (15 to "Громкость 100%"), 100 to (15 to "Громкость 100%"))
        expected.forEach { (asked, outcome) ->
            knobs.media.index = 9
            knobs.media.muted = false
            val r = run(DeviceAction.VOLUME_SET, asked)
            assertEquals("$asked %", outcome.first, knobs.media.index)
            assertEquals("$asked %", outcome.second, r.message)
            assertTrue("$asked %", r.succeeded)
        }
    }

    @Test
    fun `a level the phone ignored is not reported as done`() {
        knobs.media.ignoreWrites = true
        val r = run(DeviceAction.VOLUME_SET, 100)
        assertFalse(r.succeeded)
        assertEquals("Громкость не изменилась", r.message)
        assertNull("nothing changed, nothing to undo", r.undo)
        // Already where asked: true without a change, and nothing to undo either.
        val same = run(DeviceAction.VOLUME_SET, 60)
        assertTrue(same.succeeded)
        assertNull(same.undo)
    }

    @Test
    fun `a level that landed elsewhere says where`() {
        val phone = object : DeviceKnobs by knobs {
            override fun setMediaVolumeIndex(index: Int) = knobs.setMediaVolumeIndex(minOf(index, 10))
        }
        val r = DeviceController(phone).apply(DeviceAction.VOLUME_SET, 100, russian = true)
        assertFalse(r.succeeded)
        assertEquals("Получилось только 67% вместо 100%", r.message)
        // What did change can still be put back.
        undo(r)
        assertEquals(9, knobs.media.index)
    }

    @Test
    fun `up and down move exactly one platform step and stop honestly at the ends`() {
        assertEquals("Громкость 67%", run(DeviceAction.VOLUME_UP).message)
        assertEquals(10, knobs.media.index)
        assertEquals("Громкость 60%", run(DeviceAction.VOLUME_DOWN).message)
        assertEquals(9, knobs.media.index)

        knobs.media.index = 15
        knobs.media.calls.clear()
        val top = run(DeviceAction.VOLUME_UP)
        assertEquals("Громкость медиа уже на максимуме", top.message)
        assertNull(top.undo)
        assertFalse("no raise was sent at the top", "raise" in knobs.media.calls)

        knobs.media.index = 0
        knobs.media.muted = true
        assertEquals("Звук медиа и так выключен", run(DeviceAction.VOLUME_DOWN).message)

        knobs.media.index = 1
        knobs.media.muted = false
        val toZero = run(DeviceAction.VOLUME_DOWN)
        assertTrue("index 0 reads as muted, and that is the step asked for", toZero.succeeded)
        assertEquals("Звук медиа выключен", toZero.message)
    }

    @Test
    fun `mute keeps the level and unmute gives back the same 85 percent`() {
        knobs.media.index = 13 // 87 % of 15, the step nearest 85 %
        val muted = run(DeviceAction.MUTE)
        assertTrue(muted.succeeded)
        assertEquals("Звук медиа выключен", muted.message)
        assertEquals(13, knobs.media.index)
        assertTrue(knobs.media.muted)

        val back = run(DeviceAction.UNMUTE)
        assertTrue(back.succeeded)
        assertEquals("Звук медиа включён, 87%", back.message)
        assertEquals(13, knobs.media.index)
        assertFalse("no hard-coded 40 %", knobs.media.calls.any { it.startsWith("set") })
    }

    @Test
    fun `unmute at zero does not promise sound`() {
        knobs.media.index = 0
        knobs.media.muted = true
        val r = run(DeviceAction.UNMUTE)
        assertTrue(r.message, r.message.contains("громкость на нуле"))

        knobs.media.muted = false
        assertTrue(run(DeviceAction.UNMUTE).message.contains("громкость на нуле"))

        // A build that lifts the stream to its first step says what it got.
        knobs.media.muted = true
        knobs.media.unmuteLiftsZero = true
        assertEquals("Звук медиа включён, 7%", run(DeviceAction.UNMUTE).message)
    }

    @Test
    fun `raising a muted stream unmutes it and steps from its own level`() {
        knobs.media.muted = true
        val r = run(DeviceAction.VOLUME_UP)
        assertTrue(r.succeeded)
        assertEquals(10, knobs.media.index)
        assertFalse(knobs.media.muted)
        undo(r)
        assertEquals(9, knobs.media.index)
        assertTrue("undo puts the mute back too", knobs.media.muted)
    }

    @Test
    fun `fixed volume, ignored mute and a refused change are failures, never verified`() {
        knobs.media.fixed = true
        listOf(DeviceAction.VOLUME_UP, DeviceAction.VOLUME_DOWN, DeviceAction.MUTE, DeviceAction.UNMUTE).forEach {
            val r = run(it)
            assertEquals(it.name, DeviceActionResult.Mode.FAILED, r.mode)
            assertTrue(it.name, r.message.contains("фиксирована"))
        }
        val set = run(DeviceAction.VOLUME_SET, 30)
        assertEquals(DeviceActionResult.Mode.FAILED, set.mode)
        assertFalse(set.succeeded)

        knobs.media.fixed = false
        knobs.media.ignoreWrites = true
        val mute = run(DeviceAction.MUTE)
        assertFalse(mute.succeeded)
        assertEquals("Звук не выключился", mute.message)
        assertNull(mute.undo)

        knobs.media.ignoreWrites = false
        knobs.media.refuse = true
        val refused = run(DeviceAction.VOLUME_SET, 80)
        assertEquals(DeviceActionResult.Mode.NEEDS_PERMISSION, refused.mode)
        assertFalse(refused.succeeded)
        assertNull(refused.undo)
    }

    @Test
    fun `undo of a level set while muted restores the level and the mute`() {
        knobs.media.muted = true
        val r = run(DeviceAction.VOLUME_SET, 40)
        assertTrue(r.succeeded)
        assertFalse(knobs.media.muted)
        assertEquals(listOf(DeviceAction.VOLUME_SET, DeviceAction.MUTE), r.undo!!.steps.map { it.action })
        undo(r)
        assertEquals(9, knobs.media.index)
        assertTrue(knobs.media.muted)
    }

    @Test
    fun `undo levels land back on the same index on common stream sizes`() {
        listOf(15, 25, 30).forEach { max ->
            val v = MediaVolume(0, 0, max, muted = false)
            (0..max).forEach { i -> assertEquals("$i/$max", i, v.indexFor(v.percentOf(i))) }
        }
    }

    @Test
    fun `during a call the reply says only media changed`() {
        knobs.media.duringCall = true
        assertEquals("Громкость 53% (медиа; громкость разговора не менялась)", run(DeviceAction.VOLUME_SET, 50).message)
    }

    @Test
    fun `do not disturb without access opens the grant screen and says so`() {
        knobs.dndGranted = false
        val r = run(DeviceAction.DND_ON)
        assertEquals(DeviceActionResult.Mode.NEEDS_PERMISSION, r.mode)
        assertEquals(listOf("dnd_access"), knobs.opened)
        assertFalse(knobs.dndState)
    }

    @Test
    fun `do not disturb already on is said, and leaves nothing to undo`() {
        knobs.dndState = true
        val r = run(DeviceAction.DND_ON)
        assertTrue(r.succeeded)
        assertTrue(r.message.contains("уже"))
        assertNull(r.undo)
    }

    @Test
    fun `do not disturb undo turns it back off`() {
        val r = run(DeviceAction.DND_ON)
        assertTrue(knobs.dndState)
        undo(r)
        assertFalse(knobs.dndState)
    }

    @Test
    fun `fully silent needs do not disturb access, and asks for it instead of failing`() {
        knobs.dndGranted = false
        val r = run(DeviceAction.RINGER_SILENT)
        assertEquals(DeviceActionResult.Mode.NEEDS_PERMISSION, r.mode)
        assertEquals(Ringer.NORMAL, knobs.ringerMode)
    }

    @Test
    fun `vibrate needs no special access and undoes to the ringer it replaced`() {
        knobs.dndGranted = false
        val r = run(DeviceAction.RINGER_VIBRATE)
        assertTrue(r.succeeded)
        assertEquals("Только вибрация", r.message)
        undo(r)
        assertEquals(Ringer.NORMAL, knobs.ringerMode)
    }

    @Test
    fun `brightness turns off auto, and the undo turns auto back on`() {
        val r = run(DeviceAction.BRIGHTNESS_SET, 10)
        assertTrue(r.succeeded)
        assertFalse(knobs.auto)
        assertEquals(10, knobs.brightness)
        undo(r)
        assertTrue(knobs.auto)
    }

    @Test
    fun `manual brightness undoes to the previous level`() {
        knobs.auto = false
        val r = run(DeviceAction.BRIGHTNESS_UP)
        assertEquals(40 + DeviceController.BRIGHTNESS_STEP, knobs.brightness)
        undo(r)
        assertEquals(40, knobs.brightness)
    }

    @Test
    fun `brightness without the system-settings grant opens that screen`() {
        knobs.canWrite = false
        val r = run(DeviceAction.BRIGHTNESS_SET, 80)
        assertEquals(DeviceActionResult.Mode.NEEDS_PERMISSION, r.mode)
        assertEquals(listOf("write_settings"), knobs.opened)
    }

    @Test
    fun `wifi already in the asked state is said, no panel`() {
        val r = run(DeviceAction.WIFI_ON)
        assertTrue(r.succeeded)
        assertEquals("Wi-Fi уже включён", r.message)
        assertTrue(knobs.opened.isEmpty())
    }

    @Test
    fun `wifi off opens the panel and does not claim it is off`() {
        val r = run(DeviceAction.WIFI_OFF)
        assertEquals(DeviceActionResult.Mode.OPENED_SYSTEM_UI, r.mode)
        assertNull(r.verified)
        assertFalse(r.succeeded)
        assertEquals(listOf("WIFI"), knobs.opened)
        assertTrue(r.message.contains("осталось выключить"))
    }

    @Test
    fun `english replies for english requests`() {
        assertEquals("Volume 33%", controller.apply(DeviceAction.VOLUME_SET, 30, russian = false).message)
    }
}

class ActionSchemaTest {

    private val now = LocalDateTime.of(2026, 10, 7, 12, 0)

    private fun commandOf(e: ActionEnvelope) =
        (AgentTools.interpret(e.tool, e.args, now) as AgentTools.Call.Command).command

    @Test
    fun `a learned command stored by 0_9 still runs after the upgrade`() {
        // Exactly what LearnedCommands wrote before envelopes had a version.
        val v1 = """{"tool":"phone_control","args":{"action":"volume_set","level":30}}"""
        val e = ActionSchema.parse(v1)!!
        assertEquals(ActionSchema.CURRENT, e.version)
        assertEquals(CommandResult.DeviceControl(DeviceAction.VOLUME_SET, 30), commandOf(e))
    }

    @Test
    fun `every v1 phone_control value has the same meaning in v2`() {
        val expected = mapOf(
            "volume_up" to DeviceAction.VOLUME_UP, "volume_down" to DeviceAction.VOLUME_DOWN,
            "mute" to DeviceAction.MUTE, "unmute" to DeviceAction.UNMUTE,
            "dnd_on" to DeviceAction.DND_ON, "dnd_off" to DeviceAction.DND_OFF,
            "wifi_panel" to DeviceAction.OPEN_WIFI_PANEL, "bluetooth_panel" to DeviceAction.OPEN_BLUETOOTH_PANEL
        )
        expected.forEach { (v1, action) ->
            val e = ActionSchema.parse("""{"tool":"phone_control","args":{"action":"$v1"}}""")!!
            assertEquals(v1, CommandResult.DeviceControl(action), commandOf(e))
        }
    }

    @Test
    fun `other tools carry over unchanged`() {
        val e = ActionSchema.parse("""{"tool":"flashlight","args":{"state":"on"}}""")!!
        assertEquals(2, e.version)
        assertEquals(CommandResult.Flashlight(on = true), commandOf(e))
    }

    @Test
    fun `what can't be migrated is dropped, not guessed`() {
        assertNull(ActionSchema.parse("""{"tool":"phone_control","args":{"action":"reboot"}}"""))
        assertNull(ActionSchema.parse("""{"tool":"phone_control","args":{"action":"volume_set"}}"""))
        assertNull(ActionSchema.parse("""{"version":99,"tool":"flashlight","args":{}}"""))
        assertNull(ActionSchema.parse("not json"))
    }

    @Test
    fun `an envelope survives its own round trip`() {
        val e = ActionEnvelope("phone_control", JsonObject(mapOf("target" to JsonPrimitive("dnd"), "state" to JsonPrimitive("on"))))
        assertEquals(e, ActionSchema.parse(e.toJson()))
    }

    @Test
    fun `v2 phone_control covers ringer, brightness and radios`() {
        fun c(json: String) = (AgentTools.interpret("phone_control", kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject, now) as AgentTools.Call.Command).command
        assertEquals(CommandResult.DeviceControl(DeviceAction.RINGER_SILENT), c("""{"target":"ringer","state":"silent"}"""))
        assertEquals(CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 20), c("""{"target":"brightness","level":20}"""))
        assertEquals(CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_AUTO), c("""{"target":"brightness","state":"auto"}"""))
        assertEquals(CommandResult.DeviceControl(DeviceAction.WIFI_OFF), c("""{"target":"wifi","state":"off"}"""))
        assertTrue(AgentTools.interpret("phone_control", kotlinx.serialization.json.Json.parseToJsonElement("""{"target":"dnd"}""") as JsonObject, now) is AgentTools.Call.Invalid)
    }
}

class DeviceRoutingTest {

    private val router = CommandRouter()

    private fun action(text: String) = (router.route(text) as? CommandResult.DeviceControl)?.action

    @Test
    fun `ringer phrases reach the ringer, not the media volume`() {
        assertEquals(DeviceAction.RINGER_SILENT, action("включи беззвучный режим"))
        assertEquals(DeviceAction.RINGER_SILENT, action("поставь телефон на беззвучный"))
        assertEquals(DeviceAction.RINGER_VIBRATE, action("поставь на вибрацию"))
        assertEquals(DeviceAction.RINGER_NORMAL, action("выключи беззвучный режим"))
        assertEquals(DeviceAction.RINGER_NORMAL, action("отключи вибрацию"))
        assertEquals(DeviceAction.MUTE, action("выключи звук"))
    }

    @Test
    fun `russian and english volume phrases reach the same actions as the llm tool`() {
        val now = LocalDateTime.of(2026, 10, 7, 12, 0)
        fun tool(state: String) = (AgentTools.interpret(
            "phone_control",
            kotlinx.serialization.json.Json.parseToJsonElement("""{"target":"volume","state":"$state"}""") as JsonObject,
            now
        ) as AgentTools.Call.Command).command
        val pairs = listOf(
            listOf("сделай громче", "volume up") to "up",
            listOf("сделай тише", "turn the volume down") to "down",
            listOf("выключи звук", "mute") to "off",
            listOf("включи звук", "unmute") to "on"
        )
        pairs.forEach { (phrases, toolAction) ->
            phrases.forEach { assertEquals(it, tool(toolAction), router.route(it)) }
        }
        assertEquals(CommandResult.DeviceControl(DeviceAction.VOLUME_SET, 50), router.route("громкость на 50"))
        assertEquals(CommandResult.DeviceControl(DeviceAction.VOLUME_SET, 50), router.route("set volume to 50"))
    }

    @Test
    fun `brightness by number, direction and auto`() {
        assertEquals(
            CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 30),
            router.route("яркость на 30")
        )
        assertEquals(DeviceAction.BRIGHTNESS_UP, action("сделай ярче"))
        assertEquals(DeviceAction.BRIGHTNESS_DOWN, action("убавь яркость"))
        assertEquals(DeviceAction.BRIGHTNESS_AUTO, action("включи автояркость"))
        assertEquals(CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, 100), router.route("яркость на максимум"))
    }

    @Test
    fun `radios on, off, or just their settings`() {
        assertEquals(DeviceAction.WIFI_OFF, action("выключи вайфай"))
        assertEquals(DeviceAction.WIFI_ON, action("включи wi-fi"))
        assertEquals(DeviceAction.BLUETOOTH_OFF, action("отключи блютуз"))
        assertEquals(DeviceAction.OPEN_BLUETOOTH_PANEL, action("bluetooth"))
    }
}
