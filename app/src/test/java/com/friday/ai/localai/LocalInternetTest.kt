package com.friday.ai.localai

import java.io.IOException
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInternetTest {
    @Test
    fun `ordinary chat never reaches internet`() = runTest {
        val backend = FakeSearch()
        val internet = LocalInternet.create(search = backend)

        assertNull(internet.reply("me conta uma piada"))
        assertNull(internet.reply("me sinto triste hoje?"))
        assertEquals(emptyList<String>(), backend.queries)
    }

    @Test
    fun `explicit Portuguese search sends current prompt only`() = runTest {
        val backend = FakeSearch(SearchResult.Found(listOf(SearchItem("Título", "Fato curto", "https://fonte.test/a"))))
        val internet = LocalInternet.create(search = backend)

        val reply = internet.reply("Pesquise na internet eleições municipais")

        assertEquals(listOf("eleições municipais"), backend.queries)
        assertTrue(reply!!.contains("Fato curto"))
        assertTrue(reply.contains("https://fonte.test/a"))
    }

    @Test
    fun `current public factual question searches but timeless question stays local`() = runTest {
        val backend = FakeSearch(SearchResult.Empty)
        val internet = LocalInternet.create(search = backend)

        internet.reply("Qual a cotação do dólar hoje?")
        assertEquals(listOf("Qual a cotação do dólar hoje?"), backend.queries)
        assertNull(internet.reply("Qual a capital do Brasil?"))
    }

    @Test
    fun `weather only matches meteorological questions`() = runTest {
        val weather = FakeWeather()
        val internet = LocalInternet.create(weatherForCity = weather::answer)

        assertNull(internet.reply("quanto tempo leva para chegar?"))
        assertNull(internet.reply("me explica tempo verbal"))
        assertEquals("Qual cidade você quer consultar?", internet.reply("como está o clima?"))
        assertEquals("Clima em Salvador 0", internet.reply("previsão do tempo em Salvador hoje"))
        assertEquals("Salvador", weather.cities.single().first)
        assertEquals(0, weather.cities.single().second)
    }

    @Test
    fun `weather supports Portuguese today tomorrow and dynamic home city`() = runTest {
        var home = "Recife"
        val weather = FakeWeather()
        val internet = LocalInternet.create(weather::answer, homeCity = { home })

        // This is the reported wording. It must initialize and route on Android too.
        assertEquals("Clima em Recife 0", internet.reply("como tava o tempo hj"))
        assertEquals("Clima em Recife 1", internet.reply("vai chover amanhã?"))
        home = "Salvador"
        assertEquals("Clima em Salvador 0", internet.reply("como está o tempo?"))
    }

    @Test
    fun `explicit search takes precedence over weather words`() = runTest {
        val backend = FakeSearch()
        val internet = LocalInternet.create(search = backend)

        internet.reply("pesquise clima organizacional")

        assertEquals(listOf("clima organizacional"), backend.queries)
    }

    @Test
    fun `URLs are not transmitted`() = runTest {
        val backend = FakeSearch()
        val internet = LocalInternet.create(search = backend)

        assertTrue(internet.reply("pesquise https://example.test/segredo")!!.contains("link enviado"))
        assertEquals(emptyList<String>(), backend.queries)
    }

    @Test
    fun `network and weather failures are honest and cancellation propagates`() = runTest {
        val unavailable = LocalInternet.create(search = FakeSearch(SearchResult.Unavailable))
        assertTrue(unavailable.reply("busque preço do ouro")!!.contains("Não consegui pesquisar"))

        val weatherFailure = LocalInternet.create(weatherForCity = { _, _ -> throw IOException("offline") })
        assertTrue(weatherFailure.reply("vai chover em Salvador?")!!.contains("Não consegui consultar"))

        val cancelling = LocalInternet.create(search = object : SearchBackend {
            override suspend fun search(query: String): SearchResult = throw CancellationException("stop")
        })
        try {
            cancelling.reply("procure notícias de hoje")
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation is a control signal, not a network failure.
        }
    }

    @Test
    fun `parser keeps snippets with href before class and result ordering`() {
        val html = """
            <article><a href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fa.test" class="result__a">Primeiro</a>
            <div class="result__snippet">Resumo <b>um</b></div></article>
            <article><a class="result__a" href="https://b.test">Segundo</a>
            <span class="result__snippet">Resumo dois</span></article>
        """.trimIndent()

        val result = DuckDuckGoSearch.parseSearchHtml(html) as SearchResult.Found

        assertEquals(listOf("Primeiro", "Segundo"), result.items.map(SearchItem::title))
        assertEquals(listOf("Resumo um", "Resumo dois"), result.items.map(SearchItem::snippet))
        assertEquals("https://a.test", result.items.first().url)
    }

    class FakeSearch(private val response: SearchResult = SearchResult.Empty) : SearchBackend {
        val queries = mutableListOf<String>()

        override suspend fun search(query: String): SearchResult {
            queries += query
            return response
        }
    }

    private class FakeWeather {
        val cities = mutableListOf<Pair<String, Int>>()

        suspend fun answer(city: String, dayOffset: Int): String {
            cities += city to dayOffset
            return "Clima em $city $dayOffset"
        }
    }
}

class LocalInternetConversationTest {
    @Test
    fun `reported weather city turn is resolved from immediate local history`() = runTest {
        val weather = FakeWeatherConversation()
        val internet = LocalInternet.create(weatherForCity = weather::answer)
        val asked = Message(content = "como o tempo está hoje?", role = MessageRole.USER)
        val cityQuestion = Message(content = "Qual cidade você quer consultar?", role = MessageRole.ASSISTANT)

        assertEquals("Qual cidade você quer consultar?", internet.reply(asked.content))
        assertEquals("Clima em Paripiranga 0", (internet.request("Paripiranga", listOf(asked, cityQuestion)) as LocalInternetReply.Direct).text)
        assertEquals(listOf("Paripiranga" to 0), weather.cities)
    }

    @Test
    fun `city-like text without pending weather question stays local`() = runTest {
        val weather = FakeWeatherConversation()
        val internet = LocalInternet.create(weatherForCity = weather::answer)
        val unrelated = listOf(Message(content = "Beleza", role = MessageRole.ASSISTANT))

        assertNull(internet.request("Paripiranga", unrelated))
        assertEquals(emptyList<Pair<String, Int>>(), weather.cities)
    }

    @Test
    fun `official Roblox site is direct grounded answer without search or model`() = runTest {
        val backend = LocalInternetTest.FakeSearch()
        val internet = LocalInternet.create(search = backend)

        assertEquals("O site oficial do Roblox é https://www.roblox.com/", internet.reply("qual o site oficial do roblox?"))
        assertEquals(emptyList<String>(), backend.queries)
    }

    private class FakeWeatherConversation {
        val cities = mutableListOf<Pair<String, Int>>()
        suspend fun answer(city: String, dayOffset: Int): String {
            cities += city to dayOffset
            return "Clima em $city $dayOffset"
        }
    }
}
