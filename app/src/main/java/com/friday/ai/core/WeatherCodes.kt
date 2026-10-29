package com.friday.ai.core

/**
 * Maps WMO weather codes (what Open-Meteo returns) to something speakable.
 *
 * Kept separate and pure so the wording can be tested and tweaked without
 * touching networking.
 */
object WeatherCodes {

    /** Short description in Russian or Brazilian Portuguese, chosen by [russian]. */
    fun describe(code: Int, russian: Boolean): String = when (code) {
        0 -> if (russian) "ясно" else "céu limpo"
        1 -> if (russian) "преимущественно ясно" else "predomínio de sol"
        2 -> if (russian) "переменная облачность" else "parcialmente nublado"
        3 -> if (russian) "пасмурно" else "encoberto"
        45, 48 -> if (russian) "туман" else "neblina"
        51, 53, 55 -> if (russian) "морось" else "garoa"
        56, 57 -> if (russian) "ледяная морось" else "garoa congelante"
        61 -> if (russian) "небольшой дождь" else "chuva fraca"
        63 -> if (russian) "дождь" else "chuva"
        65 -> if (russian) "сильный дождь" else "chuva forte"
        66, 67 -> if (russian) "ледяной дождь" else "chuva congelante"
        71 -> if (russian) "небольшой снег" else "neve fraca"
        73 -> if (russian) "снег" else "neve"
        75 -> if (russian) "сильный снег" else "neve forte"
        77 -> if (russian) "снежная крупа" else "grãos de neve"
        80, 81 -> if (russian) "ливень" else "pancadas de chuva"
        82 -> if (russian) "сильный ливень" else "pancadas de chuva intensas"
        85, 86 -> if (russian) "снегопад" else "pancadas de neve"
        95 -> if (russian) "гроза" else "trovoada"
        96, 99 -> if (russian) "гроза с градом" else "trovoada com granizo"
        else -> if (russian) "неопределённая погода" else "condições indefinidas"
    }

    /**
     * Builds the one-sentence answer Friday speaks. Deliberately short —
     * spoken replies are capped at a sentence.
     */
    fun summarize(
        place: String,
        temperature: Double,
        feelsLike: Double,
        code: Int,
        russian: Boolean
    ): String {
        val t = Math.round(temperature).toInt()
        val feels = Math.round(feelsLike).toInt()
        val condition = describe(code, russian)
        val mentionFeels = Math.abs(feels - t) >= 3

        return if (russian) {
            buildString {
                append("В $place сейчас $t°, $condition")
                if (mentionFeels) append(", ощущается как $feels°")
                append(".")
            }
        } else {
            buildString {
                append("Agora em $place: $t°, $condition")
                if (mentionFeels) append(", sensação térmica de $feels°")
                append(".")
            }
        }
    }
}
