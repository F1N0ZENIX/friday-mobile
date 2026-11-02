"package com.friday.ai.localai

import java.io.IOException
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import com.friday.ai.domain.model.Message
import com.friday.ai.domain.model.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * Small, explicit internet capability for the local chat path.
 *
 * Only a bounded public-fact query is sent to a fixed search endpoint. No conversation,
 * memory, device location, or user supplied URL is transmitted.
 */
class LocalInternet private constructor(
    private val search: SearchBackend,
    private val weatherForCity: suspend (city: String, dayOffset: Int) -> String?,
    private val homeCity: suspend () -> String?
) {
    /**
     * Classifies the local-only internet turn. Search snippets stay structured so they are
     * never confused with a completed assistant answer before local inference sees them.
     */
    suspend fun request(prompt: String, history: List<Message> = emptyList()): LocalInternetReply? = when (
        val request = InternetIntent.classify(prompt).takeUnless { it == InternetIntent.None }
            ?: weatherCityReply(prompt, history)
    ) {
        null, InternetIntent.None -> null
        InternetIntent.UrlRejected -> LocalInternetReply.Direct(
            "Não posso pesquisar um link enviado. Me diga o assunto da busca."
        )
        is InternetIntent.Weather -> LocalInternetReply.Direct(weatherReply(request))
        is InternetIntent.OfficialSite -> LocalInternetReply.Direct(
            "O site oficial do Roblox é https://www.roblox.com/"
        )
        is InternetIntent.Search -> safeSearchReply(request.query)
    }

    /** Compatibility helper for callers that only need a deterministic direct answer. */
    suspend fun reply(prompt: String): String? = when (val result = request(prompt)) {
        null -> null
        is LocalInternetReply.Direct -> result.text
        is LocalInternetReply.SearchSources -> result.fallbackText()
    }

    /** A city is accepted only as the immediate answer to Friday's own city question. */
    private fun weatherCityReply(prompt: String, history: List<Message>): InternetIntent.Weather? =
        prompt.trim().replace(Regex("\\s+"), " ")
            .takeIf { it.matches(Regex("[\\p{L}][\\p{L} .'’-]{1,60}")) }
            ?.let { city ->
                history.lastOrNull()
                    ?.takeIf { last ->
                        last.role == MessageRole.ASSISTANT &&
                            last.content.trim().startsWith("Qual cidade você quer consultar?")
                    }
                    ?.let { InternetIntent.Weather(city, 0) }
            }

    private suspend fun weatherReply(request: InternetIntent.Weather): String {
        try {
            val city = request.city ?: homeCity()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return "Qual cidade você quer consultar?"
            return weatherForCity(city, request.dayOffset)
                ?: "Não consegui consultar o tempo em $city agora. Tenta de novo daqui a pouco."
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return "Não consegui consultar o tempo agora. Tenta de novo daqui a pouco."
        }
    }

    private suspend fun safeSearchReply(query: String): LocalInternetReply {
        return try {
            searchReply(query)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            LocalInternetReply.Direct("Não consegui pesquisar na internet agora. Tenta de novo daqui a pouco.")
        }
    }

    private suspend fun searchReply(query: String): LocalInternetReply = when (val result = search.search(query)) {
        is SearchResult.Found -> LocalInternetReply.SearchSources(result.items)
        SearchResult.Empty -> LocalInternetReply.Direct("Não achei resultados públicos para isso.")
        SearchResult.Unavailable -> LocalInternetReply.Direct(
            "Não consegui pesquisar na internet agora. Tenta de novo daqui a pouco."
        )
    }

    companion object {
        /**
         * Factory for the parent integration. The weather callback receives only an explicit
         * city and day offset (zero for today, one for tomorrow); it must not use location.
         */
        fun create(
            weatherForCity: suspend (city: String, dayOffset: Int) -> String? = { _, _ -> null },
            homeCity: suspend () -> String? = { null },
            search: SearchBackend = DuckDuckGoSearch.create()
        ): LocalInternet = LocalInternet(search, weatherForCity, homeCity)
    }
}

/** A search result is untrusted reference data, never an instruction or a chat turn. */
sealed interface LocalInternetReply {
    data class Direct(val text: String) : LocalInternetReply
    data class SearchSources(val items: List<SearchItem>) : LocalInternetReply {
        fun fallbackText(): String = items.take(MAX_GROUNDED_SOURCES).joinToString("\n\n") { item ->
            buildString {
                append(item.title.trim().take(MAX_SOURCE_TITLE_CHARS))
                item.snippet.trim().take(MAX_SOURCE_SNIPPET_CHARS).takeIf { it.isNotBlank() }
                    ?.let { append("\n").append(it) }
                append("\nFonte: ").append(item.url.trim().take(MAX_SOURCE_URL_CHARS))
            }
        }.ifBlank { "Não achei resultados públicos para isso." }
    }
}

internal const val MAX_GROUNDED_SOURCES = 3
internal const val MAX_SOURCE_TITLE_CHARS = 160
internal const val MAX_SOURCE_SNIPPET_CHARS = 280
internal const val MAX_SOURCE_URL_CHARS = 500

/** Injectable boundary: tests use this instead of making a real HTTP request. */
interface SearchBackend {
    suspend fun search(query: String): SearchResult
}

data class SearchItem(val title: String, val snippet: String, val url: String)

sealed interface SearchResult {
    data class Found(val items: List<SearchItem>) : SearchResult
    data object Empty : SearchResult
    data object Unavailable : SearchResult
}

private sealed interface InternetIntent {
    data object None : InternetIntent
    data object UrlRejected : InternetIntent
    data class Weather(val city: String?, val dayOffset: Int) : InternetIntent
    data object OfficialSite : InternetIntent
    data class Search(val query: String) : InternetIntent

    companion object {
        private val explicitSearch = Regex(
            """\b(?:pesquise|pesquisar|busque|buscar|procure|procurar|pesquisa|busca|""" +
                """procura)\b(?:\s+(?:na\s+)?internet)?\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        private val url = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val weatherCity = Regex(
            """\b(?:em|para)\s+([\p{L}][\p{L} .'’-]{1,60})(?:[?!.]|$)""",
            RegexOption.IGNORE_CASE
        )
        private val dateQualifier = Regex(
            """\s+\b(?:hoje|amanh[aã]|agora|depois\s+de\s+amanh[aã])\b.*$""",
            RegexOption.IGNORE_CASE
        )
        private val publicFactPrefix = Regex(
            """^(?:qual(?:\s+(?:é|a|o))?|quais|quem|onde|como|quanto|me\s+diga|diga|mostre|tem|h[aá]|""" +
                """aconteceu|acontece)\b""",
            RegexOption.IGNORE_CASE
        )
        private val temporalFact = Regex(
            """\b(?:not[ií]cias|hoje|agora|atual|atualmente|resultado|placar|cota[cç][aã]o)\b""",
            RegexOption.IGNORE_CASE
        )
        private val weatherSignal = Regex(
            weatherWordsPattern(),
            RegexOption.IGNORE_CASE
        )
        private val tomorrow = Regex(
            """(?<![\p{L}\p{M}])amanh[aã](?![\p{L}\p{M}])""",
            RegexOption.IGNORE_CASE
        )

        private fun weatherWordsPattern(): String = listOf(
            """\bprevis[aã]o(?:\s+do\s+tempo)?\b""",
            """\b(?:como|qual).{0,30}\b(?:tempo|clima)\b""",
            """\b(?:tempo|clima)\b.{0,30}\b(?:hoje|amanh[aã]|agora|est[aá]|ficar|vai)\b""",
            """\b(?:vai\s+chover|chove(?:r)?|vai\s+fazer\s+(?:frio|calor|sol))\b"""
        ).joinToString("|") { "(?:$it)" }

        fun classify(prompt: String): InternetIntent {
            val text = prompt.trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty() || text.length > MAX_QUERY_CHARS) return None
            if (url.containsMatchIn(text)) return UrlRejected
            val explicit = explicitSearch.find(text)?.groupValues?.getOrNull(1)?.trim()
            return when {
                Regex(
                    """^qual\s+(?:é\s+)?o\s+site\s+oficial\s+do\s+roblox\??$""",
                    RegexOption.IGNORE_CASE
                ).matches(text) -> OfficialSite
                !explicit.isNullOrEmpty() -> Search(explicit)
                weatherSignal.containsMatchIn(text) -> {
                    val city = weatherCity.find(text)?.groupValues?.get(1)
                        ?.replace(dateQualifier, "")?.trim()?.trimEnd('?', '!', '.')
                    Weather(city?.takeIf { it.isNotEmpty() }, if (tomorrow.containsMatchIn(text)) 1 else 0)
                }
                text.endsWith('?') && publicFactPrefix.containsMatchIn(text) && temporalFact.containsMatchIn(text) ->
                    Search(text)
                else -> None
            }
        }
    }
}

/** Fixed HTTPS-only public endpoint; it never follows or fetches result URLs. */
internal class DuckDuckGoSearch private constructor(private val client: OkHttpClient) : SearchBackend {
    override suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            requestSearch("https://html.duckduckgo.com/html/?q=$encoded", ::parseSearchHtml)
                ?: requestSearch("https://www.bing.com/search?q=$encoded", ::parseBingHtml)
                ?: SearchResult.Unavailable
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            SearchResult.Unavailable
        } catch (_: RuntimeException) {
            SearchResult.Unavailable
        }
    }

    private fun requestSearch(
        endpoint: String,
        parser: (String) -> SearchResult
    ): SearchResult? {
        val request = Request.Builder().url(endpoint).header("User-Agent", USER_AGENT).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return SearchResult.Empty
            val buffer = Buffer()
            var remaining = MAX_BODY_BYTES + 1L
            while (remaining > 0) {
                val read = source.read(buffer, remaining)
                if (read == -1L) break
                remaining -= read
            }
            return if (buffer.size > MAX_BODY_BYTES) null else parser(buffer.readString(StandardCharsets.UTF_8))
        }
    }

    companion object {
        private const val USER_AGENT = "FridayAI/1.0 (local internet search)"
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_RESULTS = 3
        private const val MAX_TEXT_CHARS = 280
        private const val CONNECT_SECONDS = 8L
        private const val REQUEST_SECONDS = 12L
        private val RESULT_LINK = Regex(
            """<a\b(?=[^>]*\bclass=["'][^"']*\bresult__a\b[^"']*["'])(?=[^>]*\bhref=["']([^"']+)["'])[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val RESULT_SNIPPET = Regex(
            """<(?:a|div|span)\b[^>]*\bclass=["'][^"']*\bresult__snippet\b[^"']*["'][^>]*>(.*?)</(?:a|div|span)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val BING_LINK = Regex(
            """<h2\b[^>]*>\s*<a\b(?=[^>]*\bhref=["']([^"']+)["'])[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val BING_SNIPPET = Regex(
            """<p\b[^>]*>(.*?)</p>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val HTML_TAG = Regex("<[^>]+>")

        fun create(): SearchBackend = DuckDuckGoSearch(
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(REQUEST_SECONDS, TimeUnit.SECONDS)
                .callTimeout(REQUEST_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
        )

        internal fun parseSearchHtml(html: String): SearchResult = parseResults(html, RESULT_LINK, RESULT_SNIPPET)

        private fun parseBingHtml(html: String): SearchResult = parseResults(html, BING_LINK, BING_SNIPPET)

        private fun parseResults(
            html: String,
            linkPattern: Regex,
            snippetPattern: Regex
        ): SearchResult {
            val anchors = linkPattern.findAll(html).toList()
            val items = anchors.mapIndexedNotNull { index, match ->
                val url = cleanUrl(decode(match.groupValues[1])) ?: return@mapIndexedNotNull null
                val title = cleanText(match.groupValues[2])
                if (title.isBlank()) return@mapIndexedNotNull null
                val end = anchors.getOrNull(index + 1)?.range?.first ?: html.length
                val snippet = snippetPattern.find(html.substring(match.range.last + 1, end))
                    ?.groupValues?.get(1)?.let(::cleanText).orEmpty()
                SearchItem(title, snippet, url)
            }.take(MAX_RESULTS)
            return if (items.isEmpty()) SearchResult.Empty else SearchResult.Found(items)
        }

        private fun cleanUrl(raw: String): String? {
            val redirected = raw.substringAfter("uddg=", raw).substringBefore('&')
            val value = decode(URLDecoder.decode(redirected, StandardCharsets.UTF_8.name()))
            return value.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }

        private fun cleanText(text: String): String = decode(text.replace(HTML_TAG, " "))
            .replace(Regex("\\s+"), " ").trim().take(MAX_TEXT_CHARS)

        private fun decode(value: String): String = value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}

private const val MAX_QUERY_CHARS = 500"$(cat /tmp/LocalInternet.kt)
