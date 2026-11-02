package com.friday.ai.localai

import com.friday.ai.core.WeatherReport
import com.friday.ai.data.remote.WeatherApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWeatherTest {
    private val today = LocalDate.of(2026, 11, 2)

    @Test
    fun `today says actual temperature feel wind and precipitation`() {
        assertEquals(
            "Agora em Salvador: 27°C, sensação de 30°C, parcialmente nublado, vento de 18 km/h. " +
                "Precipitação prevista: 60% (2,5 mm). Fonte: Open-Meteo https://open-meteo.com/",
            formatLocalWeather(report(), 0)
        )
    }

    @Test
    fun `tomorrow says min max and precipitation chance`() {
        assertEquals(
            "Amanhã em Salvador: mínima de 23°C e máxima de 29°C, chuva. " +
                "Precipitação prevista: 80% (7 mm). Fonte: Open-Meteo https://open-meteo.com/",
            formatLocalWeather(report(), 1)
        )
    }

    @Test
    fun `missing requested day returns null`() {
        assertNull(formatLocalWeather(report(), 2))
    }

    @Test
    fun `unknown city returns null without requesting forecast`() = runTest {
        val api = mockk<WeatherApiService>()
        coEvery { api.geocode("Cidade Inexistente", russian = false) } returns null

        assertNull(LocalWeather(api).reply("Cidade Inexistente", 0))
        coVerify(exactly = 1) { api.geocode("Cidade Inexistente", russian = false) }
        coVerify(exactly = 0) { api.forecast(any(), any()) }
    }

    private fun report() = WeatherReport.Forecast(
        place = "Salvador",
        now = WeatherReport.Hour(
            time = LocalDateTime.of(2026, 11, 2, 10, 0),
            temperature = 27.4,
            feelsLike = 29.6,
            precipitationChance = 60,
            precipitationMm = 2.5,
            code = 2,
            windKmh = 17.6
        ),
        hours = emptyList(),
        days = listOf(
            WeatherReport.Day(today, 23.0, 30.0, 2, 2.5, 60, 22.0),
            WeatherReport.Day(today.plusDays(1), 23.0, 29.0, 61, 7.0, 80, 25.0)
        )
    )
}

