package com.friday.ai.localai

import com.friday.ai.core.WeatherReport
import com.friday.ai.data.remote.WeatherApiService
import kotlin.math.roundToInt

/** Weather answer for an explicitly named city; it never uses GPS or history. */
class LocalWeather(private val api: WeatherApiService) {
    suspend fun reply(city: String, dayOffset: Int): String? {
        val place = api.geocode(city.trim(), russian = false) ?: return null
        return api.forecast(place, FORECAST_DAYS)?.let { formatLocalWeather(it, dayOffset) }
    }
}

/** Pure formatter so weather wording can be tested without a network call. */
internal fun formatLocalWeather(forecast: WeatherReport.Forecast, dayOffset: Int): String? =
    if (dayOffset <= 0) today(forecast) else ahead(forecast, dayOffset)

private fun today(forecast: WeatherReport.Forecast): String = with(forecast.now) {
    buildString {
        append("Agora em ${forecast.place}: ${degrees(temperature)}, sensação de ${degrees(feelsLike)}, ")
        append(description(code)).append(", vento de ${windKmh.roundToInt()} km/h.")
        val day = forecast.days.firstOrNull { it.date == time.toLocalDate() }
        precipitation(day?.precipitationChance, day?.precipitationMm)?.let { append(" $it") }
        append(" Fonte: Open-Meteo https://open-meteo.com/")
    }
}

private fun ahead(forecast: WeatherReport.Forecast, dayOffset: Int): String? {
    val date = forecast.now.time.toLocalDate().plusDays(dayOffset.toLong())
    val day = forecast.days.firstOrNull { it.date == date } ?: return null
    val label = when (dayOffset) {
        1 -> "Amanhã"
        2 -> "Depois de amanhã"
        else -> "Em $dayOffset dias"
    }
    return buildString {
        append("$label em ${forecast.place}: mínima de ${degrees(day.min)} e máxima de ${degrees(day.max)}, ")
        append(description(day.code)).append('.')
        precipitation(day.precipitationChance, day.precipitationMm)?.let { append(" $it") }
        append(" Fonte: Open-Meteo https://open-meteo.com/")
    }
}

private fun precipitation(chance: Int?, millimeters: Double?): String? = when {
    chance == null && millimeters == null -> null
    millimeters != null && millimeters > 0.0 && chance != null ->
        "Precipitação prevista: ${chance}% (${amount(millimeters)} mm)."
    millimeters != null && millimeters > 0.0 -> "Precipitação prevista: ${amount(millimeters)} mm."
    chance != null -> "Chance de precipitação: $chance%."
    else -> null
}

private fun degrees(value: Double): String = "${value.roundToInt()}°C"

private fun amount(value: Double): String = if (value == value.roundToInt().toDouble()) {
    value.roundToInt().toString()
} else {
    "%.1f".format(java.util.Locale.US, value).replace('.', ',')
}

private const val FORECAST_DAYS = 3

/** Open-Meteo WMO weather interpretation codes, in Brazilian Portuguese. */
@Suppress("MagicNumber")
private fun description(code: Int): String = when (code) {
    0 -> "céu limpo"
    1 -> "predominantemente limpo"
    2 -> "parcialmente nublado"
    3 -> "encoberto"
    45, 48 -> "nevoeiro"
    51, 53, 55 -> "garoa"
    56, 57 -> "garoa congelante"
    61, 63, 65 -> "chuva"
    66, 67 -> "chuva congelante"
    71, 73, 75, 77 -> "neve"
    80, 81, 82 -> "pancadas de chuva"
    85, 86 -> "pancadas de neve"
    95 -> "trovoadas"
    96, 99 -> "trovoadas com granizo"
    else -> "condições meteorológicas indisponíveis"
}
