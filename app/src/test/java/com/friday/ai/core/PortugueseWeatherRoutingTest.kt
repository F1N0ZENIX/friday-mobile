package com.friday.ai.core

import com.friday.ai.domain.model.CommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortugueseWeatherRoutingTest {

    private val router = CommandRouter()

    @Test
    fun `Brazilian weather questions reach the forecast`() {
        assertEquals(CommandResult.Weather(null, 0), router.route("Qual é a previsão do tempo?"))
        assertEquals(CommandResult.Weather("Salvador", 0), router.route("Como está o tempo em Salvador?"))
        assertEquals(CommandResult.Weather(null, 1), router.route("Vai chover amanhã?"))
        assertEquals(
            CommandResult.Weather(null, 2),
            router.route("Qual é a previsão do tempo depois de amanhã?")
        )
    }

    @Test
    fun `Brazilian city statements are remembered`() {
        assertEquals(
            CommandResult.SetHomeCity("Paripiranga"),
            WeatherRequest.parse("Meu município é Paripiranga", 0)
        )
        assertEquals(
            CommandResult.SetHomeCity("Salvador"),
            WeatherRequest.parse("Eu moro em Salvador", 0)
        )
    }

    @Test
    fun `casual mention of time is not a weather request`() {
        assertNull(WeatherRequest.parse("O tempo passou muito rápido", 0))
    }
}
