package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction

/**
 * How the phone's own settings are asked for in words: the ringer
 * ("беззвучный режим", "на вибрацию"), brightness ("яркость на 30",
 * "ярче", "автояркость") and whether a radio phrase means on or off.
 * Part of [CommandRouter]; kept apart so the router stays a list of kinds
 * of request.
 */
internal object DevicePhrases {

    private const val MIN_BRIGHTNESS = 5
    private const val MAX_LEVEL = 100

    private val offWords = Regex(
        "(?:выключ|отключ|выруби|\\boff\\b|disable|turn off|" +
            "(?:^|\\s)(?:deslig\\p{L}*|desativ\\p{L}*)(?=\\s|$))"
    )
    private val onWords = Regex(
        "(?:включ|вруби|\\bon\\b|enable|turn on|" +
            "(?:^|\\s)(?:lig\\p{L}*|ativ\\p{L}*)(?=\\s|$))"
    )

    private val ringerSilentPattern = Regex(
        "(?:беззвучн\\p{L}* режим|режим без звука|на беззвучн|в беззвучн|полностью беззвучн|" +
            "полная тишина|silent mode|phone on silent|modo silencioso|celular no silencioso)"
    )
    private val ringerVibratePattern = Regex(
        "(?:вибраци|на вибро|режим вибро|vibrate|vibration|modo vibra(?:ção|cao|r)|modo vibratório)"
    )
    private val ringerNormalPattern = Regex(
        "(?:(?:выключи|отключи|убери) беззвучн|верни звонок|включи звонок|звук звонка|обычный режим звонка|" +
            "ringer on|turn off silent|(?:desativar|desative|desligar|desligue|tirar) (?:o )?modo silencioso)"
    )

    private val brightnessSetPattern =
        Regex(
            "(?:яркост\\p{L}*|brightness|brilho|luminosidade)\\s*" +
                "(?:da tela\\s*)?(?:на|to|em|para)?\\s*(\\d{1,3})\\s*(?:%|por cento|процент\\p{L}*)?"
        )
    private val autoBrightnessPattern = Regex("(?:автояркост|авто ?яркост|автоматическ\\p{L}* яркост|auto.?brightness|brilho autom[aá]tico|luminosidade autom[aá]tica)")

    /** "Выключи …" / "включи …" / neither: which of the three a radio phrase asks for. */
    fun onOff(lower: String, on: DeviceAction, off: DeviceAction, neither: DeviceAction): DeviceAction = when {
        offWords.containsMatchIn(lower) -> off
        onWords.containsMatchIn(lower) -> on
        else -> neither
    }

    /** The ringer, not the media volume: "беззвучный режим", "на вибрацию", "верни звонок". */
    fun ringer(lower: String): CommandResult? = when {
        ringerNormalPattern.containsMatchIn(lower) -> CommandResult.DeviceControl(DeviceAction.RINGER_NORMAL)
        // "Отключи вибрацию" turns vibration off, i.e. back to an ordinary ringer.
        ringerVibratePattern.containsMatchIn(lower) -> CommandResult.DeviceControl(
            if (offWords.containsMatchIn(lower)) DeviceAction.RINGER_NORMAL else DeviceAction.RINGER_VIBRATE
        )
        ringerSilentPattern.containsMatchIn(lower) -> CommandResult.DeviceControl(DeviceAction.RINGER_SILENT)
        else -> null
    }

    fun brightness(lower: String): CommandResult? {
        val mentions = lower.contains("яркост") || lower.contains("brightness") ||
            lower.contains("brilho") || lower.contains("luminosidade")
        brightnessSetPattern.find(lower)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let {
                return CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, it.coerceIn(MIN_BRIGHTNESS, MAX_LEVEL))
            }
        }
        return when {
            autoBrightnessPattern.containsMatchIn(lower) -> CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_AUTO)
            mentions && Regex("максим|полную|max|máxim|total").containsMatchIn(lower) ->
                CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, MAX_LEVEL)
            mentions && Regex("миним|\\bmin(?:imum)?\\b|mínim").containsMatchIn(lower) ->
                CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_SET, MIN_BRIGHTNESS)
            Regex("(?:^|\\s)ярче(?:\\s|$)|экран ярче|brighter").containsMatchIn(lower) ||
                mentions && Regex("прибав|увелич|повыс|up|aument|elevar").containsMatchIn(lower) ->
                CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_UP)
            Regex("(?:^|\\s)темнее(?:\\s|$)|экран темнее|dimmer").containsMatchIn(lower) ||
                mentions && Regex("убав|уменьш|пониз|down|diminu|reduz").containsMatchIn(lower) ->
                CommandResult.DeviceControl(DeviceAction.BRIGHTNESS_DOWN)
            else -> null
        }
    }
}
